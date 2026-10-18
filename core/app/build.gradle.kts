import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

// Operations on the user's data that every app needs (backup, import, restore). Plain JVM, so the
// rules can be unit-tested without a device.
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
    api(project(":core:document"))
    api(project(":core:cloud")) // passphrase protection of exported archives

    testImplementation(libs.junit)
}
