package pl.stapik.common.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.stapik.common.model.CloudInfo
import pl.stapik.common.model.CloudScope
import pl.stapik.common.model.LocalDocument

class SyncPlannerTest {

    private val rw = CloudInfo(CloudScope.READ_WRITE, "cloud-1")
    private val ro = CloudInfo(CloudScope.READ_ONLY, "cloud-1")
    private val t0 = "2026-10-10T08:00:00Z"
    private val t1 = "2026-10-12T08:00:00Z"

    private fun never(dirty: Boolean = false) = LocalDocument("x", dirty = dirty)

    private fun synced(dirty: Boolean = false, modified: String? = null, identity: String? = "cloud-1") =
        LocalDocument(
            content = "x",
            cloudBaseline = t0,
            dirty = dirty,
            modifiedAt = modified?.let(Instant::parse),
            scope = CloudScope.READ_WRITE,
            cloudIdentity = identity,
        )

    private fun present(updatedAt: String, hasData: Boolean = true) = RemoteState.Present(updatedAt, hasData)

    private fun plan(
        local: LocalDocument<*>,
        localHasData: Boolean = true,
        remote: RemoteState,
        cloud: CloudInfo = rw,
        choice: FirstConnectionChoice? = null,
    ) = SyncPlanner.plan(local, localHasData, remote, cloud, choice)

    // --- first connection ---

    @Test
    fun `first connection, no local data - take the cloud without asking`() {
        assertEquals(
            SyncPlan.UseRemote(false),
            plan(never(), localHasData = false, remote = present(t1)),
        )
    }

    @Test
    fun `first connection, cloud has no document - send local without asking`() {
        assertEquals(SyncPlan.PushLocal(false), plan(never(dirty = true), remote = RemoteState.Absent))
    }

    @Test
    fun `first connection, cloud document is empty - send local without asking`() {
        assertEquals(SyncPlan.PushLocal(false), plan(never(dirty = true), remote = present(t1, hasData = false)))
    }

    @Test
    fun `first connection, data on both sides - ask`() {
        assertEquals(SyncPlan.NeedsFirstConnectionChoice(true), plan(never(dirty = true), remote = present(t1)))
    }

    @Test
    fun `first connection, data on both sides, read-only key - ask without the keep-local option`() {
        assertEquals(
            SyncPlan.NeedsFirstConnectionChoice(false),
            plan(never(dirty = true), remote = present(t1), cloud = ro),
        )
    }

    @Test
    fun `first connection answered with USE_CLOUD keeps local as conflict copy`() {
        assertEquals(
            SyncPlan.UseRemote(true),
            plan(never(dirty = true), remote = present(t1), choice = FirstConnectionChoice.USE_CLOUD),
        )
    }

    @Test
    fun `first connection answered with SEND_LOCAL keeps cloud as conflict copy`() {
        assertEquals(
            SyncPlan.PushLocal(true),
            plan(never(dirty = true), remote = present(t1), choice = FirstConnectionChoice.SEND_LOCAL),
        )
    }

    @Test
    fun `SEND_LOCAL is not honoured with a read-only key`() {
        assertEquals(
            SyncPlan.NeedsFirstConnectionChoice(false),
            plan(never(dirty = true), remote = present(t1), cloud = ro, choice = FirstConnectionChoice.SEND_LOCAL),
        )
    }

    @Test
    fun `first connection, local data but read-only key and empty cloud - keep local unsynced`() {
        assertEquals(SyncPlan.KeepLocalUnsynced, plan(never(dirty = true), remote = RemoteState.Absent, cloud = ro))
    }

    @Test
    fun `first connection, nothing anywhere - up to date`() {
        assertEquals(SyncPlan.UpToDate, plan(never(), localHasData = false, remote = RemoteState.Absent))
    }

    @Test
    fun `a different cloud counts as a first connection`() {
        assertEquals(
            SyncPlan.NeedsFirstConnectionChoice(true),
            plan(synced(dirty = true, identity = "other-cloud"), remote = present(t1)),
        )
    }

    @Test
    fun `a baseline without recorded identity is trusted`() {
        assertEquals(SyncPlan.UpToDate, plan(synced(identity = null), remote = present(t0)))
    }

    // --- known cloud ---

    @Test
    fun `nothing changed on either side`() {
        assertEquals(SyncPlan.UpToDate, plan(synced(), remote = present(t0)))
    }

    @Test
    fun `only local changed - push`() {
        assertEquals(SyncPlan.PushLocal(false), plan(synced(dirty = true), remote = present(t0)))
    }

    @Test
    fun `only local changed but read-only key - keep local unsynced`() {
        assertEquals(SyncPlan.KeepLocalUnsynced, plan(synced(dirty = true), remote = present(t0), cloud = ro))
    }

    @Test
    fun `only remote changed - take it, no conflict copy`() {
        assertEquals(SyncPlan.UseRemote(false), plan(synced(), remote = present(t1)))
    }

    @Test
    fun `both changed, local newer - push and keep cloud copy`() {
        assertEquals(
            SyncPlan.PushLocal(true),
            plan(synced(dirty = true, modified = "2026-10-13T08:00:00Z"), remote = present(t1)),
        )
    }

    @Test
    fun `both changed, cloud newer - take cloud and keep local copy`() {
        assertEquals(
            SyncPlan.UseRemote(true),
            plan(synced(dirty = true, modified = "2026-10-11T08:00:00Z"), remote = present(t1)),
        )
    }

    @Test
    fun `both changed, local newer but read-only key - take cloud and keep local copy`() {
        assertEquals(
            SyncPlan.UseRemote(true),
            plan(synced(dirty = true, modified = "2026-10-13T08:00:00Z"), remote = present(t1), cloud = ro),
        )
    }

    @Test
    fun `both changed, no local modification time - local wins`() {
        assertEquals(SyncPlan.PushLocal(true), plan(synced(dirty = true), remote = present(t1)))
    }

    @Test
    fun `both changed, unparseable cloud time - local wins`() {
        assertEquals(
            SyncPlan.PushLocal(true),
            plan(synced(dirty = true, modified = "2026-10-13T08:00:00Z"), remote = present("not-a-time")),
        )
    }

    @Test
    fun `cloud document disappeared - push local data back`() {
        assertEquals(SyncPlan.PushLocal(false), plan(synced(), remote = RemoteState.Absent))
    }

    @Test
    fun `cloud document disappeared and local is empty - nothing to do`() {
        assertEquals(SyncPlan.UpToDate, plan(synced(), localHasData = false, remote = RemoteState.Absent))
    }
}
