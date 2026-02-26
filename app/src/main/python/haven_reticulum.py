"""
Haven Reticulum Bridge — Python-side module.

Provides functions callable from Kotlin via Chaquopy to:
1. Initialise an RNS transport instance connected to Sideband's shared instance
2. Establish rnsh remote shell sessions over Reticulum Links
3. Bridge I/O between the shell and the Kotlin terminal emulator

Uses the actual rnsh protocol classes (StreamDataMessage, WindowSizeMessage, etc.)
with RNS.vendor.umsgpack for serialization (bundled with RNS).
"""

import os
import queue
import threading
import time

# Lazy imports
_rns = None
_identity = None
_reticulum = None


def _ensure_rns():
    global _rns
    if _rns is None:
        import RNS
        _rns = RNS
    return _rns


def init_reticulum(config_dir, shared_instance_host="127.0.0.1",
                   shared_instance_port=37428, rpc_key=None):
    """
    Initialise RNS as a client connecting to Sideband's shared instance.

    Returns Haven's RNS identity hash (hex string).
    """
    global _reticulum, _identity

    # Guard against double-init (Python side may already have RNS running
    # even if the Kotlin flag was reset after an error)
    if _reticulum is not None:
        if _identity is not None:
            return _identity.hexhash
        # RNS running but no identity — fall through to load/create identity

    RNS = _ensure_rns()

    os.makedirs(config_dir, exist_ok=True)
    config_path = os.path.join(config_dir, "config")

    config_content = f"""[reticulum]
  enable_transport = false
  share_instance = false
  shared_instance_port = {shared_instance_port}
  instance_control_port = {shared_instance_port + 1}

[interfaces]
  [[Shared Instance]]
    type = TCPClientInterface
    enabled = true
    target_host = {shared_instance_host}
    target_port = {shared_instance_port}
"""
    with open(config_path, "w") as f:
        f.write(config_content)

    # RNS uses signal.signal() internally which only works on the main
    # thread.  On Android/Chaquopy we are called from a coroutine IO
    # thread, so patch signal temporarily to avoid the crash.
    import signal
    _orig_signal = signal.signal
    signal.signal = lambda *a, **kw: None
    try:
        _reticulum = RNS.Reticulum(
            configdir=config_dir,
            loglevel=RNS.LOG_VERBOSE,
        )
    finally:
        signal.signal = _orig_signal

    # Load or create a persistent identity for Haven
    identity_path = os.path.join(config_dir, "haven_identity")
    if os.path.isfile(identity_path):
        _identity = RNS.Identity.from_file(identity_path)
    else:
        _identity = RNS.Identity()
        _identity.to_file(identity_path)

    return _identity.hexhash


def get_identity_hash():
    """Return the hex hash of Haven's RNS identity, or None if not initialised."""
    if _identity is None:
        return None
    return _identity.hexhash


def resolve_destination(destination_hash_hex):
    """
    Resolve a destination hash. Blocks up to 15 seconds.
    Returns True if the path is known.
    """
    RNS = _ensure_rns()
    dest_hash = bytes.fromhex(destination_hash_hex)

    if RNS.Transport.has_path(dest_hash):
        return True

    RNS.Transport.request_path(dest_hash)

    for _ in range(150):
        time.sleep(0.1)
        if RNS.Transport.has_path(dest_hash):
            return True

    return False


# --- rnsh Session ---

class RnshSession:
    """
    An rnsh remote shell session over a Reticulum Link.

    Uses the rnsh protocol messages registered on an RNS Channel.
    Output is buffered in a queue for the Kotlin reader thread to poll.
    """

    def __init__(self, destination_hash_hex):
        self._RNS = _ensure_rns()
        self._dest_hash_hex = destination_hash_hex
        self._link = None
        self._channel = None
        self._closed = False
        self._connected = False
        self._output_queue = queue.Queue()

    def connect(self, rows=24, cols=80):
        """Establish a Link and start an rnsh shell session."""
        RNS = self._RNS

        dest_hash = bytes.fromhex(self._dest_hash_hex)
        dest_identity = RNS.Identity.recall(dest_hash)
        if dest_identity is None:
            raise RuntimeError(
                f"Cannot recall identity for {self._dest_hash_hex}. "
                "Path may not be resolved."
            )

        # Build destination matching rnsh's aspects
        destination = RNS.Destination(
            dest_identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "rnsh",
        )

        self._rows = rows
        self._cols = cols
        self._link = RNS.Link(destination)
        self._link.set_link_established_callback(self._on_link_established)
        self._link.set_link_closed_callback(self._on_link_closed)

        # Wait for link establishment
        timeout = 30.0
        start = time.time()
        while not self._connected and not self._closed:
            time.sleep(0.1)
            if time.time() - start > timeout:
                self._link.teardown()
                raise RuntimeError("Link establishment timed out")

        return True

    def _on_link_established(self, link):
        """Called when the RNS Link is established."""
        from rnsh.protocol import (
            register_message_types,
            VersionInfoMessage,
            WindowSizeMessage,
            ExecuteCommandMesssage,
            StreamDataMessage,
            CommandExitedMessage,
            ErrorMessage,
        )

        self._connected = True
        self._channel = link.get_channel()

        # Register rnsh message types on the channel
        register_message_types(self._channel)

        # Set up message handler
        self._channel.add_message_handler(self._on_message)

        # Protocol handshake: send version info
        self._channel.send(VersionInfoMessage())

        # Send initial window size
        self._channel.send(WindowSizeMessage(
            rows=self._rows, cols=self._cols, hpix=0, vpix=0,
        ))

        # Request interactive shell (empty cmdline = login shell)
        self._channel.send(ExecuteCommandMesssage(
            cmdline=[],
            pipe_stdin=False,
            pipe_stdout=False,
            pipe_stderr=False,
            tcflags=None,
            term=os.environ.get("TERM", "xterm-256color"),
            rows=self._rows,
            cols=self._cols,
            hpix=0,
            vpix=0,
        ))

    def _on_link_closed(self, link):
        if not self._closed:
            self._closed = True
            self._connected = False
            self._output_queue.put(None)

    def _on_message(self, message):
        """Handle incoming rnsh protocol messages."""
        from rnsh.protocol import (
            StreamDataMessage,
            CommandExitedMessage,
            ErrorMessage,
        )

        if isinstance(message, StreamDataMessage):
            if message.data:
                self._output_queue.put(bytes(message.data))
        elif isinstance(message, CommandExitedMessage):
            self._closed = True
            self._output_queue.put(None)
        elif isinstance(message, ErrorMessage):
            if message.fatal:
                self._closed = True
                self._output_queue.put(None)

    def read_output(self, timeout_ms=1000):
        """
        Read shell output. Blocks up to timeout_ms.
        Returns bytes, empty bytes on timeout, or None if disconnected.
        """
        if self._closed and self._output_queue.empty():
            return None

        try:
            data = self._output_queue.get(timeout=timeout_ms / 1000.0)
            return data  # None = disconnect sentinel
        except queue.Empty:
            if self._closed:
                return None
            return b""

    def send_input(self, data):
        """Send keyboard input to the remote shell."""
        if self._closed or not self._channel:
            return False
        try:
            from rnsh.protocol import StreamDataMessage
            msg = StreamDataMessage(
                stream_id=0,  # stdin
                data=bytes(data),
                eof=False,
                compressed=False,
            )
            self._channel.send(msg)
            return True
        except Exception:
            return False

    def resize(self, cols, rows):
        """Send window resize."""
        if self._closed or not self._channel:
            return
        try:
            from rnsh.protocol import WindowSizeMessage
            self._channel.send(WindowSizeMessage(
                rows=rows, cols=cols, hpix=0, vpix=0,
            ))
        except Exception:
            pass

    def close(self):
        if self._closed:
            return
        self._closed = True
        self._connected = False
        self._output_queue.put(None)
        try:
            if self._link:
                self._link.teardown()
        except Exception:
            pass

    @property
    def is_connected(self):
        return self._connected and not self._closed


# --- Module-level session registry ---

_sessions = {}
_lock = threading.Lock()


def create_session(destination_hash_hex, session_id, rows=24, cols=80):
    """Create and connect an rnsh session. Returns session_id."""
    session = RnshSession(destination_hash_hex)
    session.connect(rows=rows, cols=cols)

    with _lock:
        _sessions[session_id] = session

    return session_id


def read_output(session_id, timeout_ms=1000):
    """Read output. Returns bytes, empty bytes, or None."""
    with _lock:
        session = _sessions.get(session_id)
    if session is None:
        return None
    return session.read_output(timeout_ms)


def send_input(session_id, data):
    """Send input bytes. Returns True on success."""
    with _lock:
        session = _sessions.get(session_id)
    if session:
        return session.send_input(data)
    return False


def resize_session(session_id, cols, rows):
    """Send window resize."""
    with _lock:
        session = _sessions.get(session_id)
    if session:
        session.resize(cols, rows)


def is_connected(session_id):
    """Check if session is connected."""
    with _lock:
        session = _sessions.get(session_id)
    return session is not None and session.is_connected


def close_session(session_id):
    """Close and remove a session."""
    with _lock:
        session = _sessions.pop(session_id, None)
    if session:
        session.close()


def close_all():
    """Close all sessions. RNS instance is kept alive (cannot be restarted)."""
    with _lock:
        sessions = list(_sessions.values())
        _sessions.clear()

    for session in sessions:
        try:
            session.close()
        except Exception:
            pass


def get_status():
    """Status dict for debugging."""
    return {
        "initialised": _reticulum is not None,
        "identity": get_identity_hash(),
        "active_sessions": len(_sessions),
    }
