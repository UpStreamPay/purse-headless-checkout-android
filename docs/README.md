# Purse Android Headless Checkout — Documentation

A **headless** Android SDK for Purse payments: it owns the concept of a purse payment (session,
payment methods, splits, and the plugin-driven validate pipeline) and lets a merchant build their
own checkout UI on top. It mirrors the layered design of the web `usp-widget`
(`sdk`/`model`/`common` → `headless-checkout`, with `partner-ui` plugins), adapted to Android.

> **Status: back-to-basics rebuild in progress.** The core purse-payment engine is in place —
> session (V2) decode, the payment model, the splitter, the reactive `StateFlow` surface, and the
> "create payment" pipeline — and builds green with unit tests. Partner plugins are **contract-only**
> (no PayPal / Google Pay / card / redirect implementations yet), so `submitPayment` wires the
> pipeline but has nothing to submit through until a plugin is registered.

## Module layout

```
:app              eu.purse.checkout.demo   demo host (alfred session → smoke test)
   │
:headless-checkout eu.purse.checkout       PurseHeadlessCheckout: session, model, splitter, validate
   ├── :core        eu.purse.core          Payment API client, enums, LegacyPaymentSession, decode
   └── :partner-ui  eu.purse.partnerui     PartnerUiPlugin lifecycle contract (no implementations)
```

## Start here

| If you want to… | Read |
|---|---|
| Understand the architecture, modules, and purse-payment flow | **[Headless checkout architecture](architecture/headless-checkout.md)** |
| Run the demo app against a real session | **[Testing with alfred](internal/testing-with-alfred.md)** |

## Requirements

| | |
|---|---|
| Minimum SDK | API 26 (Android 8.0) |
| Reference implementation | `../usp-widget` (`@upstreampay/headless-checkout`, `@upstreampay/partner-ui`, `@upstreampay/sdk`) |
| Related tickets | [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039) |
