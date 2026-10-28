# Migrating the connection settings from the old apps

Goal: after an app update the user **does not connect again**. On the first start without its own file, `ConnectionConfigStore` asks `LegacyConfigReader`; if that returns a configuration, the store saves it in the new format and only then clears the old place.

Reading is forgiving: a missing value, a lost Keystore key or damaged text mean "no old configuration" (the user connects as before), never an exception.

## Formats (checked in the code of four apps)

| App | `LegacyFormat` | DataStore | Keys | Keystore alias |
|---|---|---|---|---|
| calendar-mobile | `CALENDAR_PLANNER` | `api_config` | `base_url_cipher`, `base_url_iv`, `api_key_cipher`, `api_key_iv` | `stapikcalendar_api_config_key` |
| planner-mobile | `CALENDAR_PLANNER` | `api_config` | as above | `stapikplanner_api_config_key` |
| media-mobile | `MEDIA_BUDGETING` | `connection_config` | `server_url_enc`, `api_key_enc` (`Base64(iv):Base64(ciphertext)`) | `stapikmedia_config_key` |
| budgeting-mobile | `MEDIA_BUDGETING` | `connection_config` | as above | `stapikbudgeting_config_key` |

The cipher in both: AES-256-GCM, 128-bit tag, no additional data. `ApiSchemaGuard` stores the version in a separate DataStore `api_schema`; after the migration it is no longer needed (its role is taken over by `schemaVersion` in `ConnectionConfigStore`).

## Wiring in an app

There can be only one DataStore per file in a process, so the adapter uses the delegate the app already has (`Context.apiConfigDataStore` or `Context.configDataStore`), and that old delegate stays only for the adapter:

```kotlin
// NOTE: a sketch, not checked by the compiler (see the plan, section 6).
class DataStoreLegacySource(private val dataStore: DataStore<Preferences>) : LegacyPrefsSource {
    override fun readAll(): Map<String, String> = runBlocking {
        dataStore.data.first().asMap().entries
            .mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }
            .toMap()
    }

    override fun clear() = runBlocking { dataStore.edit { it.clear() }; Unit }
}

val legacy = LegacyPrefsConfigReader(
    source = DataStoreLegacySource(context.configDataStore),
    format = LegacyFormat.MEDIA_BUDGETING,
    decryptor = AesGcmLegacyDecryptor { KeystoreKeys.find("stapikmedia_config_key") },
)
val store = ConnectionConfigStore(directory, KeystoreCiphers.create("stapikmedia_data_key"), schemaVersion = 1, legacy = legacy)
```

`load()` does blocking I/O, so call it off the main thread.

## Changing `schemaVersion`

Raising `schemaVersion` does not delete the key or the passphrase: the saved configuration goes through `ConfigMigration` (`KeepAsIs` by default) and is saved again under the current version. An app that has to convert the old data (for example a different address) passes its own migration through the `migration` parameter. Only a migration that returns `null` deletes the configuration (`ConfigLoad.SchemaReset`) and the user connects from scratch. A configuration with a version newer than `schemaVersion` is not touched (`ConfigLoad.NewerSchema`), and neither is an unreadable one (`ConfigLoad.Unreadable`).
