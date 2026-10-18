# Kontrakt klienta ze Stapik Cloud

Opis tego, co robi `:core:cloud`, spisany raz, żeby aplikacje i biblioteka nie rozjeżdżały się z serwerem i desktopem (`stapik-common`). Jeśli kod i ten dokument się różnią, wygrywa kod; dokument trzeba wtedy poprawić.

Oznaczenia: **[kod]** – zaimplementowane i objęte testami, **[założenie]** – przyjęte bez potwierdzenia w kodzie serwera.

## Adresy i nagłówki

- Baza: adres z konfiguracji bez białych znaków na brzegach i bez końcowych `/`, potem `/api/v1/`. **[kod]** (`CloudConfig.baseUrl`)
- `GET {baza}me` – kim jest klucz. Odpowiedź ma pola `scope` i `extensionId`. **[kod]**
- `GET {baza}documents/{slot}` – dokument, `404` gdy slotu nie ma. **[kod]**
- `PUT {baza}documents/{slot}` – zapis. **[kod]**
- Każde żądanie niesie `x-api-key`. Klucz nie trafia do logów ani do eksportu. **[kod]**
- `http://` jest dozwolone, ale UI ostrzega, że klucz idzie nieszyfrowany. **[kod]** (`ApiUrlStatus`)

## Dokument

Odpowiedź: `slotKey`, `content` (tekst zawierający JSON koperty), `contentHash`, `updatedAt` (ISO-8601). **[kod]**

Koperta (`content`): obiekt z `lastUpdate` i `payload`; nieznane pola najwyższego poziomu przeżywają zapis. **[kod]** (`DocumentEnvelope`)

Zapis: ciało `{"content": "<tekst>"}`; gdy klient zna wersję, dodatkowo `"clientLastKnownUpdate": "<updatedAt>"` (dla starszych serwerów). **[kod]**

## Konflikty

Serwer ≥ 1.2.0 porównuje nagłówek `If-Match` z ETagiem dokumentu (cudzysłowiony SHA-256 treści) i odpowiada `409` przy braku lub nieaktualnym. **[kod po stronie klienta; zachowanie serwera: założenie z dokumentacji desktopu]**

Klient pamięta ETag z ostatniego odczytu lub zapisu każdego slotu:

1. Zna `updatedAt` równe bazie, od której edytował → używa zapamiętanego ETagu.
2. Nie zna → czyta dokument raz. Jeśli `updatedAt` serwera różni się od bazy, to jest konflikt i **żadne PUT nie jest wysyłane**.
3. `409` na PUT → konflikt (silnik powtarza do 3 razy, potem wygrywa serwer, a lokalna wersja zostaje jako kopia konfliktu).
4. Brak dokumentu przy niepustej bazie → konflikt.

Czy serwer przyjmuje jednocześnie `If-Match` i `clientLastKnownUpdate`: **[założenie]**, sprawdzone tylko na atrapie serwera.

## Limity i błędy

| Odpowiedź | Znaczenie w kliencie |
|---|---|
| `401` | `Unauthorized` – klucz odrzucony lub wygasł |
| `403` | `Forbidden` – klucz bez uprawnień |
| `404` | brak dokumentu (tylko odczyt) |
| `409` | `Conflict` |
| `429` | `Retry-After` ≤ 30 s: klient czeka i ponawia raz; dłużej: `RateLimited(retryAfter)` |
| inne ≥ 400 | `ServerError(kod)` |
| brak odpowiedzi | `Network` |

Limit serwera: 600 żądań na minutę (nagłówki `X-RateLimit-*`). `X-Api-Key-Expires-At` (ISO-8601) jest przekazywane do `onApiKeyExpiry`, żeby UI mogło ostrzec o wygasaniu klucza. **[kod]**

Zakres klucza: `READ_WRITE` pozwala zapisywać; każda inna wartość jest traktowana jak tylko do odczytu (`READ_ONLY` to **[założenie]**).

## Warstwy treści

Zapis: najpierw kompresja, potem (przy ustawionej frazie) szyfrowanie. Odczyt: odwrotnie. Dane bez żadnej warstwy są zawsze akceptowane, także przy ustawionej frazie (zapisane przed jej ustawieniem; następny zapis je zaszyfruje).

### Kompresja `stapik-gzip` **[kod]**

`{"format":"stapik-gzip","version":1,"data":"<base64 strumienia gzip>"}`

- od 32 KiB tekstu w UTF-8; mniejsze lecą bez zmian,
- jeśli base64 nie jest krótszy od tekstu, wysyłany jest tekst,
- próg musi być taki jak na desktopie, bo starsze desktopy nie czytają skompresowanych dokumentów,
- odczyt ma limit 256 MiB po rozpakowaniu.

### Szyfrowanie `stapik-e2e` **[kod; zgodność z libsodium sprawdzona na wektorach]**

`{"format":"stapik-e2e","version":1,"salt":"..","nonce":"..","ciphertext":".."}` (base64 standardowe z dopełnieniem)

- klucz: Argon2id v1.3, 2 przebiegi, 64 MiB, 1 wątek, sól 16 B, wynik 32 B (`crypto_pwhash` „interactive”),
- szyfr: XChaCha20-Poly1305-IETF, nonce 24 B, tag 16 B dołączony do szyfrogramu,
- dane dodatkowe (AAD): `stapik-e2e/1/<slot>/document`,
- nowa sól na instancję, klucze buforowane per sól (maks. 8),
- implementacja: BouncyCastle (czysta Java), nie natywny libsodium; HChaCha20 jest własny, bo BouncyCastle nie ma XChaCha.

Błędy odczytu (`CloudError.Unreadable`) mają rodzaj: brak frazy, zła fraza lub zmodyfikowane dane, nowsza wersja formatu, inny. Przy każdym z nich niczego się nie zapisuje.

## Kopie eksportu

Plik eksportu chroniony frazą to blob `stapik-e2e` z AAD `stapik-e2e/1/backup/archive`, którego tekstem jawnym jest Base64 archiwum ZIP. To format tylko mobilny; desktop go nie czyta.
