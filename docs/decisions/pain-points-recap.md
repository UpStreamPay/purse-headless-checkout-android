# Pain Points Recap — Native Mobile Drop-in vs. the Web Reference

Concise retro on [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039): what's actually
hard about a **native** Android Drop-in vs. `@upstreampay/dropin`/`@upstreampay/headless-checkout`
(`../usp-widget`), and what the Payment API needs to clarify before `/validate` + redirects can go
past "starter." Backing research was thorough; this recap deliberately isn't — see [Redirect
Handling](../architecture/redirect-handling.md), [POC Scope](poc-scope.md), and [Payment Flow
Diagrams](../architecture/payment-flows.md) (a sequence diagram per method) for the full detail
behind each point.

**TL;DR:** card capture, Google Pay's sheet, and PayPal's approval all have solid native answers
today. What's genuinely hard — and what SDK-12039 actually asks about ("comment gérer la
redirection") — is *after* `/validate`, because web's own redirect mechanism is a rarely-hit
defensive fallback, not something it had to get right, and its `/validate` contract leaves
"is a redirect required" to client-side inference rather than an explicit field.

---

## Pain points

1. **No web precedent for an app-returning redirect.** PayPal on web never leaves the page (JS SDK
   popup); web's generic `redirectToUrl`/`submitRedirectionForm` mechanism exists only for 3DS bank
   pages and a defensive fallback. Android's core question — something has to leave the app and
   come back — is new design, not a port. This is the ticket's one open question and the biggest
   unknown here.

2. **`global_status` doesn't clearly say "a redirect is required" — likely the sharpest finding.**
   `ValidationResultData.global_status` is typed `SUCCESS | ERROR | UNCERTAIN | FAILED | WAITING`
   (`usp-widget/packages/sdk/src/API/types.ts:39-47,153`) — there is no `PENDING`, despite
   [Redirect Handling](../architecture/redirect-handling.md#the-redirect-contract)'s example JSON
   showing one (should read `WAITING` or `SUCCESS`). Worse: web's own `executePayment` only runs
   the redirect resolver (`postValidateItems`) **when `global_status === "SUCCESS"`**
   (`headless-checkout-validation.ts:~266`); anything else falls into a plain-navigation error path
   with no POST support. So a 3DS step-up only gets a real redirect if the backend reports
   `SUCCESS` alongside `redirection` — if it reports `WAITING` instead (the more obvious choice),
   neither web's code nor a literal Android port of it would ever follow it. Unverified against a
   real response — see point 8.

3. **The redirect contract is inferred, not declared.** `manageRedirection` checks four optional
   fields in a fixed priority order (`redirection_json`+`redirection_url` → `redirection.url` →
   `redirection.form` → `should_submit_widget` fallback) and throws if none match
   (`usp-widget/packages/sdk/src/redirection/redirection-manager.ts:14-38`). "No redirect needed"
   and "a required field is missing" look the same to a client. A single discriminated field would
   remove the guesswork (see asks below).

4. **PayPal's `return_url` is a backend order-creation decision, not a client one.** PayPal's own
   Android SDK docs are clear that an App Link or matching custom scheme is required
   ([Integrate Card Payments in Android Apps with PayPal
   SDK](https://developer.paypal.com/docs/checkout/advanced/android/); [App
   Switch](https://developer.paypal.com/docs/checkout/standard/customize/app-switch/api-one-time-payments/)).
   What's unconfirmed is whether Purse's backend actually threads an app-scheme return target
   through when it creates the PayPal order server-side, vs. always registering the merchant's web
   success URL — `PaypalCheckoutActivity.kt` already does its half (client-side scheme = app
   package name), which is necessary but not sufficient.

5. **Custom-scheme deep links aren't a verified return path.** Unlike App Links (HTTPS + [Digital
   Asset Links](https://developer.android.com/training/app-links/verify-android-applinks)), a
   custom scheme has no Android-enforced ownership check — see Android's own [Unsafe use of deep
   links](https://developer.android.com/privacy-and-security/risks/unsafe-use-of-deeplinks) and
   [deep link hijacking](https://oversecured.com/blog/android-deep-link-vulnerabilities). Scoping
   the scheme to `${applicationId}` (this repo's current approach) reduces collision risk but isn't
   a verified link. Low blast radius today (PayPal's return only carries an opaque order id,
   re-validated server-side) but unevaluated for a generic 3DS redirect that might carry more.
   **Update:** Purse already has a building block for this — v2's JWKS-signed
   `purse-redirection-data` (point 10) — so a mobile redirect return shouldn't need bespoke
   deep-link security invented from scratch; the question is whether that same signed-token
   mechanism gets extended to an app-return deep link, not whether one needs to be designed.

6. **Custom Tabs can't POST.** Any `redirection_json`/`form`/POST case needs a WebView
   (`postUrl()`/`loadDataWithBaseURL()`), which reintroduces the WebView-bridge/no-browser-chrome
   problems native was meant to avoid — see [Why not just embed PayPal's JS SDK in a
   WebView](../architecture/redirect-handling.md#why-not-just-embed-paypals-js-sdk-in-a-webview).
   Not hypothetical: Google Pay's 3DS step-up (point 7) is a real, already-observed trigger, and
   Adyen's own Android guidance for it uses a native
   [`RedirectComponent`](https://docs.adyen.com/online-payments/3d-secure/redirect-3ds2/android-component),
   not a hand-rolled WebView. **Update:** the CB/`purse`-vault side of this is less open than it
   reads above — SecureFields Android is getting its own dedicated 3DS module, so CB won't need a
   hand-rolled Custom Tabs/WebView fallback either. That leaves the generic handler as something
   only Illicado (and any future redirect-based partner with no native SDK of its own) would
   actually fall back to, not CB.

7. **Google Pay's redirect is confirmed, not hypothetical, and the least well-handled today.**
   `plugins/generics/googlepay/index.ts` (~line 108) calls `manageRedirection` unconditionally
   after every `/validate` — a real production 3DS trigger, per [Partner: adyen — Redirection
   study](../partners/adyen.md#redirection-study). Android only surfaces
   `GooglePayValidationResult.redirectionUrl` today; nothing follows it.

8. **Nothing here has been exercised against a real redirect-requiring `/validate` response.**
   Points 2 and 3 are derived from reading source (both the web widget's and, now, the public API
   docs), not from a live sandbox response — the v1 `/validate` response body remains undocumented
   even in the official spec (point 10). No 3DS step-up or PayPal sandbox return has been driven
   end-to-end on a device yet.

9. ~~The CB vault-token payload shape had zero web precedent~~ — **resolved**: it was never actually
   missing, just unchecked against the right file. `usp-widget/packages/partner-ui/src/plugins/vaults/purse/creditcard/{bridge,utils}.ts`
   (`purseCreditCardBridge.submitForResult` + `handleExternal3ds`) shows the real shape —
   `payment_data.vault_vendor_token_id` as a **top-level field**, not nested under `card` as this
   POC originally assumed — now ported in `CardMapper` (see [Payment Methods —
   CB](../integration/payment-methods.md#cb-full)). Remaining smaller gap: Illicado still has no
   public docs and no known redirect trigger *or* known absence of one — genuine blind spot, not
   an oversight (see [Partner: illicado](../partners/illicado.md)).

10. **This POC targets a Payment API version [docs.purse.tech](https://docs.purse.tech/docs) itself
    marks deprecated.** The public spec index lists "Payment API v1 (deprecated)" alongside a
    current v2 — and the `/init`/`/validate` paths this repo (and the web widget) calls,
    `POST /{entity}/sessions/{session}/init|validate`, are the deprecated v1 shape. Amount units
    for v1 are confirmed **major/decimal units** (spec examples show `"amount": 199.99`) — good
    news, resolving the old point-7 ambiguity in this POC's favor — but v2 amounts are
    **minor units/cents** (`4299` = €42.99), and v2 has a materially different, simpler redirect
    model: a single `shopper_redirection_url` (no separate success/failure), redirected via
    **HTTP GET only**, and JWKS-verifiable signed `purse-redirection-data` tokens (the same JWS
    signing v2 uses for webhooks, per the [webhooks
    tutorial](https://docs.purse.tech/docs/integrate/purse-api/tutorials/webhooks/) and the
    `signing-jwks` endpoint). None of v1's `global_status` enum, `redirection` object schema, or
    CB payload shape are published anywhere in the public docs either — confirming points 2/8/9
    aren't discoverable without the backend team, and raising a bigger question than any single
    field: **should a new mobile SDK be built against v1 at all** (see [Questioning our own
    approach](#questioning-our-own-approach))?

11. **There appear to be two different session ids, and the reference implementation itself hedges
    on which one to use.** `POST /orchestration_session` (v2 `/client-sessions`) returns its own
    top-level `id` — but the v1 `GET /{entity}/sessions/{session}` this POC's new "re-query the
    session" step (point 10 / `DropinAndroid.getSessionTransactions`) calls needs the *legacy*
    Payment API session id instead. `usp-widget/sites/demo/src/routes/widgets/confirmation/+page.svelte`'s
    `resolveDefaultSessionId` checks for a `legacy_payment_session_id` field on the decoded
    `widget.data` blob first — a field that appears **nowhere else in the entire codebase**, not
    in the SDK's own published decode types (see point 10's sibling `wallet_session` typing gap) —
    and falls back to the outer orchestration `id` **only if that's absent**; it never falls back
    to `payment_session.id`, the field this POC (and the web widget's own `/init`/`/validate`
    calls) already use. Whether `payment_session.id` happens to equal one, both, or neither of
    these in practice is unverified — this repo's demo app now has an alfred-backed "Get
    Transactions" tool (mirroring that same reference page, `DropinActivity.getSessionViaAlfred`)
    to check this against a real session rather than guessing further from source.

---

## Questioning our own approach

- **Is one generic redirect handler even the right target?** Web's genericity works because its
  real redirect case is rare. The most likely actual triggers already have (or will have) a
  better partner-native answer than a WebView: Adyen's own `RedirectComponent` for Google Pay 3DS,
  and SecureFields Android's own upcoming 3DS module for CB. Wrapping each PSP/vault's native
  component where one exists, and falling back to generic WebView only where none does, may beat
  optimizing for one uniform code path — this is now looking like the emerging pattern rather than
  a hypothetical, worth confirming once SecureFields' 3DS module lands.
- **Should a new client inherit web's inference-based contract at all** (point 3), or push the API
  toward an explicit `next_action`-style field (à la Stripe/Adyen) now, while it's still a POC?
- **Is the custom-scheme deep link a considered security decision, or just what PayPal's SDK made
  easiest?** Worth deciding App Links as the baseline before more redirect-based partners are added.
- **Does "CB full, PayPal/Illicado starter" validate the hard part of the ticket, or the easy
  part?** CB is the one method that never redirects — the ticket's headline question is answered
  at the design-doc level only, not in device-tested code. Worth being explicit with stakeholders
  that these are two different claims.
- **Is porting the web widget's `/init`+`/validate` (v1) contract the right target at all, given
  Purse's own docs mark v1 deprecated?** This POC mirrors the web reference faithfully — a
  reasonable choice, since that's the only working implementation to copy — but it means a brand
  new native SDK is being built against an API generation Purse itself is retiring, inheriting v1's
  undocumented `global_status`/`redirection` ambiguity (points 2–3) rather than v2's cleaner,
  already-simpler, already-signed redirect model. Worth an explicit decision with the API team
  before going past "starter," not an assumption inherited from "the web widget does it this way."

---

## What it would take from the API

Ranked by how much each blocks a real (non-"starter") redirect implementation:

1. **Disambiguate `global_status` for a redirect-required `/validate`** — does it come back
   `SUCCESS` (what web's own gate requires) or something else? Settleable only by a real sandbox
   3DS round-trip. Highest leverage item on this list.
2. **Replace the four-optional-field redirect inference with one discriminated field**, e.g.
   `next_action: { type: "REDIRECT_GET"|"REDIRECT_POST"|"REDIRECT_FORM"|null, url, body }` —
   additive, doesn't need to break existing web clients.
3. **Confirm whether PayPal order creation (`/init`) can take an app return target** (App Link or
   scheme) instead of always registering the merchant's web success URL.
4. ~~Provide an explicit schema for the CB vault-token payload~~ — **resolved from web's own vault
   bridge** (point 9): `payment_data.vault_vendor_token_id`, top-level, not nested under `card`.
   Still worth an explicit confirmation from the API team that this is the intended contract going
   forward, not just an artifact of how the web plugin happens to build the request.
5. ~~Confirm amount units~~ — **resolved**: v1 (this POC's target) confirmed major/decimal units
   via `docs.purse.tech`'s own spec examples. No longer open.
6. **State whether `success`/`failure` can be app deep links** for mobile-originated sessions, or
   are contractually merchant web URLs — and whether v2's single `shopper_redirection_url` model
   is the intended answer for any future mobile-first flow instead.
7. **Ask PSP-integration directly whether Illicado's intermediary can ever impose a step-up** —
   not discoverable client-side.
8. **Decide App Links vs. custom scheme as a documented mobile-integration requirement** — and
   whether that decision should just be "extend v2's JWKS-signed `purse-redirection-data` to a
   mobile app-return deep link" rather than a bespoke Android-only scheme.
9. **Decide, as a product call, whether new mobile SDK work should target v1 (matching the current
   web widget) or v2 (the non-deprecated, already-cleaner-for-redirects generation)** — this
   arguably supersedes items 1–3 above if the answer is v2.

---

## Sources

**Internal:** [Redirect Handling](../architecture/redirect-handling.md), [POC
Scope](poc-scope.md), [Session & Protocols](../architecture/session-and-protocols.md), [Partner:
paypal](../partners/paypal.md), [Partner: adyen](../partners/adyen.md), [Partner:
illicado](../partners/illicado.md) · `usp-widget/packages/sdk/src/API/types.ts`,
`.../sdk/src/redirection/redirection-manager.ts`,
`.../headless-checkout/src/headless-checkout-validation.ts`,
`.../partner-ui/src/plugins/generics/googlepay/index.ts`,
`.../partner-ui/src/plugins/generics/hostedfields/index.ts`,
`.../partner-ui/src/plugins/vaults/purse/creditcard/{bridge,utils}.ts`,
`.../sites/demo/src/routes/widgets/confirmation/+page.svelte`,
`.../packages/alfred/routes/payment.mjs` + `services/fetcher.mjs` · this repo's
`partner-ui/.../paypal/wallet/PaypalCheckoutActivity.kt`, `WalletValidationResult.kt`,
`GooglePayValidationResult.kt`

**External:** [PayPal Android SDK integration](https://developer.paypal.com/docs/checkout/advanced/android/) ·
[PayPal App Switch](https://developer.paypal.com/docs/checkout/standard/customize/app-switch/api-one-time-payments/) ·
[Android Custom Tabs overview](https://developer.android.com/develop/ui/views/layout/webapps/overview-of-android-custom-tabs) ·
[Verify Android App Links](https://developer.android.com/training/app-links/verify-android-applinks) ·
[Unsafe use of deep links](https://developer.android.com/privacy-and-security/risks/unsafe-use-of-deeplinks) ·
[Android deep link hijacking](https://oversecured.com/blog/android-deep-link-vulnerabilities) ·
[Adyen Redirect 3DS (Android)](https://docs.adyen.com/online-payments/3d-secure/redirect-3ds2/android-component)

**Purse's own product docs** ([docs.purse.tech](https://docs.purse.tech/docs)):
[Payment API v1 spec (deprecated)](https://docs.purse.tech/openapi/payment-api-v1) ·
[Payment API v2 spec](https://docs.purse.tech/openapi/payment-api-v2) ·
[API conventions](https://docs.purse.tech/docs/references/convention) ·
[Payment glossary](https://docs.purse.tech/docs/references/glossary/payment/) ·
[Hosted Page integration mode](https://docs.purse.tech/docs/integrate/purse-checkout/hosted-page/) ·
[Webhooks tutorial (JWS signing)](https://docs.purse.tech/docs/integrate/purse-api/tutorials/webhooks/)
