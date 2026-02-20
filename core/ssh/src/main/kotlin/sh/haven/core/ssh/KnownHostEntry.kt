package sh.haven.core.ssh

import java.security.MessageDigest
import java.util.Base64

/**
 * Represents a single known_hosts entry, compatible with OpenSSH format.
 */
data class KnownHostEntry(
    val hostname: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
) {
    /**
     * Returns the SHA-256 fingerprint of the host key, formatted like:
     * SHA256:base64string
     */
    fun fingerprint(): String {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    /**
     * Formats as an OpenSSH known_hosts line.
     * Uses [host]:port format when port != 22.
     */
    fun toKnownHostsLine(): String {
        val hostField = if (port == 22) hostname else "[$hostname]:$port"
        return "$hostField $keyType $publicKeyBase64"
    }

    companion object {
        /**
         * Parse a single OpenSSH known_hosts line.
         * Returns null for comments, blank lines, or unparseable lines.
         */
        fun parse(line: String): KnownHostEntry? {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return null

            val parts = trimmed.split(" ", limit = 3)
            if (parts.size != 3) return null

            val (hostField, keyType, keyBase64) = parts

            val (hostname, port) = parseHostField(hostField)
            return KnownHostEntry(
                hostname = hostname,
                port = port,
                keyType = keyType,
                publicKeyBase64 = keyBase64,
            )
        }

        private fun parseHostField(field: String): Pair<String, Int> {
            // [host]:port format
            val bracketMatch = Regex("""\[(.+)]:(\d+)""").matchEntire(field)
            if (bracketMatch != null) {
                return bracketMatch.groupValues[1] to bracketMatch.groupValues[2].toInt()
            }
            // Plain hostname (port 22)
            return field to 22
        }
    }
}
