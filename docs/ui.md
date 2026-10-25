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
`controller.edit` saves and syncs at once. For fast input (typing, sliders) use `controller.editDebounced`: it saves and shows the edit immediately and syncs two seconds after the last one, so a burst costs one round trip. It needs the `scope` constructor argument (the ViewModel's scope); without it it behaves like `edit`. Call `controller.flushPendingSync()` when the screen stops (`onStop`) so the last edit does not wait; if the scope is cancelled first, the edit stays saved on the device and goes out with the next sync.
When `SyncAction.CHOOSE_SOURCE` is offered, show `FirstConnectionDialog` and call `controller.sync(choice)`.
Where to keep the chosen theme is the app's decision (a `MobileOnlyStore` fits).
