plugins {
    alias(libs.plugins.android.library)
}

// Per-partner plugins — mirrors usp-widget's `partner-ui` package. Each partner (PayPal today)
// owns its /init+/validate payload contract on top of `:core`'s Payment API client, plus (added in
// a later step) the partner SDK launch and its self-contained return Activity. `:dropin` depends on
// this and orchestrates it.
android {
    namespace = "eu.purse.dropin.partnerui"
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
    // The raw transport + environment layer (≈ web `sdk`). `api` so `:dropin` sees `:core` types
    // (PaymentApiClient, PaymentApiException, PurseEnvironment) transitively through `:partner-ui`.
    api(project(":core"))

    // PayPal's official Android web-checkout SDK — opens the approval browser-switch and returns
    // the outcome. Pulls in corepayments (CoreConfig/Environment/PayPalSDKError) + androidx
    // transitively. See docs/partners/paypal.md.
    implementation(libs.paypal.web.payments)
    // The official branded PayPal button. `api` so the demo `:app`'s method list can render it.
    api(libs.paypal.payment.buttons)
    // For the self-contained return Activity + ActivityResultContract.
    implementation(libs.androidx.appcompat)

    // Google Pay: PaymentsClient, the branded PayButton, and the GetPaymentDataResult
    // ActivityResultContract. `api` so the demo `:app` sees those types to render the button and
    // register the launcher. See docs/partners/adyen.md.
    api(libs.play.services.wallet)

    // org.json is provided by the Android platform at compile/runtime; tests pull in the real impl.
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
