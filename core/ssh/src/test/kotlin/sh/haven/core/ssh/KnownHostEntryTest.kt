package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class KnownHostEntryTest {

    // A dummy key for testing (not a real SSH key, just base64 bytes)
    private val testKeyBase64 = Base64.getEncoder().encodeToString("test-host-key-data".toByteArray())

    @Test
    fun `parse standard known_hosts line`() {
        val line = "example.com ssh-ed25519 $testKeyBase64"
        val entry = KnownHostEntry.parse(line)
        assertNotNull(entry)
        assertEquals("example.com", entry!!.hostname)
        assertEquals(22, entry.port)
        assertEquals("ssh-ed25519", entry.keyType)
        assertEquals(testKeyBase64, entry.publicKeyBase64)
    }

    @Test
    fun `parse known_hosts line with non-standard port`() {
        val line = "[example.com]:2222 ssh-rsa $testKeyBase64"
        val entry = KnownHostEntry.parse(line)
        assertNotNull(entry)
        assertEquals("example.com", entry!!.hostname)
        assertEquals(2222, entry.port)
        assertEquals("ssh-rsa", entry.keyType)
    }

    @Test
    fun `parse returns null for comments`() {
        assertNull(KnownHostEntry.parse("# This is a comment"))
    }

    @Test
    fun `parse returns null for blank lines`() {
        assertNull(KnownHostEntry.parse(""))
        assertNull(KnownHostEntry.parse("   "))
    }

    @Test
    fun `parse returns null for malformed lines`() {
        assertNull(KnownHostEntry.parse("only-one-field"))
        assertNull(KnownHostEntry.parse("two fields"))
    }

    @Test
    fun `toKnownHostsLine roundtrips for port 22`() {
        val entry = KnownHostEntry("example.com", 22, "ssh-ed25519", testKeyBase64)
        val line = entry.toKnownHostsLine()
        assertEquals("example.com ssh-ed25519 $testKeyBase64", line)

        val parsed = KnownHostEntry.parse(line)
        assertEquals(entry, parsed)
    }

    @Test
    fun `toKnownHostsLine roundtrips for non-standard port`() {
        val entry = KnownHostEntry("example.com", 2222, "ssh-rsa", testKeyBase64)
        val line = entry.toKnownHostsLine()
        assertEquals("[example.com]:2222 ssh-rsa $testKeyBase64", line)

        val parsed = KnownHostEntry.parse(line)
        assertEquals(entry, parsed)
    }

    @Test
    fun `fingerprint returns SHA256 format`() {
        val entry = KnownHostEntry("example.com", 22, "ssh-ed25519", testKeyBase64)
        val fingerprint = entry.fingerprint()
        assert(fingerprint.startsWith("SHA256:")) {
            "Expected fingerprint to start with SHA256:, got: $fingerprint"
        }
        // SHA256 base64 without padding should be 43 chars
        val b64Part = fingerprint.removePrefix("SHA256:")
        assert(b64Part.length == 43) {
            "Expected 43 char base64, got ${b64Part.length}: $b64Part"
        }
    }

    @Test
    fun `fingerprint is deterministic`() {
        val entry = KnownHostEntry("example.com", 22, "ssh-ed25519", testKeyBase64)
        assertEquals(entry.fingerprint(), entry.fingerprint())
    }

    @Test
    fun `parse handles IPv4 address`() {
        val line = "192.168.1.1 ssh-ed25519 $testKeyBase64"
        val entry = KnownHostEntry.parse(line)
        assertNotNull(entry)
        assertEquals("192.168.1.1", entry!!.hostname)
    }

    @Test
    fun `parse handles bracketed IPv6 with port`() {
        val line = "[::1]:22 ssh-ed25519 $testKeyBase64"
        val entry = KnownHostEntry.parse(line)
        assertNotNull(entry)
        assertEquals("::1", entry!!.hostname)
        assertEquals(22, entry.port)
    }
}
