# Purse Android Drop-in — Documentation

This repository is the POC tracked as [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039):
a **native Android Drop-in** that initializes a Purse payment session and lets a shopper pay with
Carte Bancaire (CB), Google Pay, PayPal, or Illicado, entirely with native UI — no WebView-hosted
checkout page.

> **Status: early POC, in progress.** The `:core`, `:partner-ui`, and `:dropin` SDK modules
> (session decode + method model + payment splitter in `:dropin`; PayPal wallet and the generic
> Google Pay flow in `:partner-ui`; the Payment API client + environment in `:core`) and a working
> `:app` demo exist. PayPal (wallet) pays end to end via the PayPal Android SDK, and Google Pay
> pays end to end via Google's own Google Pay API (`PaymentsClient`); some pieces (card
> `/validate`, generic redirect/3-D Secure) are still design-only. Each page states what's
> confirmed vs. still open, and calls out what's implemented. See [POC scope](decisions/poc-scope.md)
> for what "done" means for this ticket, and [Project Setup](internal/project-setup.md#still-to-do)
> for the current build state.

The Drop-in mirrors the layered design of the existing web widget
(`@upstreampay/dropin` → `@upstreampay/headless-checkout` → PSP plugins, in the sibling
`usp-widget` repo), adapted to Android:

```
Dropin UI (Compose)         ← payment-method list, forms, pay button
     │
Session / protocols model    ← decodes the session, exposes payment methods, calls /init + /validate
     │
     ├── SecureFields Android SDK (Maven, eu.purse:securefields-android) → CB
     ├── Google Pay API (PaymentsClient)                                 → Google Pay
     ├── Custom Tabs / WebView redirect                                  → PayPal
     └── Native PAN + CVV form                                           → Illicado
```

The primary goal of this POC is validating the **first row** — using the already-published
SecureFields Android SDK for card capture and tokenization inside a native Drop-in — with
PayPal and Illicado built to a "starter" depth sufficient to exercise the acceptance criteria,
not production hardening.

---

## I want to understand what this POC covers

**[POC scope](decisions/poc-scope.md)** — acceptance criteria from SDK-12039, which payment
methods are "full" vs. "starter" depth, and what's explicitly out of scope.

---

## I want to integrate SecureFields for card payments

**[Getting Started](integration/getting-started.md)** — add the `securefields-android` Maven
dependency, decode a Purse session, and initialize SecureFields for the `creditcard` method.

---

## I want to understand how payment methods are selected and submitted

**[Payment Methods](integration/payment-methods.md)** — how CB, Google Pay, PayPal, and Illicado
each map to the session's `protocols` object, and how each one is captured, tokenized/authorized,
and submitted.

---

## I want to understand the session and API model

**[Session & Protocols](architecture/session-and-protocols.md)** — the `widget.data` payload,
the `protocols` map (partner → method → configuration), the Payment API's `/init` and `/validate`
endpoints, and the three layers of status/lifecycle state.

---

## I want a sequence diagram for each payment method

**[Payment Flow Diagrams](architecture/payment-flows.md)** — a Mermaid sequence diagram per
handled method (CB, Google Pay, PayPal, Illicado), showing what's implemented today vs. what a
redirect/3DS branch would still need.

---

## I need to handle PayPal or 3DS redirects

**[Redirect Handling](architecture/redirect-handling.md)** — answers the open question from
SDK-12039 ("comment gérer la redirection (intent)"): the web's generic redirect contract, and
the Android-native translation (Custom Tabs vs. WebView) with what still needs backend
confirmation.

---

## I want the recap of pain points and what's still genuinely unresolved

**[Pain Points Recap](decisions/pain-points-recap.md)** — a consolidated retro on what's actually
hard about a native mobile Drop-in vs. the web reference, including questioning this POC's own
proposed approach, and the concrete list of what the Payment API team needs to confirm before
`/validate` + redirects can move past "starter" depth.

---

## I want to know which PSPs/partners are involved and how each redirects

**[Partners](partners/README.md)** — one page per partner behind this POC's four methods
(`purse`, `adyen`, `paypal`, `illicado`), each with a link to that partner's own official
documentation (where one exists) and a study of what a redirect-based flow means specifically for
that partner — some already redirect today, some never do, and the reasons differ per partner.

---

## I'm setting up the project from scratch

**[Project Setup](internal/project-setup.md)** *(internal — contributor onboarding)* — the
`:core` / `:partner-ui` / `:dropin` / `:app` module split, Gradle wiring, and domain-shaped package layout.

---

## I want to run the demo app against a real session

**[Testing with alfred](internal/testing-with-alfred.md)** *(internal)* — run the `usp-widget`
alfred dev server, point the app at it via a `.env` (`ALFRED_BASE_URL=http://10.0.2.2:9001` on an
emulator), and mint a real Purse session to exercise the method list.

---

## Requirements

| | |
|---|---|
| Minimum SDK | API 26 (Android 8.0) — matches SecureFields Android SDK's `minSdk` |
| SecureFields Android SDK | `eu.purse:securefields-android` (Maven Central) |
| Reference implementation | `../usp-widget` (`@upstreampay/dropin`, `@upstreampay/headless-checkout`) |
| Related tickets | [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039) (this POC), [SDK-11769](https://upstreampay.atlassian.net/browse/SDK-11769) (SecureFields Android SDK), [SDK-7005](https://upstreampay.atlassian.net/browse/SDK-7005) (prior Android widget SDK, done) |
