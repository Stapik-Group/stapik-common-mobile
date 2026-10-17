plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "pl.stapik.common.crypto"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:document"))
}
