package pl.stapik.common.cloud

import java.io.PrintWriter
import java.io.StringWriter

class Redactor(secrets: Collection<String?>) {
    private val literals = secrets.filterNotNull().filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    fun redact(text: String): String {
        val withoutLiterals = literals.fold(text) { current, secret -> current.replace(secret, MASK) }
        return PATTERNS.fold(withoutLiterals) { current, (pattern, replacement) -> pattern.replace(current, replacement) }
    }

    fun describe(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSES)
            .joinToString(" <- ") { redact("${it.javaClass.simpleName}: ${it.message.orEmpty()}") }

    fun stackTrace(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return redact(writer.toString())
    }

    companion object {
        const val MASK = "***"
        private const val MAX_CAUSES = 8

        private val PATTERNS = listOf(
            Regex("""(?i)\b(x-api-key|api[-_]?key|authorization|passphrase|password)\b(["']?\s*[:=]\s*["']?)[^\s"',;&}]+""") to "\$1\$2$MASK",
            Regex("""(?i)(://)[^/\s:@]+:[^/\s@]+@""") to "\$1$MASK@",
        )

        fun of(config: CloudConfig): Redactor = Redactor(listOf(config.apiKey, config.passphrase))

        val None: Redactor = Redactor(emptyList())
    }
}
