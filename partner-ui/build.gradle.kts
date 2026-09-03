plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// Per-partner plugin lifecycle contract PLUS its concrete plugins — mirrors usp-widget's
// `partner-ui` package, which bundles the `Plugin<Context>` contract alongside every partner's own
// plugin folder (`plugins/generics/…`, `plugins/partners/…`, `plugins/vaults/…`) in one
// package. Ships: the `PartnerUiPlugin` interface, `PartnerUiContext`, `PluginBuilder`/`getPlugin`
// resolver (`eu.purse.partnerui`); the shared redirect machinery — `RedirectionResolver` plus the
// in-app-WebView return Activity, used by every plugin, so it sits alongside `plugins/` rather than
// inside one of them, exactly as web keeps it in `sdk`/`common` (`eu.purse.partnerui.redirect`);
// the Google Pay (Ingenico/Worldline) plugin — pure protocol plus the
// Play-Services payment sheet (`plugins.generics.googlepay`);
// the Klarna plugin — pure protocol plus the native `KlarnaPaymentView` widget
// (`plugins.partners.klarna`); the PayPal wallet plugin — pure protocol plus PayPal's own
// browser-switch approval and its self-contained return Activity
// (`plugins.partners.paypal.wallet`); the Purse vault credit-card plugin — pure protocol plus the
// SecureFields Android SDK's Compose surface (`plugins.vaults.purse.creditcard`); and the generic
// redirection template — one plugin claiming every method the session marks
// `configurations.template = "redirection"` (`plugins.generics.redirection`). Each registers
// itself into the resolver by default (see `PluginRegistry`'s own `init` block) and reaches
// `:headless-checkout` only through the
// `GooglePayHost`/`KlarnaHost`/`PaypalHost`/`CardHost`/`RedirectionHost` interfaces it defines,
// never a direct dependency — `:headless-checkout` depends on `:partner-ui`, not the other way
// around.
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
        compose = true
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
    // PayPal wallet: the web-checkout browser switch (PayPalWebCheckoutClient) plus PayPal's own
    // branded PayPalButton. `api` so `:app` can render the button, exactly as it does Google Pay's.
    api(libs.paypal.web.payments)
    api(libs.paypal.payment.buttons)
    // Purse vault credit card: SecureFields' native fields + its Compose surface
    // (`rememberSecureFields`/`VaultForm`/`VaultField`). `api` so `:app` can render the form.
    api(libs.securefields.android)
    // The self-contained 3-D Secure return Activity (in-app WebView) + ActivityResultContract.
    implementation(libs.androidx.appcompat)
    // lifecycleScope for the Google Pay / Klarna coordinators' coroutine work.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Awaiting the isReadyToPay Task / running coordinator work from a coroutine.
    implementation(libs.kotlinx.coroutines.core)
    // CardCoordinator is Compose-first (see its doc) — remember{}/mutableStateOf/DisposableEffect,
    // and rememberLauncherForActivityResult for its 3-D Secure redirect launcher.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // org.json is provided by the Android platform at compile/runtime; tests pull in the real impl.
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
