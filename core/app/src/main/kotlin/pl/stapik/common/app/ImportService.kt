package pl.stapik.common.app

import pl.stapik.common.document.BackupContents
import pl.stapik.common.document.BackupFormatException
import pl.stapik.common.document.SafetyCopies
import pl.stapik.common.model.DocumentDecodeException
import pl.stapik.common.model.DocumentEnvelope
import pl.stapik.common.model.EnvelopeFormatException
import pl.stapik.common.model.SchemaGate
import pl.stapik.common.sync.DocumentReplacer
import pl.stapik.common.sync.SyncOutcome

/** Why an import was refused before anything was changed. */
sealed interface ImportRejection {
    /** The file is not a valid backup archive. */
    data class NotABackup(val reason: String) : ImportRejection

    /** The backup comes from another app. */
    data class WrongApp(val found: String) : ImportRejection

    /** The document in the backup does not fit this app's model. */
    data class DocumentInvalid(val reason: String) : ImportRejection

    /** The document in the backup was written with a newer schema than this app understands. */
    data class NewerSchema(val found: Int, val supported: Int) : ImportRejection

    /** The backup holds a device-only store this app does not have. */
    data class UnknownStore(val name: String) : ImportRejection
}

sealed interface ImportResult {
    /**
     * The data was replaced. [safetyCopy] is the state from before the import (see
     * [SafetyCopies]); [sync] is how the imported document then synchronised (null when the backup had no document).
     */
    data class Imported(val safetyCopy: String, val sync: SyncOutcome?) : ImportResult

    data class Rejected(val reason: ImportRejection) : ImportResult

    /**
     * Applying failed half way. Device-only stores changed so far were put back; [safetyCopy]
     * still holds the original state in any case.
     */
    data class Failed(val safetyCopy: String, val cause: Throwable) : ImportResult
}

/**
 * Replaces the app's data with the contents of a backup, safely (plan 4.6):
 *
 * 1. everything is checked first - app, document, schema, store names - and a refusal changes nothing;
 * 2. the current state is saved as a safety copy before the first write;
 * 3. device-only stores are written, then the document last (it also triggers a sync), and if
 *    anything fails the stores already written are put back.
 *
 * A backup without a document leaves the current document alone.
 *
 * @param validate checks that a document fits the app's model; throw [DocumentDecodeException] if not.
 */
class ImportService(
    private val appId: String,
    private val backup: AppBackup,
    private val archive: ArchiveFormat,
    private val safety: SafetyCopies,
    private val documents: DocumentReplacer,
    private val mobileOnly: Map<String, MobileOnlyPort> = emptyMap(),
    private val schemaGate: SchemaGate? = null,
    private val validate: (DocumentEnvelope) -> Unit = {},
) {
    /** [bytes] is the content of an archive file chosen by the user. */
    suspend fun importArchive(bytes: ByteArray): ImportResult {
        val contents = try {
            archive.read(bytes)
        } catch (e: BackupFormatException) {
            return ImportResult.Rejected(ImportRejection.NotABackup(e.message.orEmpty()))
        }
        return import(contents)
    }

    /** Returns the original state from a safety copy; the state it replaces becomes a safety copy too. */
    suspend fun restoreSafetyCopy(name: String): ImportResult = importArchive(safety.read(name))

    suspend fun import(contents: BackupContents): ImportResult {
        val envelope = when (val checked = check(contents)) {
            is Checked.Rejected -> return ImportResult.Rejected(checked.reason)
            is Checked.Ok -> checked.document
        }

        val before = backup.snapshot()
        val copy = safety.save(archive.write(before))

        val applied = mutableListOf<String>()
        return try {
            for ((name, text) in contents.mobileOnly) {
                checkNotNull(mobileOnly[name]).importJson(text)
                applied += name
            }
            val sync = if (envelope != null) documents.replaceDocument(envelope) else null
            ImportResult.Imported(copy.name, sync)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            rollback(applied, before)
            ImportResult.Failed(copy.name, e)
        }
    }

    private sealed interface Checked {
        class Ok(val document: DocumentEnvelope?) : Checked
        class Rejected(val reason: ImportRejection) : Checked
    }

    private fun check(contents: BackupContents): Checked {
        if (contents.appId != appId) return Checked.Rejected(ImportRejection.WrongApp(contents.appId))
        contents.mobileOnly.keys.firstOrNull { it !in mobileOnly }
            ?.let { return Checked.Rejected(ImportRejection.UnknownStore(it)) }
        val text = contents.document ?: return Checked.Ok(null)
        val envelope = try {
            DocumentEnvelope.parse(text)
        } catch (e: EnvelopeFormatException) {
            return Checked.Rejected(ImportRejection.DocumentInvalid(e.message.orEmpty()))
        }
        if (schemaGate != null) {
            val newer = schemaGate.newerVersionOf(envelope)
            if (newer != null) return Checked.Rejected(ImportRejection.NewerSchema(newer, schemaGate.supported))
        }
        try {
            validate(envelope)
        } catch (e: DocumentDecodeException) {
            return Checked.Rejected(ImportRejection.DocumentInvalid(e.message.orEmpty()))
        }
        return Checked.Ok(envelope)
    }

    private suspend fun rollback(applied: List<String>, before: BackupContents) {
        for (name in applied) {
            val old = before.mobileOnly[name] ?: continue
            try {
                checkNotNull(mobileOnly[name]).importJson(old)
            } catch (_: Exception) {
                // The safety copy still has it.
            }
        }
    }
}
