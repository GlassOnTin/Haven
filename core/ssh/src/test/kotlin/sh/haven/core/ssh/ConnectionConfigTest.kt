package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionConfigTest {

    @Test
    fun `constructor accepts valid config`() {
        val config = ConnectionConfig(host = "example.com", port = 22, username = "root")
        assertEquals("example.com", config.host)
        assertEquals(22, config.port)
        assertEquals("root", config.username)
    }

    @Test
    fun `constructor defaults port to 22`() {
        val config = ConnectionConfig(host = "example.com", username = "user")
        assertEquals(22, config.port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects blank host`() {
        ConnectionConfig(host = "", username = "user")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects blank username`() {
        ConnectionConfig(host = "example.com", username = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects port 0`() {
        ConnectionConfig(host = "example.com", port = 0, username = "user")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects port above 65535`() {
        ConnectionConfig(host = "example.com", port = 65536, username = "user")
    }

    @Test
    fun `constructor accepts port 1`() {
        val config = ConnectionConfig(host = "example.com", port = 1, username = "user")
        assertEquals(1, config.port)
    }

    @Test
    fun `constructor accepts port 65535`() {
        val config = ConnectionConfig(host = "example.com", port = 65535, username = "user")
        assertEquals(65535, config.port)
    }

    // Quick connect parsing

    @Test
    fun `parseQuickConnect handles user at host colon port`() {
        val config = ConnectionConfig.parseQuickConnect("root@192.168.1.1:2222")
        assertNotNull(config)
        assertEquals("root", config!!.username)
        assertEquals("192.168.1.1", config.host)
        assertEquals(2222, config.port)
    }

    @Test
    fun `parseQuickConnect handles user at host without port`() {
        val config = ConnectionConfig.parseQuickConnect("admin@server.example.com")
        assertNotNull(config)
        assertEquals("admin", config!!.username)
        assertEquals("server.example.com", config.host)
        assertEquals(22, config.port)
    }

    @Test
    fun `parseQuickConnect returns null for host without user`() {
        val config = ConnectionConfig.parseQuickConnect("example.com")
        assertNull(config)
    }

    @Test
    fun `parseQuickConnect returns null for blank input`() {
        assertNull(ConnectionConfig.parseQuickConnect(""))
        assertNull(ConnectionConfig.parseQuickConnect("   "))
    }

    @Test
    fun `parseQuickConnect trims whitespace`() {
        val config = ConnectionConfig.parseQuickConnect("  user@host:22  ")
        assertNotNull(config)
        assertEquals("user", config!!.username)
        assertEquals("host", config.host)
    }

    @Test
    fun `parseQuickConnect returns null for invalid port`() {
        assertNull(ConnectionConfig.parseQuickConnect("user@host:99999"))
    }

    @Test
    fun `parseQuickConnect handles IPv4 address`() {
        val config = ConnectionConfig.parseQuickConnect("deploy@10.0.0.1:8022")
        assertNotNull(config)
        assertEquals("10.0.0.1", config!!.host)
        assertEquals(8022, config.port)
    }

    // Auth method equality

    @Test
    fun `PrivateKey equals compares key bytes`() {
        val key1 = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1, 2, 3))
        val key2 = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1, 2, 3))
        val key3 = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(4, 5, 6))
        assertEquals(key1, key2)
        assert(key1 != key3)
    }
}
