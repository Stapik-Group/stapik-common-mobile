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
            is DocumentUiState.Error -> LoadErrorMessage(state.kind, onDiscardLocal = { scope.launch { controller.discardLocalAndResync() } })
            is DocumentUiState.Success -> MyScreen(state.value)
        }
    }
    if (settingsOpen) SettingsFlow(app, themeId, onThemeChange, connectController, connected, onConnected, onDisconnected, onClose)
}
```

`SyncUiState.messageKey` and the action labels (`action.retry`, ...) are resolved with `Messages.text`.
A conflict resolved automatically (both sides changed, the cloud won and the local version was saved as a conflict copy) is announced with `SyncBadge.CONFLICT_RESOLVED` and stays on the indicator, also through later edits and syncs, until the app calls `controller.dismissResolvedNotice()` (action `SyncAction.DISMISS`). The notice lasts as long as the controller does. The copy itself is kept by `FileDocumentStore` (`listConflictCopies()`); the library has no screen for it yet. Per slot the store keeps the newest 20 copies (`maxConflictCopies`) and drops those older than 90 days (`conflictCopyMaxAge`), but never the newest 3.
`controller.edit` saves and syncs at once. For fast input (typing, sliders) use `controller.editDebounced`: it saves and shows the edit immediately and syncs two seconds after the last one, so a burst costs one round trip. It needs the `scope` constructor argument (the ViewModel's scope); without it it behaves like `edit`. Call `controller.flushPendingSync()` when the screen stops (`onStop`) so the last edit does not wait; if the scope is cancelled first, the edit stays saved on the device and goes out with the next sync.
When a sync ends offline (no network, server error, rate limit), `DocumentController` calls `retry.schedule()` of the `SyncRetry` passed to its constructor; any other outcome calls `retry.cancel()`. `SyncRetryPolicy` says which outcomes are worth another try (only `Offline`). The default `SyncRetry.None` does nothing; `:core:work` has the WorkManager one.

### Retry in the background (`:core:work`)

```kotlin
class AppSyncWorker(context: Context, params: WorkerParameters) : SyncWorker(context, params) {
    override suspend fun runSync(): SyncOutcome = (applicationContext as MyApp).syncEngine.sync()
}

DocumentController(engine, scope, retry = WorkManagerSyncRetry(context, AppSyncWorker::class.java)) { connected() }
```

The work waits for a network connection, backs off exponentially from 30 s and gives up after 5 attempts (`SyncWorker.maxAttempts`); the edit stays saved on the device and goes out with the next sync. The worker must use the engine, not the controller: a controller that finishes with a good sync cancels the retry, including the one that is running it. The app has to make its engine available without a screen (for example from `Application`), because a worker may run when no activity exists.

Local data that cannot be read (`LoadErrorKind.CORRUPTED`, or `UNDECRYPTABLE` when the device key is gone) is not repaired. `LoadErrorMessage(kind, onDiscardLocal = ...)` offers, after a confirmation, `controller.discardLocalAndResync()`: it deletes the unreadable file and its backups (conflict copies stay) and takes the document from the cloud again. Changes that never reached the cloud are lost; without a cloud the document starts empty. The connection settings are encrypted with their own key and are usually lost together with the data, so the user connects again first (`ConfigLoad.Unreadable`) and then discards. Android Auto Backup restores app files but never Keystore keys, so a restored install ends exactly here; exclude the library's data directory from Auto Backup (`android:fullBackupContent` / `dataExtractionRules`) to avoid it.
When `SyncAction.CHOOSE_SOURCE` is offered, show `FirstConnectionDialog` and call `controller.sync(choice)`.
Where to keep the chosen theme is the app's decision (a `MobileOnlyStore` fits).
