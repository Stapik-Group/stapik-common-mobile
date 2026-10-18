# Continuous integration

The rules are those of the other Stapik repositories:

| Event                                       | What happens                                    |
|---------------------------------------------|-------------------------------------------------|
| push to any branch                          | nothing                                         |
| pull request from `develop` to `master`     | tests and a debug build                         |
| merge into `master`                         | nothing                                         |
| tag `v*`                                    | the same tests again (the library has no files to publish) |

"Nothing" is the absence of a trigger: do not add a `push` trigger to a workflow.

## Workflows of this repository (`.github/workflows/`)

| File             | Kind      | Purpose |
|------------------|-----------|---------|
| `build.yml`      | reusable  | JDK 21, Android SDK licences, Gradle, then `./gradlew test assembleDebug` (input `gradle-tasks`). Checks out submodules. |
| `ci.yml`         | PR        | calls `build.yml` for a pull request from `develop` |
| `release.yml`    | tag       | calls `build.yml` for a `v*` tag |
| `release-apk.yml`| reusable  | for apps: tests, builds the signed release APK and attaches it to the release of the tag |

## In an app

Copy `templates/github/pull-request.yml` and `templates/github/release-apk.yml` to `.github/workflows/` of the app and set
`@v...` to the tag of the library that the app's submodule is on (like the `GIT_TAG` of a dependency on the desktop),
and `apk-name` in the second. The signing secrets are the ones the apps already use: `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; `secrets: inherit` passes them on.

The reusable workflows check out the submodules. If the library repository is private, the default `GITHUB_TOKEN` of an
app cannot read it: create a token (or a deploy key) with read access and store it as the secret `SUBMODULES_TOKEN`
of the app. Using a reusable workflow from another private repository also has to be allowed in the library
repository (Settings, Actions, Access).
