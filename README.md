# Purse Android Headless Checkout

A **headless** Android SDK for Purse payments. It owns the *concept of a purse payment* — session
handling, the payment-method model, payment splitting, and the plugin-driven validate ("create
payment") pipeline — and leaves the UI entirely to the merchant. It mirrors the web `usp-widget`
architecture (the sibling `../usp-widget` repo), not the older native drop-in.

> **Status: back-to-basics rebuild.** The core purse-payment engine is in place and builds green
> with unit tests: session (V2) `widget.data` decode, the built payment model, the splitter (one
> primary + N secondaries), a reactive `StateFlow` surface, and the create-payment pipeline.
> Secondaries (gift cards / vouchers / loyalty) are implemented, and five **primary** plugins ship
> inside `:partner-ui`, registered by default: **Google Pay via Ingenico/Worldline** (full 3-D
> Secure), **Klarna** (native `KlarnaPaymentView`), **PayPal** (wallet pay-now, via the official
> PayPal Android SDK's browser switch), the **Purse vault credit card** (native SecureFields fields,
> rendered via Compose), and the **generic redirection template** — one plugin serving whichever
> methods the session marks `template = redirection`, with no partner named in code.
> Still to come: Apple Pay, the rendering host
> for anything beyond the card plugin's own Compose surface.

## Repository layout

| Module | Package | Mirrors web | Role |
|---|---|---|---|
| `:app` | `eu.purse.checkout.demo` | playground | Demo host — mints a session via alfred, prints the SDK's methods/split (including each item's `template` and the plugin actually resolved for it), and drives Google Pay, PayPal, Klarna, a Purse-vaulted card, or any redirection-template method. |
| `:headless-checkout` | `eu.purse.checkout` | `headless-checkout` | `PurseHeadlessCheckout`: session decode, payment model, `PursePaymentSplitter`, the validate pipeline. |
| `:core` | `eu.purse.core` | `sdk` + `model` + `common` | Payment API client, environment targets, the `Partner`/`Method`/vault enums, `MethodConfiguration`, the `LegacyPaymentSession` DTO + `widget.data` decode, and the typed validate payloads. |
| `:partner-ui` | `eu.purse.partnerui` + `.redirect` + `.plugins.generics.googlepay` + `.plugins.generics.redirection` + `.plugins.partners.klarna` + `.plugins.partners.paypal.wallet` + `.plugins.vaults.purse.creditcard` | `partner-ui` (contract + `plugins/generics/googlepay` + `plugins/generics/redirection` + `plugins/partners/klarna-installments` + `plugins/partners/paypal/wallet` + `plugins/vaults/purse/creditcard`) | The `PartnerUiPlugin` lifecycle contract + `PluginRegistry`, the shared redirect machinery (`.redirect` — `RedirectionResolver` + the in-app-WebView return Activity, used by every plugin), **and** the Google Pay, Klarna, PayPal, Purse vault credit-card and generic redirection plugins living alongside it in the same module — exactly like web bundles every partner plugin inside its one `partner-ui` package. Each reaches `:headless-checkout` only through the `GooglePayHost`/`KlarnaHost`/`PaypalHost`/`CardHost`/`RedirectionHost` interface it defines, so the dependency never points from `:partner-ui` to `:headless-checkout`. |

Dependency direction: `:app → { :headless-checkout, :partner-ui }`, `:headless-checkout → { :core,
:partner-ui }`, `:partner-ui → :core`.

Full docs: **[docs/README.md](docs/README.md)** · Architecture:
**[docs/architecture/headless-checkout.md](docs/architecture/headless-checkout.md)**.

## Using the SDK

```kotlin
// 1. Decode the merchant backend's base64 widget.data (V2 params) into a headless checkout.
val checkout = PurseHeadlessCheckout.init(widgetData)          // pass PurseEnvironment.PRODUCTION to lock

// 2. Inspect the session reactively (StateFlow).
checkout.paymentMethods.value        // primaries + secondaries, with live disabled states
checkout.remainingAmountToPay.value  // what a primary still owes
checkout.isPaymentFulfilled.value  // split covered AND every form in it complete and valid

// 3. Build the split: draw down secondaries (see below) and/or choose a primary.
checkout.setPrimary(cardMethod)

// 4. Create the payment (suspend; off the main thread). Needs a registered :partner-ui plugin.
val result = checkout.submitPayment()
```

### Secondaries (co-payment)

Gift cards, vouchers and loyalty cards are handled directly by the SDK (no `:partner-ui` plugin).
Reading a balance and applying it to the split are **separate** steps — call `getSecondaryToken`
alone if you only want the balance.

```kotlin
// The co-payment methods the session offers.
val giftCard: SecondaryMethod = checkout.secondaryMethods.first()

// Client-side rules for the manual card form (null when the method has no registered config).
val cfg = checkout.secondaryConfig(giftCard)
cfg?.requiresCvv(pan)   // whether to collect a CVV
cfg?.validatePan(pan)   // PAN format check
cfg?.validateCvv(cvv)   // CVV format check

// 1. Tokenize to read the BALANCE — POSTs /init, does NOT touch the split.
//    suspend: call off the main thread. Stop here if all you want is the balance.
val token: SecondaryToken = checkout.getSecondaryToken(giftCard, pan, cvv)
token.balance          // spendable now
token.totalBalance     // full balance (differs for loyalty)

// 2. Apply it to the split. `amount` may be any value up to the balance; omit it to take as much
//    as the order still needs. Non-splittable tokens (takePolicy == MAX) ignore `amount`.
checkout.applySecondary(token, amount = BigDecimal("10.00"))
checkout.removeSecondary(token)      // undo

// Reactive state reflects the split after each change.
checkout.appliedSecondaries          // List<SecondaryToken>
checkout.remainingAmountToPay.value  // what's left for a primary
checkout.amountSplit.value           // per-source slices
```

At `submitPayment()` each applied token builds its `/validate` payload from its `SecondaryConfig`.

**Errors** from `getSecondaryToken`: `PurseCheckoutException.NoSecondaryConfig` (method has no
config), `InvalidSecondaryCard` (PAN/CVV fails validation), `SecondaryTokenizationFailed`
(unsuccessful `/init` or zero balance), `PaymentApiException` (network).

**Supported methods** (`SecondaryConfigs`, keyed `"{partner}-{method}"`): `illicado-giftcard`,
`buybox-giftcard`, `maxxing-loyaltycard`, `ogloba-giftcard`, `mybeezbox-giftcard`,
`easy2play-giftcard`, `easy2play-voucher`. `ancv-connect` is deferred (two-phase polling).

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
