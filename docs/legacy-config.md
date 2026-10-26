# Migracja konfiguracji połączenia ze starych aplikacji

Cel: po aktualizacji aplikacji użytkownik **nie łączy się ponownie**. `ConnectionConfigStore` przy pierwszym uruchomieniu bez własnego pliku pyta `LegacyConfigReader`; jeśli ten zwróci konfigurację, zapisuje ją w nowym formacie i dopiero potem czyści stare miejsce.

Odczyt jest wybaczający: brak wartości, zgubiony klucz Keystore lub uszkodzony tekst to „brak starej konfiguracji” (użytkownik łączy się jak dawniej), nigdy wyjątek.

## Formaty (sprawdzone w kodzie czterech aplikacji)

| Aplikacja | `LegacyFormat` | DataStore | Klucze | Alias Keystore |
|---|---|---|---|---|
| calendar-mobile | `CALENDAR_PLANNER` | `api_config` | `base_url_cipher`, `base_url_iv`, `api_key_cipher`, `api_key_iv` | `stapikcalendar_api_config_key` |
| planner-mobile | `CALENDAR_PLANNER` | `api_config` | jak wyżej | `stapikplanner_api_config_key` |
| media-mobile | `MEDIA_BUDGETING` | `connection_config` | `server_url_enc`, `api_key_enc` (`Base64(iv):Base64(szyfrogram)`) | `stapikmedia_config_key` |
| budgeting-mobile | `MEDIA_BUDGETING` | `connection_config` | jak wyżej | `stapikbudgeting_config_key` |

Szyfr w obu: AES-256-GCM, tag 128 bitów, bez danych dodatkowych. `ApiSchemaGuard` zapisuje wersję w osobnym DataStore `api_schema`; po migracji nie jest już potrzebny (jego rolę przejmuje `schemaVersion` w `ConnectionConfigStore`).

## Podłączenie w aplikacji

DataStore musi być w procesie tylko jeden na plik, więc adapter korzysta z delegata, który aplikacja już ma (`Context.apiConfigDataStore` lub `Context.configDataStore`), i ten stary delegat zostaje tylko dla adaptera:

```kotlin
// UWAGA: szkic, niesprawdzony kompilatorem (patrz plan, sekcja 6).
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

`load()` robi blokujące I/O, więc wołaj je poza głównym wątkiem.

## Zmiana `schemaVersion`

Podniesienie `schemaVersion` nie kasuje klucza ani frazy: zapisana konfiguracja przechodzi przez `ConfigMigration` (domyślnie `KeepAsIs`) i jest zapisywana od nowa pod bieżącą wersją. Aplikacja, która musi przemienić stare dane (na przykład inny adres), podaje własną migrację przez parametr `migration`. Dopiero migracja zwracająca `null` usuwa konfigurację (`ConfigLoad.SchemaReset`) i użytkownik łączy się od nowa. Konfiguracja o wersji nowszej niż `schemaVersion` nie jest ruszana (`ConfigLoad.NewerSchema`), tak samo nieczytelna (`ConfigLoad.Unreadable`).
