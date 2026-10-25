package pl.stapik.common.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConfigTest {
    @Test
    fun `toString hides the key and the passphrase`() {
        val text = CloudConfig("https://cloud.example", "secret-key", "secret-phrase").toString()
        assertFalse(text.contains("secret-key"))
        assertFalse(text.contains("secret-phrase"))
        assertTrue(text.contains("https://cloud.example"))
    }

    @Test
    fun `toString tells whether a passphrase is set`() {
        assertEquals("CloudConfig(apiUrl=u, apiKey=***, passphrase=null)", CloudConfig("u", "k").toString())
        assertEquals("CloudConfig(apiUrl=u, apiKey=***, passphrase=***)", CloudConfig("u", "k", "p").toString())
    }

    @Test
    fun `equality still compares the secrets`() {
        assertEquals(CloudConfig("u", "k", "p"), CloudConfig("u", "k", "p"))
        assertFalse(CloudConfig("u", "k", "p") == CloudConfig("u", "k2", "p"))
    }
}
