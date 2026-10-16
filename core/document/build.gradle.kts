import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// File-based document storage. It uses only java.io / java.nio (available on Android since API 26),
// so it is tested on the JVM. Encryption at rest is injected through PayloadCipher; the
// Android Keystore implementation comes later in an Android module.
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
    api(project(":core:sync"))

    testImplementation(libs.junit)
}
