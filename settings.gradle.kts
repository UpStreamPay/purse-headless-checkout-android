pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Klarna's Mobile Payments SDK (com.klarna.mobile:sdk) — not published to Maven Central, see
        // https://docs.klarna.com/acquirer/klarna/mobile-payments/integrate-with-mobile-sdk/android/native-view/
        maven("https://x.klarnacdn.net/mobile-sdk/")
    }
}

rootProject.name = "dropin-android"

// Minimal demo host — mints a session via alfred and drives the headless SDK. ≈ web playground.
include(":app")
// The headless purse-payment SDK (≈ usp-widget's `headless-checkout`): session decode, the payment
// model, the splitter, and the plugin-driven validate pipeline. Depends on `:core` + `:partner-ui`.
include(":headless-checkout")
// Shared concepts (≈ usp-widget's `sdk` + `model` + `common`): the Payment API client, environment
// targets, the Partner/Method/vault enums, and the raw session DTO + decode. No orchestration here.
include(":core")
// Per-partner plugin lifecycle contract (≈ usp-widget's `partner-ui`) PLUS its concrete plugins —
// the `PartnerUiPlugin` interface/resolver, the Google Pay (Ingenico/Worldline) implementation
// (protocol + Play-Services payment sheet + 3-D Secure), and the Klarna implementation (protocol +
// native `KlarnaPaymentView`), living under `plugins.generics.googlepay` / `plugins.partners.klarna`
// inside this same module, exactly like web's `partner-ui` package bundles
// `plugins/generics/googlepay` / `plugins/partners/klarna-installments`. Each registers itself into
// `PluginRegistry` at class load (its `init` block). Sits on
// `:core` only — it talks to `:headless-checkout` through the `GooglePayHost`/`KlarnaHost` seams so
// the dependency never points back the other way (`:headless-checkout` depends on `:partner-ui`, not
// vice versa).
include(":partner-ui")
