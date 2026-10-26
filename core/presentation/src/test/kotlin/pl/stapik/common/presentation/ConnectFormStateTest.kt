package pl.stapik.common.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectFormStateTest {
    @Test
    fun `toString hides the key and the passphrase`() {
        val text = ConnectFormState(apiUrl = "https://cloud.example", apiKey = "secret-key", passphrase = "secret-phrase").toString()
        assertFalse(text.contains("secret-key"))
        assertFalse(text.contains("secret-phrase"))
        assertTrue(text.contains("https://cloud.example"))
    }
}
