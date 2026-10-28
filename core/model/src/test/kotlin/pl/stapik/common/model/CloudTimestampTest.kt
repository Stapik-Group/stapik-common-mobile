package pl.stapik.common.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTimestampTest {
    @Test
    fun `identical text is the same`() {
        assertTrue(CloudTimestamp.sameInstant("2026-10-10T08:00:00Z", "2026-10-10T08:00:00Z"))
    }

    @Test
    fun `fraction precision does not matter`() {
        assertTrue(CloudTimestamp.sameInstant("2026-10-10T08:00:00Z", "2026-10-10T08:00:00.000Z"))
        assertTrue(CloudTimestamp.sameInstant("2026-10-10T08:00:00.5Z", "2026-10-10T08:00:00.500000Z"))
    }

    @Test
    fun `a difference below the storage precision does not matter`() {
        assertTrue(CloudTimestamp.sameInstant("2026-10-10T08:00:00.123456789Z", "2026-10-10T08:00:00.123457Z"))
        assertTrue(CloudTimestamp.sameInstant("2026-10-10T08:00:00.123456789Z", "2026-10-10T08:00:00.123456Z"))
    }

    @Test
    fun `a difference of two microseconds matters`() {
        assertFalse(CloudTimestamp.sameInstant("2026-10-10T08:00:00.123456Z", "2026-10-10T08:00:00.123458Z"))
    }

    @Test
    fun `zulu and a zero offset are the same`() {
        assertTrue(CloudTimestamp.sameInstant("2026-10-10T08:00:00Z", "2026-10-10T08:00:00+00:00"))
    }

    @Test
    fun `another offset for the same instant is the same`() {
        assertTrue(CloudTimestamp.sameInstant("2026-10-10T08:00:00Z", "2026-10-10T10:00:00+02:00"))
    }

    @Test
    fun `different instants differ`() {
        assertFalse(CloudTimestamp.sameInstant("2026-10-10T08:00:00Z", "2026-10-10T08:00:01Z"))
        assertFalse(CloudTimestamp.sameInstant("2026-10-10T08:00:00Z", "2026-10-10T08:00:00.001Z"))
    }

    @Test
    fun `text that is not a time is compared as text`() {
        assertTrue(CloudTimestamp.sameInstant("v7", "v7"))
        assertFalse(CloudTimestamp.sameInstant("v7", "v8"))
        assertFalse(CloudTimestamp.sameInstant("", "2026-10-10T08:00:00Z"))
    }

    @Test
    fun `a missing time is never the same`() {
        assertFalse(CloudTimestamp.sameInstant("2026-10-10T08:00:00Z", null))
    }
}
