# Pain Points — Android Headless SDK vs Web Headless SDK

**Ticket:** [SDK-12039](https://upstreampay.atlassian.net/browse/SDK-12039) — a POC to decide whether
a native Android drop-in is feasible before investing in a full solution. Required end-to-end: CB
(via the existing Secure Fields Android SDK, SDK-11769), Google Pay, PayPal, Illicado, plus a vault
fallback — "functionally identical to the web drop-in." The ticket's own open question: **"comment
gérer la redirection (intent)"** — how to handle redirection.

This repo answers that question by going one layer further than the ticket asked for — a **headless**
port of `usp-widget`'s headless-checkout package, not a drop-in widget — but the ticket's three
decision points are exactly what this doc tracks. Everything below is scoped to them, not to every
friction point of building headless-native in general.

---

## 1. Redirect handling — the ticket's own open question

**Short answer: yes, intent-based redirection works.** `RedirectionResolver` + Custom Tabs (GET) /
in-app WebView (POST) + a deep-link return closes the loop, confirmed against a real 3-D Secure
challenge on Google Pay's `3ds_visa` test card. That validates the architecture the ticket was
asking about.

Two things keep it from being a *closed* question, both backend-owned, neither fixable client-side:

- **The `/validate` response never says "a redirect is required."** A client infers it from which of
  four optional fields happen to be present, in a fixed priority order — "no redirect needed" and "a
  field is missing" look identical. Would benefit from an explicit `next_action`-style field.
- **The redirect's own return URL is chosen by the backend/PSP at `/init`-time, not by the app.**
  Confirmed concretely: a real 3-D Secure challenge's final redirect can land on a Purse API gateway
  URL that 401s on the session's own client-facing `api_key`. For **PayPal specifically** — one of
  the ticket's four required methods, not yet built on this headless port — whether the backend can
  register an app-facing return URL at all (instead of a merchant web success URL) is still
  unconfirmed, which blocks calling that method's redirect story "done" even before it's implemented.

Net: the redirect *mechanism* is proven. The redirect *contract* (what the API promises about when
and where) still has open gaps that land on whichever method exercises them next — most immediately
PayPal.

## 2. Parity against the ticket's required scope

| Requirement | Status on this headless port |
|---|---|
| CB (Secure Fields Android, SDK-11769) | **Not yet built.** A separate SecureFields Android 3-D Secure module is in progress out-of-band; nothing in `:partner-ui` integrates it yet. |
| Google Pay | Done — protocol, sheet, and 3-D Secure redirect all working, including the gateway-specific (Ingenico/Adyen/default) builder split. |
| PayPal | **Not yet built.** Also where the redirect-return-URL gap above bites hardest. |
| Illicado | Done — but as a **secondary** (gift-card co-payment) method, not a primary card flow. The ticket groups it with CB/Google Pay/PayPal as if it's one more primary method; in Purse's own model it isn't, so "Illicado works" doesn't close the same gap CB/PayPal do. |
| Vault fallback | **Not addressed.** `MethodConfiguration.VaultInfo`/`PrimaryMethod.supportedBrands` model vault *configuration*, but no fallback-when-vaulting-fails path exists anywhere in this port yet. |

Klarna is also fully built on this port (protocol, native `KlarnaPaymentView`, redirect) but isn't
one of SDK-12039's four required methods — it's scope the branch took on beyond the ticket, useful
as a second data point (see below) but not itself a gap against the ticket.

## 3. "Functionally identical to the web drop-in" — where that's structurally not free

Two concrete respects where headless-native adds integration work a web drop-in never asks for,
found by comparing this port against `usp-widget` directly:

- **Plugin activation used to be a merchant setup step here; it's automatic on web — now fixed to
  match.** Web's `getPlugin` resolves off a static lookup table baked into the
  `@upstreampay/partner-ui` package — every plugin the package ships is live automatically, gated
  only by the session. Android's `PluginRegistry` used to start empty and only activate a method
  once the host app called that method's `*Module.register()` — a merchant could forget one and
  get no earlier signal than `PurseCheckoutException.NoPluginRegistered` at submit time. That was a
  redundant gate, not a necessary one: `getPlugin(partner, method, session)` already only resolves
  a match if `session.protocols[partner][method]` is present, i.e. whether a method is offered was
  already decided server-side by the merchant's Purse configuration — the app's `.register()` call
  didn't add a second real decision, it just had to happen to stay in sync with one that already
  existed. **Fixed:** `PluginRegistry` now auto-registers every built-in plugin (Google Pay, Klarna)
  by default, mirroring web's static table; the session remains the only real gate, and a merchant
  who genuinely needs to suppress a shipped method for a specific app build gets an explicit
  opt-*out* (`PluginRegistry.disable(partner, method)` / `.enable(...)`) instead of the old opt-*in*.
  `GooglePayModule.register()`/`KlarnaModule.register()` are still callable (idempotent) but no
  longer required — the demo app's `MainActivity` no longer calls them. Filed as SDK follow-up work
  off SDK-12039 — blocked on Jira write access for this session (drafted ticket text is in repo
  chat history / ask the author for it) rather than actually filed.
- **Redirect-based methods need app-side manifest declarations; web needs none.** Klarna (built,
  though outside the ticket's four) needs the host app to declare its own `singleTask` intent-filter
  so its return URL resumes the right Activity. **PayPal — one of the four required methods, not yet
  built — will need the same kind of app-side redirect-return wiring once it lands**, which is new
  integration surface with no web equivalent, on top of whatever the backend return-URL gap in §1
  turns out to require.

Compounding both: the deep-link schemes used so far (Klarna's, this SDK's demo) are custom URL
schemes with no OS-enforced ownership. [Verified App Links](https://developer.android.com/training/app-links/verify-android-applinks)
are the correct answer before PayPal's redirect adds another one — worth deciding once, not per
method.

---

## Bottom line

Going headless hasn't changed the answer to what SDK-12039 asked. Native Android redirect handling
*is* feasible, exactly as this repo's Google Pay work demonstrates — but the two backend-owned gaps
this doc's predecessor already found (ambiguous "redirect required" signal, backend-controlled
return URLs) are still open, and they land squarely on PayPal, the one required method most likely
to need them next. CB and vault fallback remain unbuilt on this architecture entirely. "Functionally
identical to web" holds for what's shipped, with two concrete asterisks (plugin activation, redirect
manifest wiring) that are integration cost, not missing functionality.
