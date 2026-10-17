package pl.stapik.common.cloud.layers

import pl.stapik.common.model.SlotKey
import pl.stapik.common.sync.CloudError
import pl.stapik.common.sync.CloudException
import pl.stapik.common.sync.UnreadableKind

/**
 * What happens to a document's text on its way to and from the cloud, in the order of the desktop:
 * on write compression, then encryption; on read decryption, then decompression.
 *
 * Text that is neither encrypted nor compressed passes through unchanged. Plain data found while a
 * passphrase is set is accepted as it is (it was saved before encryption was turned on) and becomes
 * encrypted on the next write; this is the desktop's behaviour too.
 */
internal class ContentLayers(
    slot: SlotKey,
    passphrase: String?,
    private val gzipThresholdBytes: Int = GzipLayer.DEFAULT_THRESHOLD_BYTES,
    random: ((Int) -> ByteArray)? = null,
) {
    private val e2e: E2eBlob? = passphrase?.takeIf { it.isNotEmpty() }?.let { phrase ->
        if (random == null) E2eBlob(phrase, slot.value) else E2eBlob(phrase, slot.value, random)
    }

    fun seal(text: String): String {
        val compressed = GzipLayer.seal(text, gzipThresholdBytes)
        return e2e?.seal(compressed, DOCUMENT_CONTEXT) ?: compressed
    }

    fun open(content: String): String {
        if (content.isEmpty()) return EMPTY_DOCUMENT
        var text = content
        val blob = E2eBlob.parse(text)
        if (blob != null) {
            val cipher = e2e ?: throw CloudException(
                CloudError.Unreadable("The cloud data is end-to-end encrypted; enter the passphrase", UnreadableKind.PASSPHRASE_REQUIRED),
            )
            text = cipher.open(blob, DOCUMENT_CONTEXT)
        }
        return GzipLayer.open(text)
    }

    private companion object {
        const val DOCUMENT_CONTEXT = "document"
        const val EMPTY_DOCUMENT = "{}"
    }
}
