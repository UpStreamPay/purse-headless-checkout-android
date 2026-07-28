plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Loads KEY=VALUE pairs from a gitignored .env file at the repo root (see .env.example).
// A real environment variable always wins over the .env file, so CI (which sets real env vars)
// is unaffected — this is purely a local-dev convenience for the demo app. Mirrors
// vault-android's app/build.gradle.kts.
val dotEnv: Map<String, String> = rootProject.file(".env")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
        val separatorIndex = trimmed.indexOf('=')
        if (separatorIndex <= 0) return@mapNotNull null
        trimmed.substring(0, separatorIndex).trim() to trimmed.substring(separatorIndex + 1).trim()
    }
    ?.toMap()
    ?: emptyMap()

fun envOrDotEnv(key: String): String =
    (System.getenv(key) ?: dotEnv[key] ?: "").replace("\"", "\\\"")

android {
    namespace = "eu.purse.checkout.demo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "eu.purse.checkout.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // usp-widget "alfred" dev server the demo mints orchestration sessions against — set via
        // env var or a gitignored .env file at the repo root (see .env.example). Empty when unset;
        // MainActivity falls back to the emulator default (10.0.2.2:9001).
        buildConfigField("String", "ALFRED_BASE_URL", "\"${envOrDotEnv("ALFRED_BASE_URL")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(project(":headless-checkout"))
    // GooglePayCoordinator/GooglePayModule (eu.purse.googlepay) — ships inside `:partner-ui`.
    implementation(project(":partner-ui"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // lifecycleScope + coroutines to observe the SDK's StateFlow surface off the main thread.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // The card (Purse vault / SecureFields) form is Compose-first — see CardCoordinator's doc.
    // Hosted inside the otherwise View-based layout via a plain ComposeView.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}