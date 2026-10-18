package pl.stapik.common.i18n

import android.content.Context

/**
 * Resolves message keys produced by the presentation layer (`sync.offline.network`) to text.
 *
 * A key maps to the string resource of the same name with dots turned into underscores
 * (`sync_offline_network`). Placeholders are positional (`%1$d`) so translations can reorder them.
 * An unknown key is returned as it is, so a missing translation shows up on screen instead of crashing.
 */
object Messages {
    fun resourceName(key: String): String = key.replace('.', '_')

    fun text(context: Context, key: String, vararg args: Any): String {
        val id = context.resources.getIdentifier(resourceName(key), "string", context.packageName)
        if (id == 0) return key
        return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }
}
