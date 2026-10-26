package pl.stapik.common.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.stapik.common.model.CloudScope

class StoredDocumentTest {
    private fun decode(scopeField: String) = StoredDocument.decode(
        """{"version":1,"content":"{\"lastUpdate\":\"t\",\"payload\":{}}"$scopeField}""".toByteArray(),
    )

    @Test
    fun `a known scope is read back`() {
        assertEquals(CloudScope.READ_WRITE, decode(""","scope":"READ_WRITE"""").scope)
        assertEquals(CloudScope.READ_ONLY, decode(""","scope":"READ_ONLY"""").scope)
    }

    @Test
    fun `a scope from a newer version falls back to read only`() {
        assertEquals(CloudScope.READ_ONLY, decode(""","scope":"READ_WRITE_ADMIN"""").scope)
    }

    @Test
    fun `no scope stays unknown`() {
        assertNull(decode("").scope)
    }
}
