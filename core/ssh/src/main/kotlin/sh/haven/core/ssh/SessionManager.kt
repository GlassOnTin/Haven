package sh.haven.core.ssh

/**
 * Session manager options for wrapping SSH shells in persistent sessions.
 * @param label Display name for logging.
 * @param command Template that produces an attach-or-create shell command given a session name,
 *                or null for no session manager.
 * @param listCommand Shell command to list existing sessions, or null if not applicable.
 * @param killCommand Template that produces a command to kill/delete a session by name.
 */
enum class SessionManager(
    val label: String,
    val command: ((String) -> String)?,
    val listCommand: String?,
    val killCommand: ((String) -> String)? = null,
) {
    NONE("None", null, null),
    TMUX("tmux", { name -> "tmux new-session -A -s $name" }, "tmux ls -F '#{session_name}' 2>/dev/null", { name -> "tmux kill-session -t $name" }),
    ZELLIJ("zellij", { name -> "zellij attach $name --create" }, "zellij ls 2>/dev/null", { name -> "zellij kill-session $name 2>/dev/null; zellij delete-session $name 2>/dev/null" }),
    SCREEN("screen", { name -> "screen -dRR $name" }, "screen -ls 2>/dev/null", { name -> "screen -S $name -X quit" }),
    BYOBU("byobu", { name -> "byobu new-session -A -s $name" }, "byobu ls -F '#{session_name}' 2>/dev/null", { name -> "byobu kill-session -t $name" });

    companion object {
        /** Strip ANSI escape sequences (colors, bold, etc.) from a string. */
        private val ANSI_REGEX = Regex("\\x1B\\[[0-9;]*[a-zA-Z]")
        private fun stripAnsi(s: String): String = s.replace(ANSI_REGEX, "")

        /**
         * Parse session list output into session names.
         * Returns empty list if output is blank or unparseable.
         */
        fun parseSessionList(manager: SessionManager, output: String): List<String> {
            val clean = stripAnsi(output)
            if (clean.isBlank()) return emptyList()
            return when (manager) {
                NONE -> emptyList()
                TMUX, BYOBU -> clean.lines().filter { it.isNotBlank() }
                ZELLIJ -> clean.lines()
                    .filter { it.isNotBlank() && !it.contains("EXITED") }
                    .map { it.trim().split(Regex("\\s+")).first() }
                    .filter { it.isNotBlank() && !it.startsWith("No ") }
                SCREEN -> clean.lines()
                    .map { it.trim() }
                    .filter { it.contains(".") && (it.contains("Detached") || it.contains("Attached")) }
                    .mapNotNull { line ->
                        val firstPart = line.split(Regex("\\s+")).firstOrNull() ?: return@mapNotNull null
                        val dotIdx = firstPart.indexOf('.')
                        if (dotIdx >= 0) firstPart.substring(dotIdx + 1) else null
                    }
            }
        }
    }
}
