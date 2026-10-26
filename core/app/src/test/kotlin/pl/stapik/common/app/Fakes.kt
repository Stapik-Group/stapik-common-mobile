package pl.stapik.common.app

import pl.stapik.common.cloud.SealedBackupException
import pl.stapik.common.document.BackupContents
import pl.stapik.common.document.BackupFormatException
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.LocalDocument
import pl.stapik.common.sync.ConflictCopy
import pl.stapik.common.sync.DocumentReplacer
import pl.stapik.common.sync.DocumentStore
import pl.stapik.common.sync.SyncOutcome
import pl.stapik.common.sync.UnreadableKind

class MemoryDocumentStore(var document: LocalDocument<DocumentEnvelope>? = null) : DocumentStore {
    override suspend fun load() = document
    override suspend fun save(document: LocalDocument<DocumentEnvelope>) {
        this.document = document
    }
    override suspend fun saveConflictCopy(copy: ConflictCopy) = Unit
    override suspend fun discard() {
        document = null
    }
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

/** Archives are kept in a table; the "bytes" are only the key. A passphrase makes the key unreadable without it. */
class TableArchiveFormat : ArchiveFormat {
    private val table = mutableMapOf<String, Pair<BackupContents, String?>>()
    override fun write(contents: BackupContents, passphrase: String?): ByteArray {
        val key = "archive-${table.size}"
        table[key] = contents to passphrase?.takeIf { it.isNotEmpty() }
        return key.toByteArray()
    }
    override fun read(bytes: ByteArray, passphrase: String?): BackupContents {
        val (contents, required) = table[String(bytes)] ?: throw BackupFormatException("not an archive")
        if (required != null) {
            if (passphrase.isNullOrEmpty()) throw SealedBackupException(UnreadableKind.PASSPHRASE_REQUIRED, "needs a passphrase")
            if (passphrase != required) throw SealedBackupException(UnreadableKind.DECRYPTION_FAILED, "wrong passphrase")
        }
        return contents
    }
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
