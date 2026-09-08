plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// The Klarna (installments) plugin — mirrors usp-widget's
// `partner-ui/src/plugins/partners/klarna-installments`, shipped as its own optional artifact
// (`eu.purse:headless-checkout-klarna`) so a merchant who does not offer Klarna never embeds the
// Klarna Mobile SDK (which also spares them Klarna's CDN Maven repository). Contributes
// `KlarnaModule` to `PluginRegistry` through `ServiceLoader`. Sits on `:partner-ui` only.
android {
    namespace = "eu.purse.partnerui.klarna"
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
    // The native KlarnaPaymentView widget + its callback contract. `api` so an app can inflate the
    // view and observe its callback itself if it wants to.
    api(libs.klarna.mobile.sdk)
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
