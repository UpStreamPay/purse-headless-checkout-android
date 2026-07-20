plugins {
    alias(libs.plugins.android.library)
}

// The raw layer — mirrors usp-widget's `sdk` package (Payment API client + environment targets).
// Deliberately dependency-light and domain-logic-free: no session/payment modelling lives here.
android {
    namespace = "eu.purse.dropin.core"
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
    // org.json is provided by the Android platform at compile/runtime (part of android.jar) —
    // no production dependency needed. The Android platform stub throws on the unit test
    // classpath, so tests pull in the real implementation.
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
