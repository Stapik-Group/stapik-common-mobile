plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "pl.stapik.common.sample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "pl.stapik.common.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:crypto"))
}
