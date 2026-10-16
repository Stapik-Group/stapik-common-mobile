import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) // only the tests declare @Serializable types for now
}

// Plain JVM module: no Android dependencies, so the domain model and (later) the sync engine
// can be unit-tested quickly. Targets Java 11 to match the Android modules that will consume it.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    // Exposed in the public API (JsonObject), hence api().
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
