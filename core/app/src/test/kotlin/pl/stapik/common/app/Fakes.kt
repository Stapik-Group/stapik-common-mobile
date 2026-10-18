package pl.stapik.common.app

import pl.stapik.common.document.BackupContents
import pl.stapik.common.document.BackupFormatException
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.sync.ConflictCopy
import pl.stapik.common.sync.DocumentReplacer
import pl.stapik.common.sync.DocumentStore
import pl.stapik.common.sync.SyncOutcome

class MemoryDocumentStore(var document: LocalDocument<DocumentEnvelope>? = null) : DocumentStore {
    override suspend fun load() = document
    override suspend fun save(document: LocalDocument<DocumentEnvelope>) {
        this.document = document
    }
    override suspend fun saveConflictCopy(copy: ConflictCopy) = Unit
}

class MemoryPort(var json: String, var failOnImport: Boolean = false) : MobileOnlyPort {
    val imported = mutableListOf<String>()
    override suspend fun exportJson() = json
    override suspend fun importJson(text: String) {
        if (failOnImport) throw IllegalArgumentException("bad store data")
        imported += text
        json = text
    }
}

/** Archives are kept in a table; the "bytes" are only the key. */
class TableArchiveFormat : ArchiveFormat {
    private val table = mutableMapOf<String, BackupContents>()
    override fun write(contents: BackupContents): ByteArray {
        val key = "archive-${table.size}"
        table[key] = contents
        return key.toByteArray()
    }
    override fun read(bytes: ByteArray): BackupContents =
        table[String(bytes)] ?: throw BackupFormatException("not an archive")
}

class RecordingReplacer(private val store: MemoryDocumentStore, var fail: Boolean = false) : DocumentReplacer {
    val replaced = mutableListOf<DocumentEnvelope>()
    override suspend fun replaceDocument(envelope: DocumentEnvelope): SyncOutcome {
        if (fail) throw IllegalStateException("disk full")
        replaced += envelope
        store.document = LocalDocument(envelope, dirty = true)
        return SyncOutcome.LocalOnly
    }
}
