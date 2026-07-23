# Pain Points — Android Drop-in POC

What we learned building [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039): where
porting the web widget (`../usp-widget`) to native Android was straightforward, where it wasn't,
and what we need from the API/backend team to go further. Deep technical detail lives in
[Redirect Handling](../architecture/redirect-handling.md) and the [partner
pages](../partners/README.md); this page is the summary.

---

## The mental model: how a payment flows

Purse's own [simple-payment tutorial](https://docs.purse.tech/docs/integrate/purse-api/tutorials/simple-payment)
describes the lifecycle. Adapted to this POC, every payment method goes through the same steps:

```
1. Merchant backend creates a session      → the app receives widget.data
2. Shopper picks a method                  → card fields, Google Pay sheet, or PayPal approval
3. The Drop-in calls POST /init            → registers the attempt, gets method-specific data back
4. The Drop-in calls POST /validate        → actually submits the payment
5a. Usually: the payment is resolved       → show the result. Done.
5b. Sometimes: the shopper must do one
    more thing on an external page (3DS)   → send them there, bring them back, re-validate
```

**The single most clarifying realization of this POC:** on web, there is always a step 6 —
navigate the browser to the merchant's page (`shopper_redirection_url` in the tutorial,
`session.success` in the widget). That step exists because a browser tab has to end up *somewhere*.
It is **not part of resolving the payment**. A native SDK has no browser tab: when step 5a applies —
which is the common case (CB without 3DS, Illicado, most Google Pay and PayPal payments) — the
Drop-in simply hands the result back to the host app and is done. Our demo app already works this
way.

So the redirect problem, which the Jira ticket flags as *the* open question ("comment gérer la
redirection"), is real but narrower than it first looked *for the four methods this POC covers*.
It only concerns step 5b (a 3DS challenge) and PayPal's approval in step 2 (the one capture that
inherently leaves the app). But — see Pain point 3 below — those four methods don't include the
one category of payment method where a redirect **is** the whole flow, every time, with real web
precedent: bank-transfer-style methods like Fintecture. This POC has designed a generic redirect
handler without ever exercising the case it's actually for.

Everything below is organized against those steps.

---

## What works end-to-end today

| Method          | Capture                      | /init + /validate          | External step                                                   |
| --------------- | ----------------------------- | --------------------------- | ---------------------------------------------------------------- |
| **CB** (SecureFields) | native fields            | wired (`CardMapper`)        | 3DS: planned via SecureFields' upcoming 3DS module               |
| **Google Pay**  | native sheet                  | wired                        | 3DS: wired (Custom Tabs/WebView), untested against a real challenge |
| **PayPal**      | PayPal SDK browser-switch      | wired                        | approval works; backend return-URL question open (see below)    |
| **Illicado**    | plain form                    | `/init` wired                | none known                                                       |

Not in this table: any `integration_mode: "redirection"` method (e.g. Fintecture
`instant_payment`) — a whole category this POC has no example of at all. See Pain point 3.

---

## Pain point 1 — The API doesn't clearly say "the shopper must do one more thing"

This is the most important finding, and it blocks step 5b.

When `/validate` needs a follow-up (a 3DS challenge), the response doesn't carry an explicit
"action required" field. Instead the web widget **infers** it from which of four optional fields
happen to be present (`redirection.url`, `redirection.form`, per-item `redirection_url` +
`redirection_json`, `should_submit_widget`), checked in a fixed priority order
(`redirection-manager.ts` in the web SDK). To a client, "no redirect needed" and "a field is
missing" look identical.

Worse, the response's overall status (`global_status`) doesn't disambiguate either. Its possible
values are `SUCCESS / ERROR / UNCERTAIN / FAILED / WAITING` — and the web widget only runs its
redirect logic when the value is **`SUCCESS`**. So if the backend were to answer a 3DS-requiring
`/validate` with `WAITING` (the intuitive choice), the web widget's own redirect code would never
run. Either the backend always says `SUCCESS` here (surprising but consistent), or this is a latent
bug in the reference implementation that we should not port. We can't tell which from reading
code — **no one has yet captured a real 3DS-requiring `/validate` response in sandbox**, on web or
on Android.

**What we need:** one real sandbox 3DS round-trip, and ideally an explicit
`next_action: { type, url, body }`-style field in the response (the way Stripe and Adyen do it) so
no client ever has to guess again.

---

## Pain point 2 — Leaving the app and coming back (PayPal, 3DS)

Web never solved this because it never had to: PayPal approval happens in an in-page popup, and
3DS pages just navigate the same browser tab. An app has to *leave* (Custom Tab or partner SDK)
and *return* (deep link). Two sub-problems:

**The return target is chosen by the backend, not the app.** The PayPal order is created
server-side during `/init`; whatever return URL Purse registers with PayPal at that moment is
where the shopper lands after approving. Our app declares the right scheme on its side
(`PaypalCheckoutActivity`, per [PayPal's Android SDK
requirements](https://developer.paypal.com/docs/checkout/advanced/android/)), but that only works
if the backend registers a matching app link instead of the merchant's web success URL.
**Unconfirmed** — this is the top blocker for calling PayPal "done."

**Custom URL schemes aren't secure return paths.** Any installed app can claim the same scheme;
only [verified App Links](https://developer.android.com/training/app-links/verify-android-applinks)
have OS-enforced ownership. Low risk today (PayPal's return only carries an order id that is
re-checked server-side), but it should be a deliberate decision before more redirect-based methods
are added. Notably, Purse already ships a fit-for-purpose mechanism in API v2: signed
`purse-redirection-data` tokens, verifiable against a public JWKS endpoint. Extending that to
app returns beats inventing something Android-specific.

Also worth knowing: if a 3DS page must be *POSTed* to (the web contract allows form-POST
redirects), a Custom Tab can't do it — only a WebView can.

**Correction to an earlier assumption here:** this doc used to say Adyen's own Android
`RedirectComponent` was "the intended native answer" for Google Pay's 3DS step, ahead of a generic
WebView. Checked directly against `usp-widget/packages/partner-ui/src/plugins/partners/adyen-creditcard/index.ts`
(lines ~244–263): its `postValidation` calls the exact same generic `manageRedirection` as Google
Pay's plugin — no Adyen SDK redirect component anywhere in the reference. That's a dead end for
this architecture anyway: Adyen's real `RedirectComponent` consumes Adyen's own typed `action`
object from Adyen's own `/payments` response, which we never see — Purse's `/validate` abstracts
Adyen away and hands back its own generic `redirection` object instead. The generic Custom
Tabs/WebView approach *is* what the reference implementation itself does here, not a fallback —
and it's now implemented: `RedirectionResolver` (`:partner-ui`) plus a generic
`RedirectionCheckoutActivity`/`RedirectionCheckoutContract` pair (Custom Tabs for GET, WebView for
POST), wired into Google Pay's capture flow in `DropinActivity`. SecureFields Android is separately
getting its own dedicated 3DS module for CB, so CB won't need this generic path at all.

---

## Pain point 3 — A whole category of "always redirect" methods isn't in this POC at all

PayPal and 3DS are not the only shape a redirect takes. Web has a **fully generic,
partner-agnostic redirect plugin** — `plugins/generics/redirection/index.ts` — that any method gets
routed to automatically, based purely on session configuration:

```ts
// usp-widget/packages/partner-ui/src/plugins/partners/index.ts
if (session?.protocols[partner]?.[method]?.configurations.settings
      ?.merchant_specifics?.integration_mode === 'redirection') {
  return redirection({ partner, method });   // generic plugin, no partner-specific code at all
}
```

This check runs **before** any partner-specific plugin is even looked up. Any partner/method entry
in `protocols` can be routed this way — it's a property of how the merchant configured that
method for that session, not something tied to a fixed partner list. A concrete, real example:
Fintecture's [`instant_payment`](https://docs.purse.tech/docs/partners/fintecture/instant_payment),
a bank-transfer method whose own docs list "Redirection" as its integration mode. There are likely
others across Purse's ~38-partner catalog that work the same way.

This category is fundamentally different from PayPal (a popup on web, only forced into a redirect
on Android) and from 3DS (an occasional step-up on an otherwise-synchronous payment): **it is a
redirect every single time, with genuine, non-hypothetical web precedent** — full-page browser
navigation already runs in production today, no popup fallback ever existed. The generic plugin's
`postValidation` calls the same `manageRedirection` resolver unconditionally, and **treats a
missing `redirection.url` as an outright error**
(`ErrorCodes.PostValidationDataIsMissingInformations`) rather than "nothing more to do" — for this
category, "is a redirect required" is never ambiguous the way Pain point 1 describes for CB/Google
Pay's 3DS.

This POC's four methods (CB, Google Pay, PayPal, Illicado) don't include a single one of these. The
generic Android redirect handler this whole doc proposes — Custom Tabs, WebView, the deep-link
return path — has therefore never been validated against the one category of method it was
actually designed for; it's been inferred from reading `redirection-manager.ts`, not built against
a real example.

**Recommendation:** if this POC continues, add one `integration_mode: "redirection"` method
(Fintecture `instant_payment` or similar) as a fifth test case. It needs no partner SDK — just a
plain HTTP GET/POST redirect — and, unlike 3DS (which needs a specific test card to provoke a
step-up on demand), it redirects unconditionally on every sandbox run. It would be the cleanest,
fastest way to prove the generic handler design actually works, instead of continuing to design it
against zero real examples.

---

## Pain point 4 — Which session id is *the* session id?

Creating a session through the v2 orchestration API returns an `id`. The decoded `widget.data`
contains a `payment_session.id` (which all `/init`/`/validate` calls use). And usp-widget's own
demo confirmation page looks for a **third** field, `legacy_payment_session_id`, inside
`widget.data` — a field that appears nowhere else in the codebase, not even in the SDK's types —
falling back to the orchestration `id`, and never to `payment_session.id`.

Whether these are one, two, or three distinct values in practice is unverified. It matters for the
status-re-query endpoint (`GET /{entity}/sessions/{session}`), which the tutorial recommends as the
source of truth after payment. Our demo app now has a "Get Transactions" tool (same as the web
demo's confirmation page) to answer this empirically against a live session.

---

## Pain point 5 — We're building on the deprecated API generation

[docs.purse.tech](https://docs.purse.tech/docs) lists the `/init`/`/validate` API we (and the web
widget) use as **"Payment API v1 (deprecated)"**. The current v2 works differently in ways that
matter to a mobile SDK:

- v1 amounts are decimal euros (`199.99`); v2 amounts are cents (`4299`).
- v2 has a single `shopper_redirection_url` (GET-only, signed data) instead of v1's four-way
  inferred redirect contract — i.e. v2 already fixed most of Pain point 1.
- v1's `/validate` response schema (the `global_status` enum, the `redirection` object) is
  documented **nowhere** publicly; we reconstructed it from web widget source.

This raises a strategic question rather than a technical one: **should a brand-new mobile SDK
target v1 at all**, or should it be the first v2-native client? Mirroring the web widget was the
right way to run the POC — it's the only working reference — but it shouldn't silently become the
production decision.

---

## Pain point 6 — Small but real: Illicado is a black box

No public developer docs, no partner plugin on web, no way to know from the client side whether
its PSP intermediary can ever demand a step-up. Only the PSP-integration team can answer.

---

## Resolved along the way

These were open questions in earlier drafts; both closed by reading the right sources:

- **Amount units** — v1 is decimal major units, confirmed by the official v1 spec's own examples.
  What we send today is correct.
- **CB submit payload shape** — the SecureFields vault token goes in
  `payment_data.vault_vendor_token_id` (top level, *not* under `card`), confirmed against the web
  widget's own vault plugin and now implemented in `CardMapper`.

---

## Asks for the API/backend team, in priority order

1. **Show us one real 3DS-requiring `/validate` response** (sandbox) — settles the
   `global_status`/redirect ambiguity that everything else in Pain point 1 hangs on.
2. **Add an explicit `next_action` field** to `/validate` responses (additive, non-breaking) so
   clients stop inferring redirects from optional-field patterns.
3. **Confirm PayPal order creation can register an app return URL** (App Link or scheme) for
   mobile sessions — the PayPal flow is blocked on this.
4. **Let us add one `integration_mode: "redirection"` method to the POC** (Fintecture
   `instant_payment` or similar) — the generic redirect handler (`RedirectionResolver` +
   `RedirectionCheckoutActivity`) is now built, but has never run against a real redirect end to
   end; this is the fastest way to prove it actually works (Pain point 3).
5. **Clarify the session id story** — is `legacy_payment_session_id` real, and which id does
   `GET /{entity}/sessions/{session}` want?
6. **Tell us whether mobile SDK work should target v1 or v2.** If v2, asks 1–2 may be moot.
7. **Decide App Links (and/or v2's signed redirection-data) as the mobile return-path standard**,
   rather than per-partner ad-hoc schemes.
8. **Ask PSP-integration whether Illicado can ever require a step-up.**

---

## Sources

**Web reference (`../usp-widget`):** `packages/sdk/src/API/types.ts` (status enums, response
types) · `packages/sdk/src/redirection/redirection-manager.ts` (the four-way redirect inference) ·
`packages/headless-checkout/src/headless-checkout-validation.ts` (the `global_status === SUCCESS`
gate) · `packages/partner-ui/src/plugins/vaults/purse/creditcard/` (CB payload shape) ·
`packages/partner-ui/src/plugins/generics/googlepay/` (the confirmed 3DS trigger) ·
`packages/partner-ui/src/plugins/generics/redirection/` + `plugins/partners/index.ts`
(the generic, session-config-driven redirect plugin) ·
`sites/demo/src/routes/widgets/confirmation/+page.svelte` + `packages/alfred/` (session id
hedging, status re-query).

**Purse public docs:** [simple-payment tutorial](https://docs.purse.tech/docs/integrate/purse-api/tutorials/simple-payment) ·
[Payment API v1 (deprecated)](https://docs.purse.tech/openapi/payment-api-v1) ·
[Payment API v2](https://docs.purse.tech/openapi/payment-api-v2) ·
[conventions](https://docs.purse.tech/docs/references/convention) ·
[webhooks / JWS signing](https://docs.purse.tech/docs/integrate/purse-api/tutorials/webhooks/) ·
[Fintecture instant_payment](https://docs.purse.tech/docs/partners/fintecture/instant_payment).

**External:** [PayPal Android SDK](https://developer.paypal.com/docs/checkout/advanced/android/) ·
[Android App Links verification](https://developer.android.com/training/app-links/verify-android-applinks) ·
[Android deep-link risks](https://developer.android.com/privacy-and-security/risks/unsafe-use-of-deeplinks) ·
[Adyen native 3DS redirect component](https://docs.adyen.com/online-payments/3d-secure/redirect-3ds2/android-component).

**This repo:** [Redirect Handling](../architecture/redirect-handling.md) ·
[Session & Protocols](../architecture/session-and-protocols.md) ·
[Payment Methods](../integration/payment-methods.md) · [partner pages](../partners/README.md)
(one sequence diagram each) · [POC Scope](poc-scope.md).
