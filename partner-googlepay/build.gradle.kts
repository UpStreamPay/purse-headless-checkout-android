plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// The Google Pay (Ingenico/Worldline, Adyen, default) plugin — mirrors usp-widget's
// `partner-ui/src/plugins/generics/googlepay`, shipped as its own optional artifact
// (`eu.purse:headless-checkout-googlepay`) so a merchant who does not offer Google Pay never embeds
// Play Services Wallet. Contributes `GooglePayModule` to `PluginRegistry` through `ServiceLoader`
// (see src/main/resources/META-INF/services). Sits on `:partner-ui` only.
android {
    namespace = "eu.purse.partnerui.googlepay"
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
        // The coordinator seams `:partner-ui` opens only for Purse's own partner artifacts.
        optIn.add("eu.purse.core.InternalPurseApi")
    }
}

dependencies {
    // The plugin contract, the coordinator base and the shared redirect machinery.
    api(project(":headless-checkout"))
    // PaymentsClient, the branded PayButton, and the GetPaymentDataResult ActivityResultContract.
    // `api` so an app can render the button + register the launcher itself if it wants to.
    api(libs.play.services.wallet)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.activity.compose)

    // org.json is provided by the Android platform at compile/runtime; tests pull in the real impl.
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
