# Pain Points — Android Headless SDK vs Web Headless SDK

**Status: a start.** This catalogs where porting `usp-widget`'s headless-checkout package
(`@upstreampay/headless-checkout` + `@upstreampay/partner-ui`) to native Android diverges from
building on it in a browser — not an exhaustive audit. It grows as more plugins get ported
(card/CB, PayPal, Apple Pay, wallet sessions — see the architecture doc's
["Deferred"](../architecture/headless-checkout.md#deferred-intentionally-not-built-yet) section).

This supersedes the framing of an earlier POC-era doc of the same name, written against a
**native drop-in widget** ported from the web *widget*, not the headless SDK. That doc's backend
findings (Pain points 1–3 below) are about the Payment API itself, so they carry over unchanged —
headlessness doesn't touch them. Everything past that section is new: it's specifically about what
changes when the *client architecture* goes from "browser + JS framework" to "native Kotlin app,"
independent of the widget-vs-headless distinction.

---

## Carried over unchanged from the API itself

These are properties of the Payment API's `/validate` contract and PSP redirect behavior — true
for any client, browser or native, widget or headless.

### Pain point 1 — `/validate` doesn't clearly say "the shopper must do one more thing"

The response carries no explicit "action required" field. A client infers it from which of four
optional fields happen to be present (`redirection.url`, `redirection.form`, per-item
`redirection_url`/`redirection_json`, `should_submit_widget`), in a fixed priority order — ported
here as `RedirectionResolver`. To any client, "no redirect needed" and "a field is missing" look
identical. **Still unresolved**; would benefit from an explicit `next_action`-style field the way
Stripe/Adyen do it.

### Pain point 2 — Leaving the app and coming back is not the app's decision

The redirect return target (3-D Secure ACS, PayPal approval) is chosen by the backend/PSP at
`/init`-time, not by the client. Confirmed on Android against Google Pay's `3ds_visa` test card: a
real challenge's own final redirect can land on a Purse API gateway URL that 401s on the session's
own client-facing `api_key` — nothing fixable client-side (see
[headless-checkout.md](../architecture/headless-checkout.md#google-pay-euplsepartnerpluginsgenericgooglepay-inside-partner-ui)
for the confirmed URL and root cause). This is now *more* exposed on Android than it was on web,
even though the bug itself is identical: a browser tab can silently 401 and the shopper barely
notices; a failed native redirect return is a dead-end WebView screen the host app has to handle
explicitly. See ["Return-URL ownership" below](#d-return-url-ownership-shifts-onto-every-merchant)
for the integration-shaped consequence.

### Pain point 3 — A whole category of "always redirect" methods (Fintecture etc.) isn't ported yet

Web's generic `integration_mode: "redirection"` plugin — routed purely off session config, before
any partner-specific plugin lookup — has no Android equivalent yet. Nothing in this SDK has
exercised that category on a real session; the redirect infrastructure that exists
(`RedirectionResolver`, `RedirectionCheckoutActivity`) was built against 3-D Secure/PayPal-shaped
occasional redirects, not against a method that redirects *every single time*.

---

## New: what changes by going headless-native, independent of the API

### A. No rendering host — the merchant inherits 100% of the UI, permanently

Web's headless-checkout is still "headless" in the sense of owning no checkout page, but a plugin
still gets a DOM mount point and a `<purse-partner-ui>` custom element to render into, with
`onDataUpdate`/`onAmountUpdate` pushing live updates into it. Android's `:partner-ui` contract has
those lifecycle hooks (`onSessionSet`, `onAmountUpdate`, `onDataUpdate`) but the rendering host that
would drive them is explicitly [deferred](../architecture/headless-checkout.md#deferred-intentionally-not-built-yet) —
there is no equivalent of even a bare DOM element to hand a plugin. Every field, every live
re-render on amount change, every disabled-state indicator is 100% host-app code, indefinitely, not
just until some future "basic" rendering layer ships. Card vaulting (CB/SecureFields) is the plugin
most likely to expose this gap first — it needs a live-validating field, and nothing in this SDK
has modeled what that looks like without a DOM.

### B. Reactive model mismatch: Svelte stores vs Kotlin `StateFlow`

Web's `remainingAmountToPay`/`isPaymentFulfilled`/`amountSplit`/`paymentMethods` are Svelte stores —
subscribable from anywhere, no lifecycle concept required, because a browser tab's lifecycle *is*
the page. `StateFlow` requires the host to actively collect inside a coroutine scope, and to do it
*lifecycle-aware* (`repeatOnLifecycle`) to avoid leaking collectors across Activity recreation —
plumbing web integrators never have to write. This SDK's own demo (`MainActivity`) sidesteps the
problem entirely by reading `.value` synchronously after each mutating call instead of collecting —
a real integration that wants live-updating UI (e.g. a remaining-balance label while gift cards are
being added) doesn't have that luxury and has to build the collection wiring itself, per screen.

### C. Every PSP's *native* Android SDK is a separate integration from its web SDK

Web's `usp-widget` plugin code often *is* the PSP integration — Klarna's plugin talks to
`Klarna.Payments`, a JS SDK. Porting Klarna to Android meant learning an entirely different SDK
(`KlarnaPaymentView`, a native widget with its own callback-based lifecycle) with its own
undocumented-until-you-hit-it quirks — e.g. `loadPaymentReview()` throwing for any
`purchase_country` outside the US, discovered only by triggering it (see `KlarnaCoordinator`'s
`prepare` doc). Nothing in the web reference implementation could have surfaced that quirk, because
web never calls the equivalent API at all. Every future PSP plugin carries this same risk
individually: Google Pay happened to be low-risk here because both platforms go through
Google-provided SDKs with parallel APIs, but that's not guaranteed for the next partner ported —
each one is its own from-scratch native-SDK research project, not a mechanical translation of the
existing web plugin.

### D. Return-URL ownership shifts onto every merchant

Klarna's `returnUrl` must resume the **same Activity instance** hosting the live widget, which means
every merchant integrating this SDK must declare their own `singleTask` intent-filter correctly (see
[the consumer API doc](../integration/consumer-api.md#7-declaring-your-own-deep-link-for-klarna)) —
`:partner-ui` cannot ship this the way it ships Google Pay's self-contained redirect Activity, because
it isn't `:partner-ui`'s Activity that needs to resume. Web has no equivalent merchant-declared
configuration step; a `<script>` tag has no manifest. Two different plugins already need two
different redirect-return architectures (self-contained Activity vs. resume-the-host's-own Activity)
for what's conceptually the same "shopper went to an external page and came back" flow — that
divergence is purely an artifact of native app lifecycle rules, not of the underlying payment logic.

Compounding this: custom URL schemes (what Klarna's own returnURL sample uses, and what this SDK's
demo uses today) have no OS-enforced ownership — any installed app can claim the same scheme. Web
never has to make this tradeoff. [Verified App Links](https://developer.android.com/training/app-links/verify-android-applinks)
are the correct answer for anything beyond a demo, and every merchant integrating this SDK inherits
that decision, not just this repo's own demo app.

### E. Coverage gaps vs web's current headless-checkout are a porting backlog, not just "different"

As of this writing: no card/CB vaulting plugin (a separate SecureFields Android 3DS module is in
progress out-of-band), no PayPal, no Apple Pay, no `ancv-connect` two-phase secondary flow, no
splitter voucher→loyalty→primary autofill middleware, no wallet/saved-token sessions. A merchant
comparing "what can I build with the web SDK" to "what can I build with the Android SDK" today will
find the Android answer is a strict subset, not a like-for-like port with different ergonomics.
Each gap above is its own item on this recap once work starts on it — expect this section to be
replaced by concrete pain points as CB/PayPal/wallet land, the way Google Pay's and Klarna's
sections above already were.

### F. No browser devtools — confirming actual API shapes is `Log.d` + `adb logcat`

Web integrators get the network tab for free when a `/validate` response doesn't behave as the
reference code expects (see Pain point 1). This SDK's own coordinators resort to ad hoc
`Log.d(TAG, "validate response: …")` calls (see `GooglePayCoordinator.submit` /
`KlarnaCoordinator.resolveOutcome`) plus `adb logcat` filtering as the only way to inspect a real
response body during development — there's no equivalent of "open devtools" for a merchant
integrating this SDK to self-diagnose an unexpected `global_status` or missing redirect field.
Worth a deliberate answer (a debug-only response inspector, structured logging convention, or
similar) before this SDK reaches merchants who don't have `adb` access to the device in question.

---

## Open questions for next iteration

- Does the eventual card/CB plugin need *any* rendering-host primitive, or can SecureFields'
  separate native 3DS module sidestep the DOM-mount-point problem entirely (§A)?
- Is there a lighter-weight lifecycle-aware collection helper worth adding to
  `:headless-checkout` itself, so every host app doesn't reinvent §B?
- Should `:partner-ui` ship a reusable "declare this intent-filter" checker/lint, given §D is a
  correctness footgun (a merchant who gets the manifest wrong fails silently at the redirect-return
  step, not at build time)?
