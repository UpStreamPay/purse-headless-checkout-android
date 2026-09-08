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

rootProject.name = "purse-headless-checkout-android"

// Minimal demo host — mints a session via alfred and drives the headless SDK. ≈ web playground.
include(":app")
// The SDK — one module, published as `eu.purse:headless-checkout-android`, with the card and
// redirection methods inside. Its three packages mirror usp-widget's packages (`eu.purse.core` ≈
// `sdk`+`model`+`common`, `eu.purse.partnerui` ≈ `partner-ui`, `eu.purse.checkout` ≈
// `headless-checkout`); see headless-checkout/build.gradle.kts for what lives where.
include(":headless-checkout")
// Optional partner artifacts — one plugin + the partner's own SDK each, contributed to
// `PluginRegistry` through `PartnerModule` + `ServiceLoader`, so a merchant embeds only the partner
// SDKs of the methods they offer (and only Klarna merchants declare Klarna's Maven repository).
// Each sits on `:headless-checkout`.
include(":partner-googlepay")   // Google Pay (Ingenico/Worldline, Adyen, default) — Play Services Wallet
include(":partner-klarna")      // Klarna installments — Klarna Mobile SDK (needs Klarna's CDN repository)
include(":partner-paypal")      // PayPal wallet — PayPal Android SDK
