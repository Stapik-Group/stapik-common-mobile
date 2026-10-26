package pl.stapik.common.cloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private val redactor = Redactor.of(CloudConfig("https://c.example", "key-12345", "correct horse"))

    @Test
    fun `the key and the passphrase are masked wherever they appear`() {
        assertEquals("sent *** and *** and ***", redactor.redact("sent key-12345 and correct horse and key-12345"))
    }

    @Test
    fun `a secret that contains another is masked whole`() {
        val nested = Redactor(listOf("abc", "abcdef"))
        assertEquals("***", nested.redact("abcdef"))
    }

    @Test
    fun `missing and empty secrets are ignored`() {
        val none = Redactor(listOf(null, ""))
        assertEquals("nothing to hide", none.redact("nothing to hide"))
    }

    @Test
    fun `text without secrets is unchanged`() {
        assertEquals("GET https://c.example/api/v1/me failed", redactor.redact("GET https://c.example/api/v1/me failed"))
    }

    @Test
    fun `credential headers and fields are masked even when the value is not a known secret`() {
        assertEquals("x-api-key: ***", Redactor.None.redact("x-api-key: other-key"))
        assertEquals("Authorization=***", Redactor.None.redact("Authorization=Bearer"))
        assertEquals("""{"apiKey":"***"}""", Redactor.None.redact("""{"apiKey":"zzz"}"""))
        assertEquals("passphrase = ***", Redactor.None.redact("passphrase = hunter2"))
    }

    @Test
    fun `credentials inside a url are masked`() {
        assertEquals("https://***@c.example/path", Redactor.None.redact("https://user:secret@c.example/path"))
    }

    @Test
    fun `describe walks the causes and masks each message`() {
        val error = IllegalStateException("outer key-12345", IOException("inner correct horse"))
        val text = redactor.describe(error)
        assertEquals("IllegalStateException: outer *** <- IOException: inner ***", text)
    }

    @Test
    fun `the stack trace is masked too`() {
        val trace = redactor.stackTrace(IOException("failed with key-12345"))
        assertTrue(trace.contains("IOException: failed with ***"))
        assertFalse(trace.contains("key-12345"))
    }

    @Test
    fun `a request prints without its key and without its body`() {
        val request = HttpRequest("PUT", "https://c.example/api/v1/documents/a", mapOf("x-api-key" to "key-12345", "accept" to "application/json"), "document text")
        val text = request.toString()
        assertFalse(text.contains("key-12345"))
        assertFalse(text.contains("document text"))
        assertTrue(text.contains("accept=application/json"))
    }
}
