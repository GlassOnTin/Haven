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
_gateways = {}  # (host, port) -> TCPClientInterface instance
_init_mode = None  # "sideband" or "gateway" — tracks how RNS was first initialised
_announced = {}  # dest_hash_hex -> {"hops": int, "timestamp": float}
_announce_handler = None


def _ensure_rns():
    global _rns
    if _rns is None:
        import RNS
        _rns = RNS
    return _rns


def _is_sideband_target(host, port):
    """Check if the target is a local Sideband shared instance."""
    return host in ("127.0.0.1", "localhost", "::1") and port == 37428


def init_reticulum(config_dir, shared_instance_host="127.0.0.1",
                   shared_instance_port=37428):
    """
    Initialise RNS and connect to either:
    - Sideband's TCP shared instance (localhost:37428) — joins as shared instance client
    - A remote TCP gateway (any other host:port) — uses TCPClientInterface

    Called before every connection. First call bootstraps RNS; subsequent
    calls add new gateway interfaces if the host:port hasn't been seen.

    The first call's mode (sideband vs gateway) determines the RNS instance
    type for the process lifetime. If Sideband mode was initialised first,
    direct gateways are added as additional TCPClientInterfaces. If gateway
    mode was initialised first, Sideband connections are not possible (RNS
    cannot switch to shared instance client mode after standalone init).

    Returns Haven's RNS identity hash (hex string).
    """
    global _reticulum, _identity, _gateways, _init_mode, _announce_handler

    shared_instance_port = int(shared_instance_port)
    sideband_mode = _is_sideband_target(shared_instance_host, shared_instance_port)
    gateway = (shared_instance_host, shared_instance_port)

    if _reticulum is not None:
        # RNS already running
        if not sideband_mode:
            # Add a direct gateway interface (works in both sideband and standalone mode)
            _ensure_gateway(shared_instance_host, shared_instance_port)
        elif _init_mode == "gateway":
            # User wants Sideband but RNS was initialised in gateway mode.
            # Cannot switch — log a warning.
            print("[Haven] WARNING: RNS was initialised in gateway mode. "
                  "Cannot switch to Sideband shared instance. "
                  "Restart Haven to use Local Sideband.")

        if _identity is not None:
            return _identity.hexhash
        # RNS running but no identity — fall through to load/create identity

    else:
        # First init — bootstrap RNS
        RNS = _ensure_rns()

        os.makedirs(config_dir, exist_ok=True)
        config_path = os.path.join(config_dir, "config")

        if sideband_mode:
            # Connect to Sideband's TCP shared instance.
            # share_instance = true + shared_instance_type = tcp makes RNS
            # try to create a TCP shared instance on port 37428; when that
            # fails (Sideband already owns it), RNS falls back to connecting
            # as a client — which is what we want.
            config_content = f"""[reticulum]
  enable_transport = false
  share_instance = true
  shared_instance_type = tcp
  shared_instance_port = {shared_instance_port}
  instance_control_port = 0

[interfaces]
"""
            _init_mode = "sideband"
        else:
            # Connect to a remote TCP gateway directly
            config_content = f"""[reticulum]
  enable_transport = false
  share_instance = false
  shared_instance_port = 0
  instance_control_port = 0

[interfaces]
  [[Gateway {shared_instance_host}:{shared_instance_port}]]
    type = TCPClientInterface
    enabled = true
    target_host = {shared_instance_host}
    target_port = {shared_instance_port}
"""
            _init_mode = "gateway"

        # Always write config fresh — avoids stale config from previous runs
        with open(config_path, "w") as f:
            f.write(config_content)

        print(f"[Haven] init_reticulum: mode={_init_mode} host={shared_instance_host} port={shared_instance_port}")
        print(f"[Haven] Config:\n{config_content}")

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

        print(f"[Haven] RNS initialized: shared={_reticulum.is_shared_instance} connected_to_shared={_reticulum.is_connected_to_shared_instance} standalone={_reticulum.is_standalone_instance}")

        if sideband_mode and not _reticulum.is_connected_to_shared_instance:
            print("[Haven] WARNING: Expected to connect to Sideband shared instance but failed. "
                  "Check that Sideband is running with 'Share Reticulum Instance' enabled.")

        if not sideband_mode:
            _gateways[gateway] = None  # Interface created by RNS from config

        # Register rnsh announce handler
        _announce_handler = _RnshAnnounceHandler()
        RNS.Transport.register_announce_handler(_announce_handler)
        print("[Haven] Registered rnsh announce handler")

    # Load or create a persistent identity for Haven
    RNS = _ensure_rns()
    identity_path = os.path.join(config_dir, "haven_identity")
    if os.path.isfile(identity_path):
        _identity = RNS.Identity.from_file(identity_path)
    else:
        _identity = RNS.Identity()
        _identity.to_file(identity_path)

    return _identity.hexhash


def _ensure_gateway(host, port):
    """Add a TCPClientInterface for a gateway if not already connected."""
    global _gateways
    gateway = (host, port)
    if gateway in _gateways:
        return

    RNS = _ensure_rns()
    from RNS.vendor.configobj import ConfigObj

    name = f"Gateway {host}:{port}"
    config = ConfigObj()
    config["name"] = name
    config["target_host"] = host
    config["target_port"] = str(port)

    print(f"[Haven] Adding gateway interface: {name}")

    from RNS.Interfaces.TCPInterface import TCPClientInterface
    interface = TCPClientInterface(owner=RNS.Transport, configuration=config)
    _reticulum._add_interface(interface)
    _gateways[gateway] = interface
    print(f"[Haven] Gateway {name} added OK")


class _RnshAnnounceHandler:
    """Collect rnsh destination announces from the network."""
    aspect_filter = "rnsh"
    receive_path_responses = True

    def received_announce(self, destination_hash, announced_identity, app_data,
                          announce_packet_hash=None, is_path_response=False):
        RNS = _ensure_rns()
        dest_hex = destination_hash.hex()
        hops = -1
        if destination_hash in RNS.Transport.path_table:
            hops = RNS.Transport.path_table[destination_hash][2]
        _announced[dest_hex] = {
            "hops": hops,
            "timestamp": time.time(),
        }
        print(f"[Haven] rnsh announce: {dest_hex} ({hops} hops)")


def get_discovered_destinations():
    """
    Return a list of discovered rnsh destinations as JSON string.
    Each entry: {"hash": "<hex>", "hops": <int>}
    Returns destinations heard via announces.
    """
    import json
    results = []
    for dest_hex, info in _announced.items():
        results.append({
            "hash": dest_hex,
            "hops": info.get("hops", -1),
        })
    # Sort by hop count (nearest first), unknowns last
    results.sort(key=lambda d: d["hops"] if d["hops"] >= 0 else 999)
    return json.dumps(results)


def get_identity_hash():
    """Return the hex hash of Haven's RNS identity, or None if not initialised."""
    if _identity is None:
        return None
    return _identity.hexhash


def get_init_mode():
    """Return the RNS init mode: 'sideband', 'gateway', or None."""
    return _init_mode


def probe_sideband(config_dir, host="127.0.0.1", port=37428):
    """
    Check if Sideband's shared instance is listening, and if so,
    speculatively initialise RNS in Sideband mode to start collecting
    announces. Safe to call multiple times — no-op if already initialised.

    Returns True if connected to Sideband, False otherwise.
    """
    import socket

    if _reticulum is not None:
        # Already initialised — return current state
        return _init_mode == "sideband" and _reticulum.is_connected_to_shared_instance

    # TCP probe — check if Sideband is listening before committing
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(1.0)
        sock.connect((host, int(port)))
        sock.close()
    except (OSError, socket.timeout):
        print(f"[Haven] probe_sideband: no listener on {host}:{port}")
        return False

    print(f"[Haven] probe_sideband: Sideband detected on {host}:{port}, initialising...")
    init_reticulum(config_dir, host, int(port))
    return _reticulum is not None and _reticulum.is_connected_to_shared_instance


def request_path(destination_hash_hex):
    """Request a path to a destination. Non-blocking — the response
    will arrive as a path response and trigger the announce handler."""
    RNS = _ensure_rns()
    dest_hash = bytes.fromhex(destination_hash_hex)
    if not RNS.Transport.has_path(dest_hash):
        RNS.Transport.request_path(dest_hash)
        print(f"[Haven] request_path: requested path for {destination_hash_hex}")
    return RNS.Transport.has_path(dest_hash)


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

        # Request interactive shell (empty cmdline = login shell)
        # Window size is included in the execute message — do NOT send
        # a separate WindowSizeMessage before the command, as rnsh only
        # accepts ExecuteCommandMessage in LSSTATE_WAIT_CMD state.
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
        "init_mode": _init_mode,
        "identity": get_identity_hash(),
        "active_sessions": len(_sessions),
    }
