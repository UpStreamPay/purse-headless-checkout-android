# Documentation

A headless Android SDK for Purse payments: it owns the payment engine (session, methods, split,
`/validate`) and lets you build the checkout UI. Same concepts as the web `usp-widget` headless
SDK, in Kotlin and Compose.

## Integrate

| | |
|---|---|
| **[Getting started](integration/getting-started.md)** | Install, create the checkout, render a method, pay, confirm. Read this first. |
| [Recipes](integration/recipes.md) | One task per section: each payment method, gift cards, saved cards, hooks, theme, errors, session refresh, BNPL. |
| [API reference](integration/consumer-api.md) | Every function, flow, type and error. |
| [Payment methods by platform](payment-methods-matrix.md) | What this SDK supports versus web. |

## Work on the SDK

| | |
|---|---|
| [Architecture](architecture/headless-checkout.md) | Modules, the purse-payment flow, plugin lifecycle. |
| [Plugins](plugins/README.md) | Per PSP: `/init` contract, sequence, quirks, failure modes — [Google Pay](plugins/google-pay.md) · [Klarna](plugins/klarna.md) · [PayPal](plugins/paypal.md) · [Card](plugins/card-purse-vault.md) · [Redirection](plugins/redirection.md). |
| [Web ↔ Android map](web-android-map.md) | The web file behind each Android file, and every deliberate divergence. |
| [Simplification review](decisions/simplification-review.md) · [Pain points](decisions/pain-points-recap.md) | Decisions and their reasons. |
| [Testing with alfred](internal/testing-with-alfred.md) | Run the demo against real sessions. |

```
:app               demo (Compose)
:headless-checkout PurseHeadlessCheckout, payment elements          ← depend on this
  ├── :partner-ui  plugins + coordinators (card, Google Pay, PayPal, Klarna, redirection)
  └── :core        Payment API client, session decode, shared types
```

Requirements: minSdk 26, Jetpack Compose. Related tickets: [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039), [SDK-12216](https://upstreampay.atlassian.net/browse/SDK-12216).
