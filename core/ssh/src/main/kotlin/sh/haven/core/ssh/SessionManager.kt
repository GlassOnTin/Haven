package sh.haven.core.ssh

/**
 * Session manager options for wrapping SSH shells in persistent sessions.
 * @param label Display name for logging.
 * @param command Template that produces an attach-or-create shell command given a session name,
 *                or null for no session manager.
 */
enum class SessionManager(val label: String, val command: ((String) -> String)?) {
    NONE("None", null),
    TMUX("tmux", { name -> "tmux new-session -A -s $name" }),
    ZELLIJ("zellij", { name -> "zellij attach $name --create" }),
    SCREEN("screen", { name -> "screen -dRR $name" }),
    BYOBU("byobu", { name -> "byobu new-session -A -s $name" }),
}
