# Redirect Handling

Answers the open question listed verbatim in
[SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039): **"comment gérer la
redirection (intent)"** — how to handle a payment redirect (PayPal approval, a 3DS challenge, or
any future redirect-based method) using an Android Intent.

> **What's confirmed vs. open.** The redirect *contract shape* below (§ [The redirect
> contract](#the-redirect-contract)) is taken directly from the web reference implementation and
> is safe to build against. What's **not yet confirmed** is whether the backend can hand the
> Android app a redirect target that returns to the app (an App Link / custom scheme) instead of
> a web `success`/`failure` URL — see [What needs backend
> confirmation](#what-needs-backend-confirmation). Until that's answered, treat the PayPal flow
> below as the POC's best proposal, not a settled design.

---

## Table of Contents

- [Why this needs its own design](#why-this-needs-its-own-design)
- [What "no redirect" means for a mobile SDK](#what-no-redirect-means-for-a-mobile-sdk)
- [The redirect contract](#the-redirect-contract)
- [The session-level fallback and the error path](#the-session-level-fallback-and-the-error-path)
- [Android translation](#android-translation)
- [Proposed PayPal flow for this POC](#proposed-paypal-flow-for-this-poc)
- [The return path](#the-return-path)
- [What needs backend confirmation](#what-needs-backend-confirmation)
- [Why not just embed PayPal's JS SDK in a WebView](#why-not-just-embed-paypals-js-sdk-in-a-webview)
- [See also](#see-also)

---

## Why this needs its own design

On web, PayPal today is handled with the **PayPal JS SDK's own popup button** — the shopper never
leaves the merchant's page, so no redirect/return contract is needed at all (see
[Payment Methods — PayPal](../integration/payment-methods.md#paypal-starter) for the full flow).
There is no full-page-redirect PayPal implementation in `usp-widget` to copy. A generic
redirect mechanism *does* exist in the web codebase (used for 3DS bank pages and any
`should_submit_widget` fallback) — that's what this doc adapts for Android, since Android has no
equivalent of "open a popup on the same page."

---

## What "no redirect" means for a mobile SDK

Worth stating explicitly, since it's easy to over-scope this whole doc: **most payments need
none of this.** Per Purse's own [simple-payment
tutorial](https://docs.purse.tech/docs/integrate/purse-api/tutorials/simple-payment), a redirect
back to `shopper_redirection_url` exists because the shopper is inside a **browser page** that has
to navigate to *something* when checkout ends — that's a structural property of a web/hosted-page
integration, not a property of "how a payment resolves." Web's own fallback
(`session.success`/`session.redirection`, see [below](#the-session-level-fallback-and-the-error-path))
is the same idea: even a payment that never needed an external redirect still has to land the
browser tab somewhere.

A native app has no browser tab to land. When `/validate` resolves without requiring the shopper to
go anywhere externally — no `redirection.url`, no partner-owned approval step still pending — the
Drop-in's job is simply to **return the result to the host app** (a callback, a return value, a
UI update) and stop. That's already what this repo's `capturePaypal`/`captureGooglePay`/
`captureCard` do in `DropinActivity`: on success or a plain decline, they just report the outcome —
there's no attempt to "redirect to nowhere." Nothing below this point needs to run at all for CB
without 3DS, Illicado, or Google Pay/PayPal without a chained challenge.

The rest of this doc — Custom Tabs, WebView, the return-path deep link — is scoped to the
**minority** of cases where the shopper genuinely has to interact with something outside the app:
PayPal's own approval step, and a chained 3DS/SCA challenge on any method. Read the sections below
as "what to do when there actually is somewhere to go," not "what every payment has to do."

---

## The redirect contract

From `/validate`'s response (`ValidationResultData` in
`usp-widget/packages/sdk/src/API/types.ts`):

```json
{
  "results": [
    {
      "plugin_result": {
        "logs": {
          "redirection_url": "https://…",
          "redirection_json": "{\"field\":\"value\"}",
          "redirect_url_post_params": "field=value&other=value"
        }
      }
    }
  ],
  "redirection": {
    "should_submit_widget": false,
    "url": "https://…",
    "form": "<form>…</form>",
    "name": "…"
  },
  "global_status": "PENDING"
}
```

The web resolver (`manageRedirection` in `usp-widget/packages/sdk/src/redirection/redirection-manager.ts`)
checks these in a fixed priority order:

1. **Per-item POST form** — `plugin_result.logs.redirection_json` + `redirection_url` present →
   POST `redirection_json` as form data to `redirection_url`.
2. **Global URL redirect** — `redirection.url` present → GET, or POST
   `redirect_url_post_params` if also present.
3. **Raw HTML form string** — `redirection.form` present → inject and auto-submit that literal
   `<form>` HTML.
4. **Fallback** — `redirection.should_submit_widget` and a fallback URL → GET the fallback.

If none match, it's an error — there was no redirect to perform.

**This resolver is invoked per-method, not globally.** Each *primary* method in the current
payment split owns its own `postValidation` step and decides for itself whether/how to redirect —
e.g. PayPal wallet's `postValidation` just navigates to `session.success` directly, without ever
calling `manageRedirection` (see [Payment Methods —
PayPal](../integration/payment-methods.md#paypal-starter)); a 3DS-requiring card partner's
`postValidation` would call `manageRedirection` per the priority list above. Android should own
this the same way: whichever method coordinator submitted the payment is responsible for handling
its own `/validate` response, not a shared, method-agnostic redirect step.

---

## The session-level fallback and the error path

There's one more layer web falls back to, in `usp-widget/packages/headless-checkout/src/headless-checkout-validation.ts`,
**only reached when no primary method is involved at all** (e.g. a payment made entirely of
secondary methods — a pure Illicado payment, in this POC's terms — where no partner UI exists to
own a `postValidation` step), or when the `/validate` response's `results` array comes back empty:

```ts
const redirectionUrl = data.redirection?.url ?? sessionRedirectionUrl;   // sessionRedirectionUrl = session.redirection
if (redirectionUrl) {
  const redirectionForm = data.redirection?.form;
  redirectionHandler.submitRedirectionForm(redirectionUrl, redirectionForm ?? '', redirectionForm ? 'POST' : 'GET');
}
```

`sessionRedirectionUrl` is `session.source.redirection` — the top-level `redirection` field
described in [Session & Protocols](session-and-protocols.md#full-legacypaymentsession-shape). So
the real fallback order at this layer is: **`/validate` response's `redirection.url` → the
session's own `redirection` field → nothing (silently do nothing)**.

**On a `/validate` call that errors outright** (rather than returning a redirect requirement),
web dispatches the same fallback pair differently — a **plain top-level navigation**
(`window.location.href = url`), not `submitRedirectionForm` — again because a browser page always
has to land somewhere, even on error. That reasoning doesn't transfer to Android: per [What "no
redirect" means for a mobile SDK](#what-no-redirect-means-for-a-mobile-sdk), an error with no
`redirection.url` and no partner-specific approval step pending is just an error to report to the
host app, not something requiring a Custom Tabs/browser launch. Only open a Custom Tab when there's
an actual URL that resolves to a real next step for the shopper.

For this POC: Illicado (this POC's only secondary-only-payment case) has no known redirect step of
its own (see [Payment Methods — Illicado](../integration/payment-methods.md#illicado-starter)), so
this fallback layer is a defensive corner case, not something Illicado is expected to trigger.

---

## Android translation

| Web mechanism | What it does | Android equivalent |
|---|---|---|
| `redirectToUrl` (`window.open(url, '_self')`) | Full-page GET navigation | **Custom Tabs** (`CustomTabsIntent`) — GET only, cannot carry a POST body |
| `submitRedirectionForm` (hidden `<form>` + `.submit()`) | POST navigation with form fields | **WebView**, loading the URL via `postUrl()` with the form fields encoded as the POST body — Custom Tabs cannot POST |
| `submitStringRedirectionForm` (inject HTML string, auto-submit) | Same as above, but the form HTML comes from the server | **WebView**, via `loadDataWithBaseURL()` on the provided HTML, letting the page's own `<form>.submit()` (or `onload` auto-submit script) fire the POST |

> **Update — implemented.** The interface below was this doc's original proposal; the actual
> implementation is `eu.purse.dropin.partnerui.redirect.RedirectionResolver` (`:partner-ui`),
> which resolves a `/validate` response into a sealed `RedirectAction` (`Get`/`PostForm`/
> `PostHtml`) using the exact same priority order as web's `manageRedirection`, and
> `eu.purse.dropin.partnerui.generic.redirection.RedirectionCheckoutActivity`/
> `RedirectionCheckoutContract`, which dispatch a `RedirectAction` to Custom Tabs or a WebView and
> return once the shopper comes back via this app's own `${applicationId}.redirect` deep link
> (distinct from PayPal's own `${applicationId}` scheme — see [Partner:
> paypal](../partners/paypal.md) for why PayPal needs its own). Currently wired into Google Pay's
> capture flow (`DropinActivity.captureGooglePay`) — see [Partner:
> adyen](../partners/adyen.md#sequence-diagram). Reusing it for PayPal's own chained-3DS case, CB's
> future chained 3DS, and a `integration_mode: "redirection"` method (see
> docs/decisions/pain-points-recap.md, Pain point 3) is call-site wiring, not a redesign.

Proposed Kotlin interface, mirroring `RedirectionHandler` in
`usp-widget/packages/common/src/Redirection.ts` — kept here for the historical mapping from web's
naming to Android's, though the actual shipped type is `RedirectAction` above, not this interface:

```kotlin
interface DropinRedirectionHandler {
    fun redirectToUrl(url: String)                                  // → Custom Tabs
    fun submitRedirectionForm(url: String, formDataUrlEncoded: String) // → WebView POST
    fun submitHtmlForm(html: String)                                 // → WebView loadDataWithBaseURL
}
```

Same priority order as the web resolver: check `plugin_result.logs` first, then
`redirection.url`/`form`, then the `should_submit_widget` fallback.

---

## Proposed PayPal flow for this POC

> **Update — implemented.** The wallet flow below is now built in `:partner-ui` using the PayPal
> Android SDK's own browser-switch (`PayPalWebCheckoutClient`) rather than a hand-rolled Custom Tab,
> so steps 3–5 are handled by the SDK; steps 2 (`/init`) and 6 (`/validate`) are as described. See
> [Partner: paypal](../partners/paypal.md#implemented-android). The backend `return_url` question in
> [What needs backend confirmation](#what-needs-backend-confirmation) still stands.

PayPal's web flow (see [Payment Methods](../integration/payment-methods.md#paypal-starter))
resolves an `order_id` via `/init` and then has the PayPal JS SDK collect approval in a popup —
Android has no popup equivalent, so this POC proposes a full redirect instead:

1. Shopper selects PayPal in the method list.
2. Call `POST /init` with `partner: "paypal", method: "wallet"` — same request shape as web.
   Response includes `plugin_result.logs.order_id`.
3. Open PayPal's standard hosted approval page in a **Custom Tab**:
   `https://www.paypal.com/checkoutnow?token={order_id}` (sandbox equivalent for non-production
   sessions). This is PayPal's classic redirect-based Checkout entry point, independent of the JS
   SDK — it's what the JS SDK's popup itself navigates to internally.
4. The shopper approves (or cancels) on PayPal's page.
5. PayPal redirects to whatever return URL was registered for the order at creation time. **This
   is the unconfirmed part** — see below.
6. On return, call `POST /validate` with `partner: "paypal", method: "wallet"` — same request
   shape as web's `postApproval` step.
7. Handle the `/validate` response's `redirection` object per the [contract
   above](#the-redirect-contract) in case a further step (e.g. 3DS) is chained.

---

## The return path

Web's `success`/`failure` URLs (`PaymentSession.success` / `.failure`) are plain web pages the
merchant's backend serves — Purse does not append a fixed query-parameter contract to them.
Whatever lands on `success`/`failure` is expected to re-query the session/payment status
server-side rather than trust query parameters.

On Android there's no page to land on — the return needs to come back into the app. The standard
pattern:

- Register an **Android App Link** (preferred) or custom URI scheme (e.g.
  `eu.purse.dropin://paypal-return`) as an `<intent-filter>` on an Activity in the demo app's
  manifest.
- That URL becomes what's passed to PayPal/the backend as the return target for this order,
  instead of a web `success` URL.
- The Activity's `onNewIntent`/launch `Intent` carries whatever query parameters PayPal appends
  (typically `token`/`PayerID` for classic Checkout) — treat them as a hint to re-fetch status,
  not as the source of truth, matching the web pattern of re-querying rather than trusting
  redirect params.
- Close the Custom Tab (or let the system do it once the deep link fires) and resume the Drop-in
  UI, then call `/validate`.

---

## What needs backend confirmation

The web reference implementation never needed an app-returning redirect target — its PayPal flow
never leaves the page — so there is **no precedent in `usp-widget` for "the backend accepts an app
deep link as a redirect/return target."** Before this flow can move past "starter" depth, confirm
with the backend/PSP integration team:

1. Whether `/init` for `partner: "paypal"` can accept (or already accepts) an app deep link /
   universal link as the PayPal order's return/cancel URL, or whether that's fixed to a
   merchant-configured web URL at session-creation time.
2. Whether `settings.merchant_specifics.integration_mode: "redirection"` (present in
   `MethodConfiguration` today, but currently only read by web to gate BNPL eligibility — see
   [Payment Methods](../integration/payment-methods.md#paypal-starter)) is meant to change what
   `/init` returns, e.g. surface an explicit approval URL rather than requiring the client to
   construct `checkoutnow?token=...` itself.
3. Whether the alternative — using PayPal's own official Android SDK (Braintree / PayPal Native
   Checkout, which handles the approval UI and app-return natively without a custom deep link) —
   is preferred over the generic-redirect approach above. That SDK still calls the same Purse
   `/init` (to get `order_id`) and `/validate` (to capture) — it only replaces step 3 above (how
   approval UI is shown) — but is a different dependency/integration decision than "reuse the
   generic redirect contract," so it's worth deciding explicitly rather than defaulting into it.

Until answered, the Custom Tabs + deep link design above is this POC's working proposal, matching
what the Jira ticket's own open question calls out as unresolved.

---

## Why not just embed PayPal's JS SDK in a WebView

Loading the existing PayPal JS SDK inside an Android `WebView` would technically work and reuse
the exact web flow, but was not chosen as the starting proposal because it reintroduces the
problems native Drop-ins exist to avoid: no native look-and-feel for the button, WebView
JS-bridge complexity, and it sidesteps rather than answers the ticket's actual question (how to
handle a *redirect*, not how to avoid needing one). It remains a fallback if the deep-link-based
flow above turns out to be blocked on backend changes.

---

## See also

- [Payment Methods — PayPal](../integration/payment-methods.md#paypal-starter)
- [Session & Protocols](session-and-protocols.md)
- [POC Scope](../decisions/poc-scope.md)
