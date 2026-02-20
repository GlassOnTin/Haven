package sh.haven.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile

class ConnectionProfileTest {

    @Test
    fun `default port is 22`() {
        val profile = ConnectionProfile(
            label = "test",
            host = "example.com",
            username = "user",
        )
        assertEquals(22, profile.port)
    }

    @Test
    fun `default auth type is PASSWORD`() {
        val profile = ConnectionProfile(
            label = "test",
            host = "example.com",
            username = "user",
        )
        assertEquals(ConnectionProfile.AuthType.PASSWORD, profile.authType)
    }

    @Test
    fun `id is auto-generated UUID`() {
        val p1 = ConnectionProfile(label = "a", host = "h", username = "u")
        val p2 = ConnectionProfile(label = "a", host = "h", username = "u")
        assertNotEquals("Each profile should get a unique ID", p1.id, p2.id)
    }

    @Test
    fun `keyId is null by default`() {
        val profile = ConnectionProfile(label = "t", host = "h", username = "u")
        assertNull(profile.keyId)
    }

    @Test
    fun `lastConnected is null by default`() {
        val profile = ConnectionProfile(label = "t", host = "h", username = "u")
        assertNull(profile.lastConnected)
    }

    @Test
    fun `copy preserves id`() {
        val original = ConnectionProfile(label = "a", host = "h", username = "u")
        val copy = original.copy(label = "b")
        assertEquals(original.id, copy.id)
        assertEquals("b", copy.label)
    }

    @Test
    fun `AuthType KEY is distinct from PASSWORD`() {
        assertNotEquals(ConnectionProfile.AuthType.PASSWORD, ConnectionProfile.AuthType.KEY)
    }
}
