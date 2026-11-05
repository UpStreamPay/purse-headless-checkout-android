plugins {
    alias(libs.plugins.android.library)
    // For the payment elements and the partner surfaces (card form, wallet buttons) — the Compose
    // half of an otherwise headless module.
    alias(libs.plugins.kotlin.compose)
}

// The Purse headless checkout SDK — one module, one artifact (`eu.purse:headless-checkout-android`)
// with the card and redirection methods inside; Google Pay, Klarna and PayPal are optional sibling
// artifacts (`:partner-googlepay` / `:partner-klarna` / `:partner-paypal`) because each drags in a
// partner SDK — Klarna's even a Maven repository of its own. Three packages mirror usp-widget's
// packages:
//
//   eu.purse.core       ≈ web `sdk` + `model` + `common`: the Payment API client, environment
//                         targets, the Partner/Method/vault enums, `MethodConfiguration`, the raw
//                         `LegacyPaymentSession` DTO + its base64 `widget.data` decode. No
//                         orchestration, no splitter.
//   eu.purse.partnerui  ≈ web `partner-ui`: the plugin lifecycle contract (`PartnerUiPlugin`,
//                         `PartnerUiContext`, `PluginRegistry`), the `PartnerModule` seam the
//                         optional artifacts plug into (discovered with `ServiceLoader`), the shared
//                         redirect machinery (`RedirectionResolver` + the in-app-WebView return
//                         Activity), and the two plugins every merchant gets — Purse vault card
//                         (SecureFields form) and the generic redirection template. Plugins reach
//                         the engine only through the `PluginHost` seam
//                         `PurseHeadlessCheckout.host(item)` implements.
//   eu.purse.checkout   ≈ web `headless-checkout`: `PurseHeadlessCheckout` — session handling, the
//                         built payment model, the splitter (one primary + N secondaries), the
//                         plugin-driven validate pipeline — and `rememberPaymentElement`.
//
// The dependency direction between the packages (checkout → partnerui → core, never back) is a
// convention the `// Web: / Same: / Differs:` headers and docs/architecture keep; it used to be
// enforced by module boundaries until the three were collapsed into one artifact (see
// docs/decisions/distribution.md).
android {
    namespace = "eu.purse.checkout"
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

    testOptions {
        // `PaymentValidator` and the coordinators log via `android.util.Log`, whose android.jar stub
        // throws "not mocked" on the JVM test classpath. Return defaults instead, so a diagnostic
        // log can never fail a test. Only affects android.* stubs — `org.json` still resolves to
        // the real implementation pulled in below, which shadows the stub.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        // The coordinator seams this module opens only for Purse's own partner artifacts.
        optIn.add("eu.purse.core.InternalPurseApi")
    }
}

dependencies {
    // Reactive state (StateFlow) for the split-driven public surface — the Kotlin analog of web's
    // Svelte stores. `api`: merchants collect these flows.
    api(libs.kotlinx.coroutines.core)

    // Purse vault credit card: SecureFields' native fields + its Compose surface. `api` so an app can
    // render the form itself if it wants to.
    api(libs.securefields.android)

    // The redirect return Activity (in-app WebView) and PayPal's return Activity.
    implementation(libs.androidx.appcompat)
    // lifecycleScope for the coordinators' coroutine work; ProcessLifecycleOwner — the Android
    // analog of web's `beforeunload`: the analytics batch queue flushes when the app goes to
    // background (see CheckoutEventBus wiring).
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    // Records the host app's FLAG_DEBUGGABLE at process start (`HostAppInitializer`), which gates the
    // internal TEST / DEVELOPMENT tiers — `init` takes no Context. Already on the runtime classpath
    // through lifecycle-process; declared for the `Initializer` API.
    implementation(libs.androidx.startup.runtime)
    // The payment elements and the coordinators are Compose-first — remember{}/mutableStateOf,
    // rememberLauncherForActivityResult for the Activity-result launchers.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // org.json is provided by the Android platform at compile/runtime (part of android.jar) —
    // no production dependency needed. The Android platform stub throws on the unit test
    // classpath, so tests pull in the real implementation.
    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // The engine's tests drive sessions offering PayPal, Google Pay and Klarna; the plugins for those
    // come from the optional artifacts, discovered on the test classpath exactly as in an app.
    testImplementation(project(":partner-googlepay"))
    testImplementation(project(":partner-klarna"))
    testImplementation(project(":partner-paypal"))
}

// ── SDK version, from the build ───────────────────────────────────────────────────────────────────
// The version this artifact is published under lives in the git tag (`SDK_VERSION`, see the root
// script). Telemetry stamps it on every message, so it is generated into a Kotlin constant rather
// than maintained by hand — a hand-kept constant is exactly what drifted before.
abstract class GenerateSdkVersionTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDir.get().asFile.resolve("eu/purse/core/SdkVersion.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Generated by :headless-checkout `generateSdkVersion` from `SDK_VERSION` — do not edit.
            |package eu.purse.core
            |
            |/** The version this artifact is built as: the release tag in CI, `0.0.0-SNAPSHOT` locally. */
            |internal object SdkVersion {
            |    const val VALUE = "${version.get()}"
            |}
            |
            """.trimMargin(),
        )
    }
}

val generateSdkVersion by tasks.registering(GenerateSdkVersionTask::class) {
    version.set(project.version.toString())
    outputDir.set(layout.buildDirectory.dir("generated/source/sdkVersion"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateSdkVersion, GenerateSdkVersionTask::outputDir)
    }
}
