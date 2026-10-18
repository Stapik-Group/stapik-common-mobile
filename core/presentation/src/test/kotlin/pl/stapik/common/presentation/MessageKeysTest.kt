package pl.stapik.common.presentation

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the message keys used by this module, the string resources of `:core:i18n` and its
 * translations in step. Gradle runs tests with the module directory as the working directory.
 */
class MessageKeysTest {
    private val resDir = File("../i18n/src/main/res")
    private val locales = listOf("values", "values-pl", "values-de")

    private class Entry(val text: String)

    private fun load(folder: String): Map<String, Entry> {
        val file = File(resDir, "$folder/strings.xml")
        assertTrue("Missing $file", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to Entry(node.textContent)
        }
    }

    private fun resource(key: String) = key.replace('.', '_')

    private fun keysUsedInCode(): Set<String> {
        val main = File("src/main/kotlin")
        val literal = Regex("\"((?:sync|connect)\\.[a-z_.]+)\"")
        return main.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> literal.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
    }

    @Test
    fun `every key used in code has a text in every language`() {
        val used = keysUsedInCode()
        assertTrue("No keys found; is the working directory the module?", used.size > 10)
        for (locale in locales) {
            val names = load(locale).keys
            val missing = used.filter { resource(it) !in names }
            assertEquals("Missing in $locale", emptyList<String>(), missing)
        }
    }

    @Test
    fun `every action has a label`() {
        val names = load("values").keys
        val missing = SyncAction.values().map { "action_${it.name.lowercase()}" }.filter { it !in names }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `all languages have the same keys`() {
        val base = load("values").keys
        for (locale in locales.drop(1)) {
            val other = load(locale).keys
            assertEquals("Only in default, missing in $locale", emptySet<String>(), base - other)
            assertEquals("Only in $locale", emptySet<String>(), other - base)
        }
    }

    @Test
    fun `placeholders are the same in every language`() {
        val placeholder = Regex("%\\d\\$[sd]")
        val base = load("values")
        for (locale in locales.drop(1)) {
            for ((name, entry) in load(locale)) {
                val expected = placeholder.findAll(base.getValue(name).text).map { it.value }.sorted().toList()
                val actual = placeholder.findAll(entry.text).map { it.value }.sorted().toList()
                assertEquals("Placeholders of $name in $locale", expected, actual)
            }
        }
    }

    @Test
    fun `no text is empty`() {
        for (locale in locales) {
            assertEquals(emptyList<String>(), load(locale).filterValues { it.text.isBlank() }.keys.toList())
        }
    }
}
