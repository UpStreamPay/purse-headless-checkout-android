plugins {
    alias(libs.plugins.android.library)
}

// The headless purse-payment SDK — mirrors usp-widget's `headless-checkout` package. Owns the
// purse-payment concept: session (V2) handling, the built payment model, the payment splitter
// (one primary + N secondaries), and the plugin-driven validate ("create payment") pipeline. It
// pushes session params / vault settings / merchant_specifics to `:partner-ui` plugins.
android {
    namespace = "eu.purse.checkout"
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

    testOptions {
        // `PaymentValidator` logs the outgoing /validate items via `android.util.Log`, whose
        // android.jar stub throws "not mocked" on the JVM test classpath. Return defaults instead,
        // so a diagnostic log can never fail a test. Only affects android.* stubs — `org.json` still
        // resolves to the real implementation pulled in below, which shadows the stub.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Shared concepts (≈ web `sdk`). `api` (not `implementation`) so consumers of this SDK — e.g.
    // the demo `:app` — see core types like PurseEnvironment / the session model transitively,
    // mirroring how web's headless-checkout re-exports sdk's enums.
    api(project(":core"))
    // The partner plugin lifecycle contract (≈ web `partner-ui`), which this engine drives — same
    // direction as web's headless-checkout depending on partner-ui.
    api(project(":partner-ui"))

    // Reactive state (StateFlow) for the split-driven public surface — the Kotlin analog of web's
    // Svelte stores. The Payment API client itself stays blocking (called off the main thread).
    api(libs.kotlinx.coroutines.core)

    // ProcessLifecycleOwner — the Android analog of web's `beforeunload`: the analytics batch
    // queue flushes when the app goes to background (see CheckoutEventBus wiring).
    implementation(libs.androidx.lifecycle.process)

    // org.json is provided by the Android platform at compile/runtime (part of android.jar) —
    // no production dependency needed. The Android platform stub throws on the unit test
    // classpath, so tests pull in the real implementation.
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
