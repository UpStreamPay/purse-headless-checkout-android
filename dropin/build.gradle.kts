plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "eu.purse.dropin"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // The raw transport + environment layer (≈ usp-widget's `sdk`). `api` (not `implementation`)
    // so consumers of `:dropin` — e.g. the demo `:app` — see core types like PurseEnvironment
    // transitively, mirroring how web's headless-checkout re-exports sdk's enums.
    api(project(":core"))

    // org.json is provided by the Android platform at compile/runtime (part of android.jar) —
    // no production dependency needed. The Android platform stub throws on the unit test
    // classpath, so tests pull in the real implementation.
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
