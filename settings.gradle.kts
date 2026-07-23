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
// Per-partner plugin lifecycle contract (≈ usp-widget's `partner-ui`): the `PartnerUiPlugin`
// interface, its context, and the resolver — no concrete plugin implementations. Sits on `:core`.
include(":partner-ui")
