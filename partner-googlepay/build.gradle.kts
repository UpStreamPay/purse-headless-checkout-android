plugins {
    alias(libs.plugins.android.library)
}

// The Google Pay (Ingenico / Worldline) plugin. Holds everything Google-Pay-specific: the pure
// protocol (`GooglePayPlugin : PartnerUiPlugin` + mappers, unit-testable), the Play-Services
// `PaymentsClient` + payment-sheet coordinator, the Worldline product-320 refinement, and the
// 3-D Secure redirect Activity. It registers itself into `:partner-ui`'s `PluginRegistry` at
// runtime (via `GooglePayModule.register()`), so `:partner-ui` never depends on it.
android {
    namespace = "eu.purse.googlepay"
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
    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // The headless SDK this plugin drives. `api` so the demo `:app` sees PurseHeadlessCheckout and
    // this module's coordinator types transitively. Brings `:core` + `:partner-ui` transitively too.
    api(project(":headless-checkout"))

    // Google Pay: PaymentsClient, the branded PayButton, and the GetPaymentDataResult
    // ActivityResultContract. `api` so `:app` can render the button + register the launcher.
    api(libs.play.services.wallet)
    // Custom Tabs for the 3-D Secure GET redirect.
    implementation(libs.androidx.browser)
    // The self-contained 3-D Secure return Activity + ActivityResultContract.
    implementation(libs.androidx.appcompat)
    // lifecycleScope for the coordinator's coroutine work.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Awaiting the isReadyToPay Task from a coroutine.
    implementation(libs.kotlinx.coroutines.core)

    // org.json is provided by the Android platform at compile/runtime; tests pull in the real impl.
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
