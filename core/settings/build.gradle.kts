import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

// Settings that every app has: the connection to Stapik Cloud, stored encrypted. Plain JVM; the
// Android Keystore cipher is supplied by the app (see :core:crypto).
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
    api(project(":core:cloud"))
    api(project(":core:document"))

    testImplementation(libs.junit)
}
