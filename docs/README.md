# Documentation

A headless Android SDK for Purse payments: it owns the payment engine (session, methods, split,
`/validate`) and lets you build the checkout UI. Same concepts as the web `usp-widget` headless
SDK, in Kotlin and Compose.

## Integrate

| | |
|---|---|
| **[Getting started](integration/getting-started.md)** | Install from Maven Central, create the checkout, render a method, pay, confirm. Read this first. |
| [Recipes](integration/recipes.md) | One task per section: each payment method, gift cards, saved cards, hooks, theme, errors, session refresh, BNPL. |
| [API reference](integration/consumer-api.md) | Every function, flow, type and error. The generated KDoc reference ships with each [release](https://github.com/UpStreamPay/purse-headless-checkout-android/releases). |
| [Payment methods by platform](payment-methods-matrix.md) | What this SDK supports versus web. |
| [Monitoring and analytics](integration/monitoring.md) | What the SDK reports to Purse (events, envelope, levels) and what never leaves the device. |

## Work on the SDK

| | |
|---|---|
| [Architecture](architecture/headless-checkout.md) | Modules, the purse-payment flow, plugin lifecycle. |
| [Plugins](plugins/README.md) | Per PSP: `/init` contract, sequence, quirks, failure modes — [Google Pay](plugins/google-pay.md) · [Klarna](plugins/klarna.md) · [PayPal](plugins/paypal.md) · [Card](plugins/card-purse-vault.md) · [Redirection](plugins/redirection.md). |
| [Web ↔ Android map](web-android-map.md) | The web file behind each Android file, and every deliberate divergence. |
| [Release](contributing/release.md) · [CI and gates](contributing/ci.md) | Cut a release (push to `main`); what blocks a merge and how to refresh an API dump. |
| [Distribution](decisions/distribution.md) · [R8](decisions/r8-proguard.md) · [16 KB](decisions/16kb-alignment.md) | Maven Central, one engine artifact + optional partner artifacts; consumer rules; the native-library audit. |
| [Simplification review](decisions/simplification-review.md) · [Pain points](decisions/pain-points-recap.md) | Decisions and their reasons. |
| [Testing with alfred](internal/testing-with-alfred.md) | Run the demo against real sessions. |

```
:app                 demo (Compose) — takes every artifact below
:headless-checkout   the SDK: eu.purse:headless-checkout-android            ← depend on this
  eu.purse.checkout    PurseHeadlessCheckout, payment elements
  eu.purse.partnerui   plugin contract, redirect, card + redirection plugins
  eu.purse.core        Payment API client, session decode, shared types
:partner-googlepay   Google Pay plugin (optional)   eu.purse:headless-checkout-googlepay
:partner-klarna      Klarna plugin (optional)       eu.purse:headless-checkout-klarna
:partner-paypal      PayPal plugin (optional)       eu.purse:headless-checkout-paypal
```

Requirements: minSdk 26, Jetpack Compose. Related tickets: [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039), [SDK-12216](https://upstreampay.atlassian.net/browse/SDK-12216), [SDK-12218](https://upstreampay.atlassian.net/browse/SDK-12218), [SDK-12219](https://upstreampay.atlassian.net/browse/SDK-12219), [SDK-12220](https://upstreampay.atlassian.net/browse/SDK-12220).
