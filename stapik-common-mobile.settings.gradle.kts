// Included by an app's settings.gradle.kts, see docs/consuming.md:
//
//   val commonDir = providers.gradleProperty("stapikCommonMobileDir").getOrElse("libs/stapik-common-mobile")
//   apply(from = "$commonDir/stapik-common-mobile.settings.gradle.kts")
//
// It adds this library as an included build and maps the coordinates `pl.stapik.common:<module>` to its
// projects, so an app writes  implementation("pl.stapik.common:ui-shell")  (the path with ':' turned into '-').

val libraryDir = buildscript.sourceFile!!.parentFile

// Every library module an app may depend on. Keep in step with settings.gradle.kts (not :app and :sample-app).
val modules = listOf(
    "core:model",
    "core:sync",
    "core:document",
    "core:crypto",
    "core:cloud",
    "core:settings",
    "core:app",
    "core:presentation",
    "core:i18n",
    "core:theme",
    "ui:theme",
    "ui:components",
    "ui:sync",
    "ui:connect",
    "ui:shell",
)

includeBuild(libraryDir) {
    dependencySubstitution {
        for (path in modules) {
            substitute(module("pl.stapik.common:" + path.replace(':', '-'))).using(project(":$path"))
        }
    }
}
