# Shared UI

| Module          | Contents |
|-----------------|----------|
| `ui:theme`      | `StapikTheme(themeId) { }`, `StapikTheme.colors` (colours Material has no slot for) |
| `ui:components` | `StapikButton`, `StapikTextField`, `StapikScreen`/`StapikScreenHeader`, `StapikOptionRow`, `StapikBanner`, `StapikDialog`, `Modifier.themedSurface`/`bevel` |
| `ui:sync`       | `SyncStatusBanner(SyncUiState)`, `FirstConnectionDialog`, `LoadErrorMessage` |
| `ui:connect`    | `ConnectRoute(ConnectController)`, stateless `ConnectScreen` |
| `ui:shell`      | `AppDefinition`, `SettingsFlow` (connect, theme, about), `ThemeScreen`, `AboutScreen` |

The components take already translated texts; the screens read them from `core:i18n`.

## An app's root

```kotlin
val app = AppDefinition("calendar", "Stapik Calendar", BuildConfig.VERSION_NAME, "Stapik", "https://github.com/Stapik-Group")

StapikTheme(themeId) {
    val doc by controller.document.collectAsState()      // DocumentController
    val sync by controller.sync.collectAsState()
    Column {
        SyncStatusBanner(sync, onAction = { action -> /* RETRY -> controller.sync(), RECONNECT -> open settings, DISMISS -> controller.dismissResolvedNotice(), ... */ })
        when (val state = doc) {
            DocumentUiState.Loading -> CircularProgressIndicator()
            is DocumentUiState.Error -> LoadErrorMessage(state.kind)
            is DocumentUiState.Success -> MyScreen(state.value)
        }
    }
    if (settingsOpen) SettingsFlow(app, themeId, onThemeChange, connectController, connected, onConnected, onDisconnected, onClose)
}
```

`SyncUiState.messageKey` and the action labels (`action.retry`, ...) are resolved with `Messages.text`.
A conflict resolved automatically (both sides changed, the cloud won and the local version was saved as a conflict copy) is announced with `SyncBadge.CONFLICT_RESOLVED` and stays on the indicator, also through later edits and syncs, until the app calls `controller.dismissResolvedNotice()` (action `SyncAction.DISMISS`). The notice lasts as long as the controller does. The copy itself is kept by `FileDocumentStore` (`listConflictCopies()`); the library has no screen for it yet.
When `SyncAction.CHOOSE_SOURCE` is offered, show `FirstConnectionDialog` and call `controller.sync(choice)`.
Where to keep the chosen theme is the app's decision (a `MobileOnlyStore` fits).
