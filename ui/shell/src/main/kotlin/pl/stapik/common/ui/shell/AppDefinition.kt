package pl.stapik.common.ui.shell

/**
 * What a Stapik app tells the shared screens about itself.
 *
 * @property appId the name used in backups and by the cloud (`calendar`, `planner`, ...).
 * @property name the name shown in the About screen.
 * @property versionName the version shown in the About screen (the app's `BuildConfig.VERSION_NAME`).
 * @property author shown in the About screen.
 * @property websiteUrl opened by the About screen; null hides the link.
 */
data class AppDefinition(
    val appId: String,
    val name: String,
    val versionName: String,
    val author: String,
    val websiteUrl: String? = null,
)
