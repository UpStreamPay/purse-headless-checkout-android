plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// The PayPal wallet plugin — mirrors usp-widget's `partner-ui/src/plugins/partners/paypal/wallet`,
// shipped as its own optional artifact (`eu.purse:headless-checkout-paypal`) so a merchant who does
// not offer PayPal never embeds the PayPal Android SDK. Contributes `PaypalModule` to
// `PluginRegistry` through `ServiceLoader`. Sits on `:partner-ui` only.
android {
    namespace = "eu.purse.partnerui.paypal"
    compileSdk {
        version = release(37)
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

kotlin {
    compilerOptions {
        optIn.add("eu.purse.core.InternalPurseApi")
    }
}

dependencies {
    api(project(":headless-checkout"))
    // The web-checkout browser switch (PayPalWebCheckoutClient) plus PayPal's own branded
    // PayPalButton. `api` so an app can render the button itself, exactly as it could Google Pay's.
    api(libs.paypal.web.payments)
    api(libs.paypal.payment.buttons)
    // The self-contained approval return Activity.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
