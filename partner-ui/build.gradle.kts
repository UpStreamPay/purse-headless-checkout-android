plugins {
    alias(libs.plugins.android.library)
}

// Per-partner plugin lifecycle contract — mirrors usp-widget's `partner-ui` package, but reduced to
// the CONTRACT only: the `PartnerUiPlugin` interface, its `PartnerUiContext`, the `PluginBuilder`
// factory type, and the `getPlugin` resolver with empty registries. There are intentionally NO
// concrete plugin implementations (PayPal, Google Pay, card vaulting, redirect) and therefore no
// partner SDK dependencies — those are deferred. `:headless-checkout` drives this contract.
android {
    namespace = "eu.purse.partnerui"
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
    // Shared concepts (≈ web `sdk`). `api` so `:headless-checkout` sees `:core` types
    // (PaymentApiClient, LegacyPaymentSession, Partner/Method, the payload types) transitively.
    api(project(":core"))

    // org.json is provided by the Android platform at compile/runtime; tests pull in the real impl.
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
