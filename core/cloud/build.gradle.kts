import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

// Client of Stapik Cloud: HTTP, the gzip and end-to-end encryption layers of the desktop
// format, and the CloudGateway implementation used by the sync engine. Plain JVM so that it
// can be unit-tested without a device.
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
    implementation(libs.okhttp)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
