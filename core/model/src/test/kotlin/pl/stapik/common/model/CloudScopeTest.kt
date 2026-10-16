package pl.stapik.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudScopeTest {

    @Test
    fun `READ_WRITE is parsed and can write`() {
        assertEquals(CloudScope.READ_WRITE, CloudScope.fromWire("READ_WRITE"))
        assertTrue(CloudScope.READ_WRITE.canWrite)
    }

    @Test
    fun `anything else is read only`() {
        listOf("READ_ONLY", "", "read_write", "ADMIN", null).forEach { value ->
            assertEquals(CloudScope.READ_ONLY, CloudScope.fromWire(value))
        }
        assertFalse(CloudScope.READ_ONLY.canWrite)
    }
}
