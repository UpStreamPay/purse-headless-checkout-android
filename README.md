# Purse Android Headless Checkout

A headless Android SDK for Purse payments. It owns the payment engine — session, payment methods,
split, `/validate` — and you own the UI. Card (Purse vault), Google Pay, PayPal, Klarna, any
redirection method, gift cards, saved cards and BNPL simulation ship in the box. It mirrors the web
`usp-widget` headless SDK; the [web ↔ Android map](docs/web-android-map.md) says what differs.

**Start here → [Getting started](docs/integration/getting-started.md)** · [Recipes](docs/integration/recipes.md) · [API reference](docs/integration/consumer-api.md) · [All docs](docs/README.md)

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

Outcomes arrive on the listener; after a redirect, confirm with `checkout.awaitPaymentOutcome()`.

## Modules

| Module | Role |
|---|---|
| `:headless-checkout` | `PurseHeadlessCheckout`, the payment elements. What an app depends on. |
| `:partner-ui` | The plugins (card, Google Pay, PayPal, Klarna, redirection) and their coordinators. Pulled in transitively. |
| `:core` | Payment API client, session decode, shared types. Pulled in transitively. |
| `:app` | Demo: session → pay → result, every method, against sessions minted by `alfred`. |

Requirements: minSdk 26, Jetpack Compose. Not published to Maven yet: consume as Gradle source
modules, see [getting started](docs/integration/getting-started.md#2-install).

## Build, test, run the demo

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build                 # compile + lint + unit tests
./gradlew :app:installDebug     # needs alfred running — see docs/internal/testing-with-alfred.md
```

## Related

- `../usp-widget` — the web SDK this design mirrors (`@upstreampay/headless-checkout`, `@upstreampay/partner-ui`)
- [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039), [SDK-12216](https://upstreampay.atlassian.net/browse/SDK-12216)
