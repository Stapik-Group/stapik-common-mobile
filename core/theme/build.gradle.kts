import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

// Plain JVM module: colours of the Stapik themes as plain numbers (no Compose), so they can be
// checked on the JVM (contrast). The Compose mapping comes in ui:theme.
// Targets Java 11 to match the Android modules that will consume it.
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
    testImplementation(libs.junit)
}
