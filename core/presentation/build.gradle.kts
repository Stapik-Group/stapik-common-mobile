import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

// What the screens show and what the buttons do, without any Android or Compose: sync indicator,
// connect form, document state. The Compose screens (ui:*) only draw these. Plain JVM, so it is
// unit-tested without a device.
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
    api(project(":core:app"))
    api(project(":core:settings"))

    testImplementation(libs.junit)
}
