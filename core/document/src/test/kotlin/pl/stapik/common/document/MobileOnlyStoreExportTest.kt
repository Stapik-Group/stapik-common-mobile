package pl.stapik.common.document

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobileOnlyStoreExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Serializable
    data class Prefs(val n: Int = 0)

    private val dir: Path get() = tmp.root.toPath()

    private fun store(sub: String) =
        MobileOnlyStore(dir.resolve(sub), "prefs", Prefs.serializer(), Prefs())

    @Test
    fun `exported data can be imported into another store`() = runBlocking {
        val source = store("a").apply { save(Prefs(42)) }
        val target = store("b")
        target.importJson(source.exportJson())
        assertEquals(Prefs(42), target.load())
    }

    @Test
    fun `export of an empty store is the default value`() = runBlocking {
        val target = store("b")
        target.importJson(store("a").exportJson())
        assertEquals(Prefs(), target.load())
    }

    @Test
    fun `import text from a newer version is reported with both versions`() = runBlocking {
        val s = store("a").apply { save(Prefs(5)) }
        val error = assertThrows(NewerStoredVersionException::class.java) { runBlocking { s.importJson("""{"version":9,"data":{"n":1}}""") } }
        assertEquals(9, error.found)
        assertEquals(1, error.supported)
        assertEquals(Prefs(5), s.load())
    }

    @Test
    fun `invalid import text changes nothing`() = runBlocking {
        val s = store("a").apply { save(Prefs(5)) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { s.importJson("not json") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { s.importJson("""{"version":9,"data":{"n":1}}""") } }
        assertEquals(Prefs(5), s.load())
    }
}
