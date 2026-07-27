plugins {
    alias(libs.plugins.android.library)
}

// Per-partner plugin lifecycle contract PLUS its concrete plugins — mirrors usp-widget's
// `partner-ui` package, which bundles the `Plugin<Context>` contract alongside every partner's own
// plugin folder (`plugins/generics/googlepay`, `plugins/partners/…`) in one package. Ships:
// the `PartnerUiPlugin` interface, `PartnerUiContext`, `PluginBuilder`/`getPlugin` resolver
// (`eu.purse.partnerui`); the Google Pay (Ingenico/Worldline) plugin — pure protocol, the
// Play-Services payment sheet, and the 3-D Secure redirect Activity (`plugins.generics.googlepay`);
// and the Klarna plugin — pure protocol plus the native `KlarnaPaymentView` widget
// (`plugins.partners.klarna`). Each registers itself into the resolver at runtime
// (`GooglePayModule.register()` / `KlarnaModule.register()`) and reaches `:headless-checkout` only
// through the `GooglePayHost`/`KlarnaHost` interfaces it defines, never a direct dependency —
// `:headless-checkout` depends on `:partner-ui`, not the other way around.
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
    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // Shared concepts (≈ web `sdk`). `api` so `:headless-checkout` sees `:core` types
    // (PaymentApiClient, LegacyPaymentSession, Partner/Method, the payload types) transitively.
    api(project(":core"))

    // Google Pay: PaymentsClient, the branded PayButton, and the GetPaymentDataResult
    // ActivityResultContract. `api` so `:app` can render the button + register the launcher.
    api(libs.play.services.wallet)
    // Klarna: the native KlarnaPaymentView widget + its callback contract. `api` so `:app` can
    // inflate the view and implement/observe its callback.
    api(libs.klarna.mobile.sdk)
    // The self-contained 3-D Secure return Activity (in-app WebView) + ActivityResultContract.
    implementation(libs.androidx.appcompat)
    // lifecycleScope for the Google Pay / Klarna coordinators' coroutine work.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Awaiting the isReadyToPay Task / running coordinator work from a coroutine.
    implementation(libs.kotlinx.coroutines.core)

    // org.json is provided by the Android platform at compile/runtime; tests pull in the real impl.
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
