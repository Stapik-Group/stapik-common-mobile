# Using the library in an app

The library is a git submodule of the app, pinned to a tag, and Gradle builds it together with the app
(`includeBuild`). The app depends on a tag, never on a branch.

## Once per app

1. Add the submodule and pin it to a release:
   ```
   git submodule add <url of stapik-common-mobile> libs/stapik-common-mobile
   git -C libs/stapik-common-mobile checkout vX.Y.Z
   git add libs/stapik-common-mobile .gitmodules
   ```
2. In the app's `settings.gradle.kts`, after `rootProject.name`:
   ```kotlin
   val commonDir = providers.gradleProperty("stapikCommonMobileDir").getOrElse("libs/stapik-common-mobile")
   apply(from = "$commonDir/stapik-common-mobile.settings.gradle.kts")
   ```
3. In the module that uses it: `implementation("pl.stapik.common:ui-shell")`. The coordinate is `pl.stapik.common:`
   plus the library path with `:` replaced by `-` (`core:sync` is `core-sync`, `ui:theme` is `ui-theme`).
4. CI: `actions/checkout` needs `submodules: recursive`.

## Developing the library next to an app

Without touching the app's files, point it at a checkout of the library: in `~/.gradle/gradle.properties`
```
stapikCommonMobileDir=/home/me/AndroidStudioProjects/StapikCommonMobile
```
(a path relative to the app also works). Remove the line to go back to the pinned submodule.

## Versions must match

An included build and the app share one Gradle run, so the **Android Gradle Plugin, Kotlin and Gradle must be the
same version in both** (the library's `gradle/libs.versions.toml` has the values). An app on an older AGP
is moved to the library's version first. Libraries (Compose BOM, kotlinx, OkHttp, ...) are resolved to the
highest version either side asks for.

## Updating

```
git -C libs/stapik-common-mobile fetch --tags
git -C libs/stapik-common-mobile checkout vX.Y.Z
git add libs/stapik-common-mobile
```
New library modules are added to `stapik-common-mobile.settings.gradle.kts` in the library, so an app gets them
with the update and needs no change in `settings.gradle.kts`.
