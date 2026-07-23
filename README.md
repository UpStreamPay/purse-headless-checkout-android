# Purse Android Headless Checkout

A **headless** Android SDK for Purse payments. It owns the *concept of a purse payment* — session
handling, the payment-method model, payment splitting, and the plugin-driven validate ("create
payment") pipeline — and leaves the UI entirely to the merchant. It mirrors the web `usp-widget`
architecture (the sibling `../usp-widget` repo), not the older native drop-in.

> **Status: back-to-basics rebuild.** The core purse-payment engine is in place and builds green
> with unit tests: session (V2) `widget.data` decode, the built payment model, the splitter (one
> primary + N secondaries), a reactive `StateFlow` surface, and the create-payment pipeline. Partner
> integrations are **contract-only for now** — the `PartnerUiPlugin` lifecycle exists but there are
> no PayPal / Google Pay / card / redirect implementations, so `submitPayment` is wired but has
> nothing to submit through until a plugin is registered.

## Repository layout

| Module | Package | Mirrors web | Role |
|---|---|---|---|
| `:app` | `eu.purse.checkout.demo` | playground | Demo host — mints a session via alfred and prints the SDK's methods/split (smoke test). |
| `:headless-checkout` | `eu.purse.checkout` | `headless-checkout` | `PurseHeadlessCheckout`: session decode, payment model, `PursePaymentSplitter`, the validate pipeline. |
| `:core` | `eu.purse.core` | `sdk` + `model` + `common` | Payment API client, environment targets, the `Partner`/`Method`/vault enums, `MethodConfiguration`, the `LegacyPaymentSession` DTO + `widget.data` decode, and the typed validate payloads. |
| `:partner-ui` | `eu.purse.partnerui` | `partner-ui` | The `PartnerUiPlugin` lifecycle **contract** and `getPlugin` resolver — no concrete plugins yet. |

Dependency direction: `:app → :headless-checkout → { :core, :partner-ui }`, `:partner-ui → :core` —
mirroring web's `dropin → headless-checkout → { sdk, partner-ui }`, `partner-ui → sdk`.

Full docs: **[docs/README.md](docs/README.md)** · Architecture:
**[docs/architecture/headless-checkout.md](docs/architecture/headless-checkout.md)**.

## Using the SDK

```kotlin
// 1. Decode the merchant backend's base64 widget.data (V2 params) into a headless checkout.
val checkout = PurseHeadlessCheckout.init(widgetData)          // pass PurseEnvironment.PRODUCTION to lock

// 2. Inspect the session reactively (StateFlow).
checkout.paymentMethods.value        // primaries + secondaries, with live disabled states
checkout.remainingAmountToPay.value  // what a primary still owes
checkout.isPaymentFulfilled.value

// 3. Build the split: draw down secondaries and/or choose a primary.
checkout.takeSecondary(giftCard, balance = BigDecimal("10.00"))
checkout.setPrimary(cardMethod)

// 4. Create the payment (suspend; off the main thread). Needs a registered :partner-ui plugin.
val result = checkout.submitPayment()
```

## Build & test

The Android Studio JBR is required (system `java` may not be on `PATH`):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build            # compile + lint + unit tests, all modules
./gradlew :app:installDebug
```

## Run the demo

Exercising the demo end to end needs **alfred** — the `usp-widget` dev server that mints real Purse
sessions. Point the app at it via a gitignored `.env` (`ALFRED_BASE_URL=http://10.0.2.2:9001` on an
emulator; `.env` is read at build time, so rebuild after editing). Full walkthrough:
**[docs/internal/testing-with-alfred.md](docs/internal/testing-with-alfred.md)**.

## Related

- `../usp-widget` — the web SDK this design mirrors (`@upstreampay/headless-checkout`, `@upstreampay/partner-ui`)
- [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039)
