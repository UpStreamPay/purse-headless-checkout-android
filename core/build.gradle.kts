plugins {
    alias(libs.plugins.android.library)
}

// Shared concepts — mirrors usp-widget's `sdk` + `model` + `common` packages collapsed into one:
// the Payment API client, environment targets, the Partner/Method/vault enums, `MethodConfiguration`,
// and the raw `LegacyPaymentSession` DTO + its base64 `widget.data` decode. No session orchestration,
// no splitter — those live in `:headless-checkout`.
android {
    namespace = "eu.purse.core"
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
