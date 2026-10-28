# The client's contract with Stapik Cloud

A description of what `:core:cloud` does, written down once so that the apps and the library do not drift away from the server and the desktop (`stapik-common`). If the code and this document differ, the code wins; the document then has to be corrected.

Markers: **[code]** - implemented and covered by tests, **[assumption]** - taken without confirmation in the server code.

## Addresses and headers

- Base: the address from the configuration without whitespace at the edges and without trailing `/`, then `/api/v1/`. **[code]** (`CloudConfig.baseUrl`)
- `GET {base}me` - who the key belongs to. The response has the fields `scope` and `extensionId`. The gateway remembers it for 5 minutes (monotonic clock, not the device time) and forgets it after a `401` or `403` from any request, so synchronisation does not ask for it before every change. **[code]**
- `GET {base}documents/{slot}` - the document, `404` when the slot does not exist. **[code]**
- `PUT {base}documents/{slot}` - write. **[code]**
- Every request carries `x-api-key`. The key and the passphrase do not reach logs or exports, also through `toString()` of `CloudConfig` and `ConnectFormState`. **[code]**
- `http://` is allowed, but the UI warns that the key travels unencrypted. **[code]** (`ApiUrlStatus`)
- The response body is read up to a limit of 16 MiB (`HttpCloudGateway.DEFAULT_MAX_RESPONSE_BYTES`, also after HTTP compression is removed). A longer response aborts the read and ends with `Unreadable`. The desktop has the same mechanism (`maxResponseBytes` of a request), but does not set it for documents. **[code]**
- HTTP redirects are not followed, also in a client passed to `OkHttpTransport`: when the host changes, OkHttp removes only `Authorization`, so `x-api-key` would go to the host named in `Location`. **[code]**

## Document

Response: `slotKey`, `content` (text containing the JSON of the envelope), `contentHash`, `updatedAt` (ISO-8601). **[code]**

Envelope (`content`): an object with `lastUpdate` and `payload`; unknown fields at the top level survive a write. **[code]** (`DocumentEnvelope`)

Write: the body `{"content": "<text>"}`; when the client knows the version, additionally `"clientLastKnownUpdate": "<updatedAt>"` (for older servers). **[code]**

### Unknown fields on write

The codec (`DocumentCodec`) reads the document into the app's typed model, so what the model does not describe survives a write only partly:

| Where the unknown field is | After a write from this version of the library |
|---|---|
| the top level of the envelope (next to `lastUpdate` and `payload`) | stays |
| the top level of `payload`, together with everything nested in it | stays in full |
| inside a known structure, for example an extra key in a list item | **disappears** |

What protects against the loss is not the codec but `SchemaGate`: a document written in a newer schema than the app knows is shown but not edited (`SyncOutcome.NewerSchema`). So the protection works only if the desktop raises the schema number on every change to the structure of known objects. Adding a field to a known object without raising the schema number ends with the field being lost on the first edit on the phone. **[code; desktop behaviour: assumption]**

## Conflicts

A server >= 1.2.0 compares the `If-Match` header with the document's ETag (a quoted SHA-256 of the content) and answers `409` when it is missing or stale. **[client side: code; server behaviour: assumption from the desktop documentation]**

`updatedAt` and the baseline are compared as instants (`CloudTimestamp.sameInstant`), not as text: `Z`, `+00:00` and a different number of fraction digits are the same time and give neither a false conflict nor an extra GET. Differences below a microsecond do not count as a change either: in reply to a `PUT` the server returns `updatedAt` with nanosecond precision, while the database stores and returns microseconds, so the next `GET` returned a different value and every second write ended with a false conflict. Text that is not a time is compared as text. **[code]**

The client remembers the ETag of the last read or write of every slot:

1. It knows an `updatedAt` equal to the baseline it edited from -> it uses the remembered ETag.
2. It does not know it -> it reads the document once. If the server's `updatedAt` differs from the baseline, that is a conflict and **no PUT is sent**.
3. `409` on a PUT -> conflict (the engine repeats up to 3 times, then the server wins and the local version stays as a conflict copy).
4. No document with a non-empty baseline -> conflict.

A clock does not decide who wins. When both sides changed (the server relative to the baseline, and the local document), the cloud version stays and the local one goes to a conflict copy; the result is `ServerWon`. The local document's `modifiedAt` is only a hint for the UI. The desktop decides differently: it compares the `lastUpdate` of both envelopes (the time of the edit, not the server time), so on the desktop side the decision still depends on the clocks of the devices.

Whether the server accepts `If-Match` and `clientLastKnownUpdate` together: **[assumption]**, checked only against a mock server.

## Limits and errors

| Response | Meaning in the client |
|---|---|
| `401` | `Unauthorized` - the key was rejected or has expired |
| `403` | `Forbidden` - the key lacks permission |
| `404` | no document (read only) |
| `409` | `Conflict` |
| `429` | `Retry-After` (seconds or an HTTP date) <= 30 s: the client waits and retries once; longer: `RateLimited(retryAfter)`, and the retry is left to the background. An HTTP date is counted against the device clock, a negative or unrecognised value means "unknown" |
| `3xx` | `ServerError(code)` - the redirect is not followed; the address in the settings has to point straight at the API |
| other >= 400 | `ServerError(code)` |
| no response | `Network` |

The request, response parsing, compression and encryption (Argon2id) run on the gateway's dispatcher (`Dispatchers.IO` by default), not on the caller's thread. An `OutOfMemoryError` in such a step ends with `Unreadable`, not with the process being killed. **[code]**

Server limit: 600 requests per minute (`X-RateLimit-*` headers). `X-Api-Key-Expires-At` (ISO-8601) is passed to `onApiKeyExpiry`, so the UI can warn about the key expiring. **[code]**

Key scope: `READ_WRITE` allows writing; every other value is treated as read only (`READ_ONLY` is an **[assumption]**).

## Content layers

Write: compression first, then (when a passphrase is set) encryption. Read: the other way round. Data without any layer is always accepted, also when a passphrase is set (it was saved before the passphrase was set; the next write encrypts it).

### `stapik-gzip` compression **[code]**

`{"format":"stapik-gzip","version":1,"data":"<base64 of a gzip stream>"}`

- from 32 KiB of UTF-8 text; smaller texts go as they are,
- if the base64 is not shorter than the text, the text is sent,
- the threshold has to be the same as on the desktop, because older desktops do not read compressed documents,
- reading has a limit of 32 MiB after decompression (desktop: 256 MiB, so a document larger than 32 MiB created on the desktop will not open on the phone, it ends with `Unreadable`).

### `stapik-e2e` encryption **[code; compatibility with libsodium checked on test vectors]**

`{"format":"stapik-e2e","version":1,"salt":"..","nonce":"..","ciphertext":".."}` (standard base64 with padding)

- key: Argon2id v1.3, 2 passes, 64 MiB, 1 thread, 16 B salt, 32 B output (`crypto_pwhash` "interactive"),
- cipher: XChaCha20-Poly1305-IETF, 24 B nonce, 16 B tag appended to the ciphertext,
- additional data (AAD): `stapik-e2e/1/<slot>/document`,
- a new salt per instance, keys cached per salt (at most 8),
- implementation: BouncyCastle (pure Java), not native libsodium; HChaCha20 is custom, because BouncyCastle has no XChaCha.

Read errors (`CloudError.Unreadable`) have a kind: no passphrase, wrong passphrase or modified data, newer format version, other. With any of them nothing is written.

## Logs and error reports

The key and the passphrase do not reach the library's error messages (`CloudError`, `CloudException`), nor `toString()` of the configuration, the form and the HTTP request. An app that logs its own exceptions or sends error reports passes the text through `Redactor`:

```kotlin
val redactor = Redactor.of(config)
log.e(redactor.describe(error))
report.attach(redactor.stackTrace(error))
```

`Redactor` replaces with `***` the known secrets (the key, the passphrase) anywhere in the text, and besides that the values of the fields `x-api-key`, `authorization`, `apiKey`, `passphrase`, `password` and the credentials in an address (`https://user:password@host`). `describe` walks the chain of causes, `stackTrace` masks the whole stack trace. The content of the user's document is not recognised: do not put it in logs. **[code]**

## Export copies

An export file protected with a passphrase is a `stapik-e2e` blob with the AAD `stapik-e2e/1/backup/archive`, whose plaintext is the Base64 of a ZIP archive. This is a mobile-only format; the desktop does not read it.
