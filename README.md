# Stapik Common Mobile

The code that the Stapik Android apps share: local-first document storage, synchronisation with
Stapik Cloud, the connection settings, and the screens around them. It is the mobile counterpart of
[`stapik-common`](https://github.com/Stapik-Group/stapik-common) (C++, desktop) and speaks the same
cloud format, so a document written on a phone opens on the desktop and the other way round.

## What it gives an app

- **Local-first documents.** Every edit is saved on the device first and always succeeds; sending it
  to the cloud is a separate, best-effort step with retries in the background.
- **Synchronisation with Stapik Cloud.** Conflicts are decided by the document's baseline, not by
  clocks; the losing version is kept as a conflict copy and the user is told.
- **The desktop's wire format.** Gzip above a size threshold and end-to-end encryption
  (Argon2id and XChaCha20-Poly1305), compatible with libsodium.
- **Safe storage.** Atomic writes, rotating backups, recovery, optional encryption at rest with the
  Android Keystore, a way back when the data cannot be read, and backup and restore of the whole
  app's data.
- **The shared screens.** Connect form, sync indicator, themes, about, settings, with texts in
  English, Polish and German.

## Modules

The logic is plain JVM wherever it can be, so it is unit-tested without a device. Only the modules
that need Android (the Keystore, resources, Compose, WorkManager) are Android libraries.

| Module              | Kind    | Contents                                                              |
|---------------------|---------|-----------------------------------------------------------------------|
| `core:model`        | JVM     | Document envelope and codec, schema gate, cloud scope, timestamps     |
| `core:sync`         | JVM     | Sync engine and planner, the cloud gateway port, retry policy         |
| `core:document`     | JVM     | File-based document store, backups, mobile-only store, backup archive |
| `core:cloud`        | JVM     | HTTP client of Stapik Cloud, gzip and E2E layers, `Redactor` for logs |
| `core:settings`     | JVM     | The saved connection, stored encrypted, with migration of old configs |
| `core:app`          | JVM     | Backup, export and import of the user's data                          |
| `core:presentation` | JVM     | What the screens show: document state, sync indicator, connect form   |
| `core:theme`        | Android | Colours of the Stapik themes                                          |
| `core:i18n`         | Android | Texts shared by the apps (en, pl, de)                                 |
| `core:crypto`       | Android | Android Keystore keys and ciphers                                     |
| `core:work`         | Android | Background retry of a failed sync on WorkManager                      |
| `ui:theme`          | Android | `StapikTheme`                                                         |
| `ui:components`     | Android | Buttons, text fields, dialogs, banners                                |
| `ui:sync`           | Android | Sync status banner, first-connection dialog, load error message       |
| `ui:connect`        | Android | Connect screen                                                        |
| `ui:shell`          | Android | `AppDefinition`, settings flow, theme and about screens               |
| `sample-app`        | app     | Runs a self-test of the Android-only parts on a device                |

## Using it in an app

The library is a git submodule of the app, pinned to a tag, and Gradle builds it with the app. An app
depends on a tag, never on a branch.

```
git submodule add <url of stapik-common-mobile> libs/stapik-common-mobile
git -C libs/stapik-common-mobile checkout vX.Y.Z
```

```kotlin
val commonDir = providers.gradleProperty("stapikCommonMobileDir").getOrElse("libs/stapik-common-mobile")
apply(from = "$commonDir/stapik-common-mobile.settings.gradle.kts")
```

```kotlin
implementation("pl.stapik.common:ui-shell")
```

The coordinate is `pl.stapik.common:` plus the module path with `:` turned into `-`. The full
walk-through, including working on the library next to an app, is in [docs/consuming.md](docs/consuming.md).

## Building and testing

JDK 21 and the Android SDK are needed.

```
./gradlew test assembleDebug
```

Most of the tests run on the JVM and need no device. `sample-app` covers what the JVM cannot: install
it and open it to run the Keystore and storage self-test.

## Documentation

| Document                                       | About                                                                       |
|------------------------------------------------|-----------------------------------------------------------------------------|
| [docs/consuming.md](docs/consuming.md)         | Adding the library to an app, versions, local development                   |
| [docs/ui.md](docs/ui.md)                       | The shared screens, controllers and how an app wires them                   |
| [docs/cloud-api.md](docs/cloud-api.md)         | The contract with Stapik Cloud: requests, conflicts, errors, content layers |
| [docs/legacy-config.md](docs/legacy-config.md) | Reading and migrating the connection settings of the older apps             |
| [docs/i18n.md](docs/i18n.md)                   | Texts shared with the desktop and how keys map                              |
| [docs/ci.md](docs/ci.md)                       | Workflows of this repository and the templates for apps                     |

## Branches and releases

Work happens on `develop`; a pull request from `develop` to `master` runs the tests and a debug
build. A release is a tag `vX.Y.Z` on `master`, and the apps pin their submodule to such a tag. The
templates in `templates/github/` carry the tag too, so they are updated with every release.
