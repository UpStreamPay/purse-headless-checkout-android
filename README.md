# Purse Android Headless Checkout

[![CI](https://github.com/UpStreamPay/purse-headless-checkout-android/actions/workflows/ci.yml/badge.svg)](https://github.com/UpStreamPay/purse-headless-checkout-android/actions/workflows/ci.yml)

A headless Android SDK for Purse payments. It owns the payment engine — session, payment methods,
split, `/validate` — and you own the UI. Card (Purse vault), Google Pay, PayPal, Klarna, any
redirection method, gift cards, saved cards and BNPL simulation ship in the box. It mirrors the web
`usp-widget` headless SDK; the [web ↔ Android map](docs/web-android-map.md) says what differs.

**Start here → [Getting started](docs/integration/getting-started.md)** · [Partner setup](docs/integration/partner-setup.md) · [Recipes](docs/integration/recipes.md) · [API reference](docs/integration/consumer-api.md) · [All docs](docs/README.md)

## Install

```kotlin
dependencies {
    implementation("eu.purse:headless-checkout-android:<version>")      // engine, payment elements, card, redirection methods
    implementation("eu.purse:headless-checkout-googlepay:<version>")    // only if you offer Google Pay
    implementation("eu.purse:headless-checkout-klarna:<version>")       // only if you offer Klarna (+ Klarna's Maven repository)
    implementation("eu.purse:headless-checkout-paypal:<version>")       // only if you offer PayPal
}
```

Maven Central, `eu.purse` group, one version for all. Partners that bring their own SDK are separate
artifacts, so a card-only merchant embeds none of them. Versions and the generated API reference:
[releases](https://github.com/UpStreamPay/purse-headless-checkout-android/releases).

## Install

```kotlin
dependencies {
    implementation("eu.purse:headless-checkout-android:<version>")      // engine, payment elements, card, redirection methods
    implementation("eu.purse:headless-checkout-googlepay:<version>")    // only if you offer Google Pay
    implementation("eu.purse:headless-checkout-klarna:<version>")       // only if you offer Klarna (+ Klarna's Maven repository)
    implementation("eu.purse:headless-checkout-paypal:<version>")       // only if you offer PayPal
}
```

Maven Central, `eu.purse` group, one version for all. Partners that bring their own SDK are separate
artifacts, so a card-only merchant embeds none of them. Versions and the generated API reference:
[releases](https://github.com/UpStreamPay/purse-headless-checkout-android/releases).

## In short

```kotlin
val checkout = PurseHeadlessCheckout.init(widgetData, lockedEnvironment = PurseEnvironment.PRODUCTION)

val methods by checkout.paymentMethods.collectAsState()          // what the session offers
val fulfilled by checkout.isPaymentFulfilled.collectAsState()    // split covered and forms valid

val element = rememberPaymentElement(checkout, selectedMethod, listener)   // card form, wallet button, widget…
LaunchedEffect(selectedMethod.id) { checkout.setPrimary(selectedMethod) }
PaymentElementUi(element)
if (!element.selfSubmitting) Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
```

Outcomes arrive on the listener; after a redirect, confirm the payment from your backend
([recipe 9](docs/integration/recipes.md#9-confirm-after-a-redirect-ask-your-backend)).

## Layout

| Module | Artifact | Role |
|---|---|---|
| `:headless-checkout` | `eu.purse:headless-checkout-android` | The SDK: `PurseHeadlessCheckout`, the payment elements, the plugin contract, the redirect WebView, the card and redirection plugins. Three packages mirror the web packages: `eu.purse.checkout` (`headless-checkout`), `eu.purse.partnerui` (`partner-ui`), `eu.purse.core` (`sdk` + `model` + `common`). |
| `:partner-googlepay` | `eu.purse:headless-checkout-googlepay` | Optional: the Google Pay plugin + Play Services Wallet. |
| `:partner-klarna` | `eu.purse:headless-checkout-klarna` | Optional: the Klarna plugin + Klarna Mobile SDK. |
| `:partner-paypal` | `eu.purse:headless-checkout-paypal` | Optional: the PayPal plugin + PayPal Android SDK. |
| `:app` | — | Demo: a Purse setup page (pick the methods the store offers) → a store checkout → the result, every method, against sessions minted by `alfred`. Never published. |

Requirements: minSdk 26, Jetpack Compose.

## Build, test, run the demo

The build needs nothing from your machine: Gradle provisions its own JDK 21 (`gradle/gradle-daemon-jvm.properties`).

```bash
./gradlew assembleDebug testDebugUnitTest lint       # what CI runs, plus:
./gradlew ktlintCheck detekt apiCheck                # style, static analysis, public API unchanged
./gradlew :app:assembleRelease verify16kbAlignment   # minified demo (R8), native libs 16 KB aligned
./gradlew :app:installDebug                          # needs alfred running — see docs/internal/testing-with-alfred.md
```

Changed the public API on purpose? `./gradlew apiDump` and commit the `*/api/*.api` dumps (a removal
is a `feat!:`). Releasing: push to `main` — [docs/contributing/release.md](docs/contributing/release.md).

## Related

- `../usp-widget` — the web SDK this design mirrors (`@upstreampay/headless-checkout`, `@upstreampay/partner-ui`)
- [securefields-android](https://github.com/UpStreamPay/purse-securefields-android) — the card fields this SDK embeds; same publishing chain
- [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039), [SDK-12216](https://upstreampay.atlassian.net/browse/SDK-12216), [SDK-12218](https://upstreampay.atlassian.net/browse/SDK-12218), [SDK-12219](https://upstreampay.atlassian.net/browse/SDK-12219)
