# Purse Android Headless Checkout — Documentation

A **headless** Android SDK for Purse payments: it owns the concept of a purse payment (session,
payment methods, splits, and the plugin-driven validate pipeline) and lets a merchant build their
own checkout UI on top. It mirrors the layered design of the web `usp-widget`
(`sdk`/`model`/`common` → `headless-checkout`, with `partner-ui` plugins), adapted to Android.

> **Status: back-to-basics rebuild in progress.** The core purse-payment engine is in place —
> session (V2) decode, the payment model, the splitter, the reactive `StateFlow` surface, and the
> "create payment" pipeline — and builds green with unit tests. Three interactive plugins ship in
> `:partner-ui` and register themselves: **Google Pay**, **Klarna** (installments) and the
> **Purse-vaulted card** (SecureFields), plus the shared 3-D-Secure redirect Activity. PayPal is not
> ported yet.

## Module layout

```
:app              eu.purse.checkout.demo   demo host, Compose: session → pay → result
   │
:headless-checkout eu.purse.checkout       PurseHeadlessCheckout: session, model, splitter, validate
   ├── :core        eu.purse.core          Payment API client, enums, LegacyPaymentSession, decode
   └── :partner-ui  eu.purse.partnerui     PartnerUiPlugin lifecycle contract (no implementations)
```

## Start here

| If you want to… | Read |
|---|---|
| Understand the architecture, modules, and purse-payment flow | **[Headless checkout architecture](architecture/headless-checkout.md)** |
| Integrate the SDK — the full consumer API (functions, events, callbacks) | **[Consumer API reference](integration/consumer-api.md)** |
| Know where this diverges from the web headless SDK | **[Pain points recap](decisions/pain-points-recap.md)** |
| Run the demo app against a real session | **[Testing with alfred](internal/testing-with-alfred.md)** |

## Requirements

| | |
|---|---|
| Minimum SDK | API 26 (Android 8.0) |
| Reference implementation | `../usp-widget` (`@upstreampay/headless-checkout`, `@upstreampay/partner-ui`, `@upstreampay/sdk`) |
| Related tickets | [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039) |
