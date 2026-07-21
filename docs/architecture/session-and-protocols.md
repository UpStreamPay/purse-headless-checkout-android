# Session & Protocols

The session payload the Drop-in consumes, the `protocols` map that drives which payment methods
are shown, the Payment API endpoints used to submit a payment, and the three layers of
status/lifecycle state. This mirrors `@upstreampay/headless-checkout` in `../usp-widget` —
Android should reuse its vocabulary rather than invent new terms, so this doc and the web code
stay easy to cross-reference.

---

## Table of Contents

- [Where the session comes from](#where-the-session-comes-from)
- [Decoding `widget.data`](#decoding-widgetdata)
- [Full `LegacyPaymentSession` shape](#full-legacypaymentsession-shape)
- [Session-level vault config — where `tenantId` actually comes from](#session-level-vault-config--where-tenantid-actually-comes-from)
- [Validation rules](#validation-rules)
- [`information_request` sessions](#information_request-sessions)
- [The `protocols` map](#the-protocols-map)
- [`MethodConfiguration`](#methodconfiguration)
- [Turning `protocols` into a method list](#turning-protocols-into-a-method-list)
- [Method availability rules (disabled states)](#method-availability-rules-disabled-states)
- [Payment API — environments and endpoints](#payment-api--environments-and-endpoints)
- [`/init` and `/validate`](#init-and-validate)
- [Three layers of status](#three-layers-of-status)
- [See also](#see-also)

---

## Where the session comes from

A Purse **Client Session** is created server-side by the merchant's backend against the
Orchestration API — the Android app never creates a session itself, the same way the web widget
never does. The backend response includes a `widget` object:

```
ClientSession {
  id, status, amount, currency, ...
  widget: { data: string, js_url: string }   // "js_url" is a web-only field, irrelevant on Android
  ...
}
```

`widget.data` is what the Drop-in needs. How the merchant's backend hands that string to the
Android app (deep link, QR code, its own API, embedded in a push notification, ...) is a merchant
integration detail outside this POC's scope — the demo app can hardcode or paste it for now.

---

## Decoding `widget.data`

`widget.data` is a **base64-encoded JSON blob**. Decoded, its shape (matching
`packages/headless-checkout/src/utils/session-utils.ts` in `usp-widget`) is:

```json
{
  "api_key": "…",
  "api_url": "…",
  "environment": "sandbox",
  "entity_id": "…",
  "payment_session": { "...": "see below" },
  "wallet_session": { "...": "optional — saved cards, out of scope for this POC" }
}
```

Proposed Kotlin model (in the `:dropin` module — see [Project Setup](../internal/project-setup.md)):

```kotlin
data class PurseSessionParams(
    val apiKey: String,
    val apiUrl: String,
    val environment: PurseEnvironment,
    val entityId: String,
    val paymentSession: PaymentSession,
)

enum class PurseEnvironment { DEVELOPMENT, TEST, SANDBOX, PRODUCTION }
```

`environment` resolves the Payment API root — see
[Payment API — environments and endpoints](#payment-api--environments-and-endpoints) below.

> **Two slightly different decode paths exist on web.** The one shown above
> (`utils/session-utils.ts`, used by the main `PurseHeadlessCheckout.init()`/`PurseDropin.init()`
> entry point) types `wallet_session` explicitly. A second decoder
> (`session/payment/utils.ts::extractWidgetDataFromString`, typed as `OrchestrationPaymentSessionInner`
> — `{ api_key, api_url, environment, entity_id, payment_session }`) does not include
> `wallet_session` in its type at all. Since this POC doesn't use `wallet_session` (saved
> cards/tokens are out of scope — see [POC Scope](../decisions/poc-scope.md#explicitly-out-of-scope)),
> this discrepancy doesn't affect the Android design, but it's worth knowing the field isn't
> universally typed across the web codebase if you go looking for it there.

---

## Full `LegacyPaymentSession` shape

`payment_session` (`LegacyPaymentSession` in `usp-widget/packages/sdk/src/types.ts`) has more
top-level fields than just `protocols`:

```ts
type LegacyPaymentSession = {
  id: string;
  amount?: number;                 // absent/0 → an "information_request" session, see below
  information_request?: boolean;   // explicit alternative to inferring from `amount`
  currency_code: string;
  expiration_date: string;
  success?: string;                // where the browser ends up after a successful redirect flow
  failure?: string;                // where the browser ends up after a failed redirect flow
  redirection?: string;            // fallback redirect target — see below
  protocols: { [partner]?: { [method]?: { configurations: MethodConfiguration; integrated_tokens?: IntegratedToken[] } } };
  vault?: SessionVault;            // session-level vault vendor config — see next section
  wallet?: { shared_wallet_user?: boolean };
};
```

`redirection` is distinct from `success`/`failure`: it's the **fallback URL used when a
`/validate` response itself doesn't carry a `redirection.url`**, in the two cases where the
headless-checkout layer (rather than a specific payment method) has to handle the redirect
itself — see [Redirect Handling](redirect-handling.md#the-session-level-fallback-and-the-error-path)
for exactly when that happens.

---

## Session-level vault config — where `tenantId` actually comes from

`session.vault` (`SessionVault` type) is **separate from the per-method `vault` field** inside
`MethodConfiguration` (described [below](#methodconfiguration)) — don't conflate the two:

```ts
type SessionVault = {
  vendor_front_data: {
    purse?: { tenant_id: string };
    pci_proxy: { merchant_id: string };
  };
};
```

This is where the value to pass as SecureFields Android SDK's `tenantId` parameter actually comes
from: **`session.vault.vendor_front_data.purse.tenant_id`** — not `entity_id`, which is a
different identifier (the merchant/entity, used to scope Payment API calls, not Vault ones). This
corrects an earlier draft of this doc set, which assumed `entityId` doubled as the Vault
`tenantId`; it does not. See [Getting Started](../integration/getting-started.md) for the
corrected initialization code.

---

## Validation rules

Client-side validation of an incoming session is intentionally minimal — mirror this rather than
adding stricter checks that don't exist on web, since a real merchant backend can and does send
sessions that only satisfy the bare minimum below:

- **`checkSessionFormat`** (`utils/session-utils.ts`) — the *only* structural check performed:
  `session.id` and `session.expiration_date` must both be present, or the whole init throws
  (`SESSION_MALFORMED`). Nothing else about `protocols`, `amount`, or `currency_code` is validated
  client-side at this stage.
- **`checkInitParams`** — for the non-base64 ("V1") init path: `apiKey`, `entityId`, and
  `environment` must all be truthy, and `paymentSession` must be present, or the init throws
  (`INVALID_INIT_PARAMS`).
- **Expiration re-check on every `setSession`** — separate from the format check above,
  `PursePayment.setSession()` re-validates expiration against the **server's** clock (via a
  `ServerDatetimeChecker.sync()` call, not the device's local clock) every time a session is set,
  throwing `EXPIRED_SESSION` if it's already past. A device with a skewed local clock should not
  affect this — Android should sync against server time the same way, not trust `System.currentTimeMillis()`
  against `expiration_date` directly.
- **Setting the same session twice is a no-op** — if `setSession()` is called again with a
  session whose `id` matches the currently-active one, web just warns and returns without
  rebuilding anything. Worth mirroring to avoid redundant work if the Android app re-enters this
  path (e.g. on a configuration change).
- **Environment is locked at the build level, not trusted from the session.** This is a rule
  worth taking seriously for a payment app: the web bundle is built for a fixed target
  environment (`SDK_ENV` at build time, defaulting to `test`). If that locked target is
  `production` or `sandbox` — the two real (non-development) tiers — the widget **ignores
  whatever `environment` the session or init params declare** and forces the locked tier's API
  URLs, also ignoring any custom `apiPaths` passed in, logging a console warning on mismatch. Only
  `development`/`test`-locked builds honor a session-declared environment. **Android should mirror
  this**: a release build should hardcode/lock its Payment API root (and SecureFields'
  `VaultEnvironment`) to `PRODUCTION` regardless of what a session claims, rather than trusting a
  session-supplied `environment` string to pick the API root at runtime — the same reasoning
  SecureFields itself already applies to `VaultEnvironment.TEST` (see
  `vault-android/docs/integration/api-reference.md#vaultenvironment`).

---

## `information_request` sessions

A session can exist purely to **register/collect a payment method without charging money** — no
transaction, no amount. This is out of scope for this POC (see
[POC Scope](../decisions/poc-scope.md#explicitly-out-of-scope)), but the rule is easy to trip over
by accident, so it's worth knowing:

- `isInformationRequest = session.information_request ?? !session.amount` — i.e. **an absent or
  zero `amount` implicitly makes a session an information request**, even without the explicit
  flag.
- When true, the method list is filtered differently: **only methods whose
  `configurations.settings.registration_mode === "OFF_SESSION"` are included at all** — everything
  else is dropped from the list entirely, not merely disabled.
  See [Turning `protocols` into a method list](#turning-protocols-into-a-method-list).
- Saved-card tokens (`integrated_tokens`) are stripped even for the OFF_SESSION methods that do
  survive — the point of this session type is registering a *new* method, not paying with one
  already on file.
- Amount-based disabling (`PAYMENT_FULFILLED`, `MIN_AMOUNT_NOT_MET`, `MAX_AMOUNT_EXCEEDED` — see
  [Method availability rules](#method-availability-rules-disabled-states)) never applies in this
  mode, since there's no amount to reach.

If this POC's demo sessions always carry a real, non-zero `amount`, none of this applies — but
it's worth an explicit assumption check when constructing test sessions, since a session created
with `amount: 0` silently becomes an information request rather than erroring.

---

## The `protocols` map

`PaymentSession.protocols` is a **partner → method → configuration** map. This is the entire
catalog of payment methods available for this session — there is no separate "list available
payment methods" call. The Drop-in is a pure consumer of this object, exactly like the web widget.

```json
{
  "protocols": {
    "aci":      { "creditcard": { "configurations": { "...": "MethodConfiguration, see below" } } },
    "paypal":   { "wallet":     { "configurations": { "..." : "..." } } },
    "illicado": { "giftcard":   { "configurations": { "..." : "..." } } },
    "adyen":    { "googlepay":  { "configurations": { "..." : "..." } } }
  },
  "amount": 480,
  "currency_code": "EUR",
  "success": "https://merchant.example/confirmation",
  "failure": "https://merchant.example/confirmation",
  "expiration_date": "2026-07-17T12:00:00Z"
}
```

Reference example: `usp-widget/e2e/tests/widget/mocks/session.ts` has a realistic full payload
including `illicado`, `paypal`, and `aci` (CB) entries.

There is **no dedicated "CB" method name.** Carte Bancaire is one of the `supported_brands` under
the generic `creditcard` method — see [`MethodConfiguration`](#methodconfiguration) below. Google
Pay is `method: "googlepay"` under whichever partner acts as the PSP gateway (e.g. `adyen`).
PayPal is `partner: "paypal"`, `method: "wallet"` (this POC ignores the sibling `bnpl` — Pay
Later — method). Illicado is `partner: "illicado"`, `method: "giftcard"`.

**Implemented** — `eu.purse.dropin.payment.partner.Partner` and `.Method` are sealed classes (not enums):
each recognized value is a singleton `object`, and anything else round-trips through an
`Other(raw: String)` case rather than being dropped — a real session's `protocols` can and does
contain partners this POC doesn't otherwise handle. `LegacyPaymentSession` (in
`dropin/src/main/kotlin/eu/purse/dropin/session/LegacyPaymentSession.kt`) is the parsed session,
`MethodEntry` wraps a `MethodConfiguration`; amounts are `BigDecimal`, not `Long` or `Double` —
session amounts are decimal major-currency-unit values (`payment_min_amount: 0.01` below rules
out an integer type), and `BigDecimal` avoids float-comparison bugs in the min/max checks that
drive [disabled states](#method-availability-rules-disabled-states).

---

## `MethodConfiguration`

```json
{
  "stable": true,
  "template": "primary",
  "labels": ["aci", "creditcard", "primary"],
  "position": 1,
  "vault": {
    "supported_brands": ["CARTE_BANCAIRE", "VISA", "MASTERCARD", "AMERICAN_EXPRESS"],
    "vendor": "purse",
    "token_scope": "…",
    "external_three_ds_enabled": true
  },
  "settings": {
    "registration_mode": "ON_SESSION",
    "save_token": true,
    "save_token_mode": "PREFER",
    "payment_min_amount": 0.01,
    "payment_max_amount": 99999,
    "usage_per_session_limit": 1,
    "merchant_specifics": { "integration_mode": "popup" }
  }
}
```

The field that matters most for this POC is **`vault.vendor`**:

| `vault.vendor` | Meaning | This POC |
|---|---|---|
| `"purse"` | Card fields are hosted by Purse's own Vault | ✅ Use [SecureFields Android SDK](../integration/getting-started.md) |
| `"pci_proxy"` | Card fields are hosted by the PSP directly (e.g. some ACI configurations) | ❌ Out of scope — different integration shape, see [POC scope](../decisions/poc-scope.md#explicitly-out-of-scope) |
| `"none"` | No vault — used by secondary methods like Illicado | n/a, see [Illicado](../integration/payment-methods.md#illicado-starter) |

`settings.merchant_specifics.integration_mode` (`"popup"` or `"redirection"`) is read for PayPal —
see [Redirect Handling](redirect-handling.md).

---

## Turning `protocols` into a method list

Web's `SessionBuilder` walks `protocols[partner][method]` and produces one runtime item per
partner/method combination, split into **primaries** (creditcard, googlepay, wallet — rendered as
a radio-style list, one form mounted per selection) and **secondaries** (giftcard and other
gift-card/voucher/loyalty methods — rendered as an accordion *above* the primaries list, since
picking one reduces the remaining amount rather than replacing the whole payment).

**The primary/secondary split is a literal string check, not a template or partner lookup:** a
method is secondary if and only if `configurations.labels.includes("secondary")`
(`SECONDARY_LABEL = "secondary"` in `usp-widget/packages/headless-checkout/src/session/payment/mops/item-factory/secondaries.ts`).
The sample payload's `illicado.giftcard` entry has `labels: ["illicado", "giftcard", "secondary"]`
— don't key off `template` or the partner name to decide this; check `labels`.

**Building the list also applies rules, not just a flat map-to-list conversion**
(`getLegacySessionMeansOfPayment` in `usp-widget/packages/headless-checkout/src/session/payment/utils.ts`):

- If the session [is an `information_request`](#information_request-sessions), any method whose
  `configurations.settings.registration_mode !== "OFF_SESSION"` is skipped entirely — not added to
  the list at all.
- For every method that *is* included, its `integrated_tokens` (if any, and unless this is an
  information request) are expanded into separate saved-card/token list entries alongside the
  method itself — out of scope for this POC (see
  [POC Scope](../decisions/poc-scope.md#explicitly-out-of-scope)), but worth knowing `protocols`
  entries can carry more than just the method's own configuration.
- The resulting list is sorted by `configurations.position` ascending (`positionSort`); entries
  without a `position` sort last, and two entries that both lack `position` keep their original
  relative order.
- **Web additionally filters by platform user agent for two specific partners only** —
  `usp-widget/packages/headless-checkout/src/session/payment/utils.ts`'s rewiring step deletes
  `payzen.applepay`/`sogecommerce.applepay` on an Android user agent and
  `payzen.googlepay`/`sogecommerce.googlepay` on iOS, then merges the survivor into a synthetic
  `xpay` entry. **This is scoped to those two named partners, not a general rule** — the sample
  session (and this POC's target `googlepay` entry, typically under `adyen`) isn't affected by it.
  Native Android has no reason to reimplement this Apple Pay filtering at all (Apple Pay is never
  offered on Android to begin with), but don't assume every partner exposes `googlepay`/`applepay`
  through this same merge pattern — most, including the ones this POC targets, don't.

**Implemented** — `eu.purse.dropin.payment.method.DropinPaymentMethod` is a sealed class mirroring
`PurseHeadlessCheckoutPaymentMethod` in
`usp-widget/packages/headless-checkout/src/types/headless-checkout.ts`, with `PrimaryMethod` and
`SecondaryMethod` data class cases (`id` follows the same `"{partner}-{method}"` convention as
web, e.g. `"paypal-wallet"`, `"illicado-giftcard"`). `minAmount`/`maxAmount` are computed
properties delegating to `configuration.settings`, not separately stored fields — `configuration:
MethodConfiguration` is the single source of truth per item, so e.g. `supportedBrands` is read via
`configuration.vault?.supportedBrands` rather than being duplicated onto `PrimaryMethod` itself.
`PaymentSessionBuilder.build()` (in `dropin/src/main/kotlin/eu/purse/dropin/session/`) is what
applies the `information_request` filter, the `labels`-based primary/secondary split, and the
position sort described above, producing this list.

For the demo app's UI: render `SecondaryMethod`s (Illicado) first in an accordion, then
`PrimaryMethod`s (CB, Google Pay, PayPal) as a list — matching the web ordering — once `:app` is
wired up to `:dropin` (see [Project Setup — Still to do](../internal/project-setup.md#still-to-do)).
See [Payment Methods](../integration/payment-methods.md) for how each one renders and submits.

---

## Method availability rules (disabled states)

Appearing in `protocols` doesn't mean a method is currently *selectable*. Web layers a separate
"disabled state" on top of each list item (`usp-widget/packages/headless-checkout/src/session/payment/mops/disabled.ts`),
recomputed whenever the amount still owed by primary methods (`primaryReach` — total minus
whatever's already covered by secondary-method splits) changes:

The full web taxonomy, for reference (`DisabledStateCode` in
`usp-widget/packages/headless-checkout/src/session/payment/mops/disabled.ts`): `MethodUnstable`,
`TokenInitFailed`, `PaymentElementFatalError`, `Submitting`, `PaymentElementRenderFailed`,
`PluginIncompatible`, `PartnerUiFatalError`, `FatalErrorBeforeRender`, `MinAmountNotMet`,
`PaymentFulfilled`, `AmountConditionUnreachable`, `DoesNotBelongToCurrentSession`,
`MaxAmountExceeded`.

**Implemented — the amount-driven subset only.** `eu.purse.dropin.payment.method.DisabledReason` is a
sealed class with just `PaymentFulfilled`, `MinAmountNotMet(minAmount, restToPay)`, and
`MaxAmountExceeded(maxAmount)` (both carrying `BigDecimal`, not `Long`) — the technical-failure
codes above (`TokenInitFailed`, `PluginIncompatible`, ...) only make sense once a method is
actually being rendered/submitted, which is future work once `methods/` coordinators exist (see
[Project Setup](../internal/project-setup.md#still-to-do)). The precedence itself lives in
`eu.purse.dropin.payment.method.MethodAvailability.disabledFor()`, and is applied in two places
against the same rule — mirroring web computing it at build time and then re-running
`updateMopDisabledState` on every `primaryReach` change
(`usp-widget/packages/headless-checkout/src/session/payment/payment.ts`):

- `PaymentSessionBuilder` calls it **once at session-build time** with `primaryReach` = the full
  session amount (no split has happened yet);
- as the shopper adds secondaries, `MethodAvailability.recompute()` (exposed as
  `DropinAndroid.methodsForPrimaryReach(remaining)`) re-runs it **live** against the
  `PaymentSplitter`'s dropping `remaining`, so a primary below its min switches to `MinAmountNotMet`
  and, once secondaries cover the whole amount, every method becomes `PaymentFulfilled`. The demo
  app pushes each recompute onto the already-rendered rows in place (without re-mounting the
  SecureFields form).

The precedence:

1. **Any existing disabled reason that isn't one of the three below is left untouched** — a
   fatal/technical error is never silently overwritten by an amount recalculation.
2. `primaryReach === 0` and this method isn't already part of an active split and the session
   isn't an information request → **`PaymentFulfilled`** (there's nothing left to pay with this
   method).
3. `minAmount != null && primaryReach < minAmount` (same exclusions) → **`MinAmountNotMet`**.
4. `maxAmount != null && primaryReach > maxAmount`, and the method is **not secondary** (same
   information-request exclusion) → **`MaxAmountExceeded`**. Secondary methods are never disabled
   for exceeding max — a gift card can legitimately hold more balance than the remaining amount;
   it just can't `take()` more than what's owed.
5. Otherwise, the disabled reason is cleared (method is selectable).

`minAmount`/`maxAmount` come straight from `MethodConfiguration.settings.payment_min_amount` /
`payment_max_amount` — see [`MethodConfiguration`](#methodconfiguration) above.

For this POC's "starter"/"full" methods, at minimum implement the `PaymentFulfilled` /
`MinAmountNotMet` / `MaxAmountExceeded` cases — a merchant session with e.g.
`payment_min_amount: 5.00` should visibly disable CB (or whichever method) when the amount owed
drops below that, exactly as the web widget would.

---

## Payment API — environments and endpoints

**Implemented** — `eu.purse.dropin.core.environment.PurseEnvironment`:

```kotlin
enum class PurseEnvironment(val wireValue: String, val paymentApiRoot: String) {
    DEVELOPMENT("development", "https://api.purse-dev.com/payment/v1"),
    TEST("test", "https://api.purse-test.com/payment/v1"),
    SANDBOX("sandbox", "https://api.purse-sandbox.com/payment/v1"),
    PRODUCTION("production", "https://api.purse-live.com/payment/v1"),
}
```

`DropinAndroid.init(widgetData, lockedEnvironment)` implements the environment-locking rule from
[Validation rules](#validation-rules) above: pass `PurseEnvironment.PRODUCTION` from a release
build to override whatever the session itself declares, rather than trusting it.

All calls carry header `x-api-key: {apiKey}` and are scoped to the current session:

```
POST {paymentApiRoot}/{entityId}/sessions/{sessionId}/init
POST {paymentApiRoot}/{entityId}/sessions/{sessionId}/validate
```

This is the **same generic pair of endpoints for every payment method** — CB, Google Pay, PayPal,
and Illicado all call `/init` then `/validate` with a method-specific `payment_data` payload. Only
CB's card data capture (the SecureFields form) and PayPal's approval step (the redirect) happen
outside this pair; everything else — including Illicado's PAN/CVV — goes straight through it.

---

## `/init` and `/validate`

> **Status:** `/init`/`/validate` are **implemented** — `eu.purse.dropin.core.api.PaymentApiClient`
> (a thin, synchronous `HttpURLConnection` client mirroring web's `FetchPaymentAPI`) backs every
> flow below it. Wired up for: the **secondary (Illicado gift card)** flow, via
> `SecondaryTokenMapper` + `DropinAndroid.getSecondaryToken(method, pan, cvv)` (`/init` only);
> **PayPal wallet**, via `PaypalWalletMapper` + `DropinAndroid.createPaypalOrder`/`validatePaypal`
> (both calls — see [Partner: paypal](../partners/paypal.md#implemented-android)); and **Google
> Pay**, via `GooglePayMapper` + `DropinAndroid.createGooglePayRequest`/`validateGooglePay` (both
> calls — see [Partner: adyen](../partners/adyen.md#implemented-android)). **CB's `/init`+`/validate`
> are not built yet** (SecureFields tokenizes in-app today, but the follow-up submit to the Payment
> API isn't wired).

`/init` is called once the shopper picks (or, for CB, finishes typing into) a method. Request
shape:

```json
{
  "partner": "illicado",
  "method": "giftcard",
  "amount": 480,
  "partner_data": {},
  "payment_data": { "card": { "number": "9250004781234567890", "csc": "123" } },
  "payment_preferences": { "registration": false }
}
```

Response includes `plugin_result.logs` (method-specific — e.g. a PayPal `order_id`, or redirect
data — see [Redirect Handling](redirect-handling.md)) and a call-level `status`.

> **Amount units — still unverified.** `SecondaryTokenMapper.buildInitPayload` currently sends
> `session.amount` **as-is** (a major-unit decimal — the same unit `PurseSessionParams` decodes,
> e.g. `payment_min_amount: 0.01`), matching what web passes (`paymentSplitter.primaryReach`).
> Note that web's `fromCents`/`toCents` round-trip in the splitter is **unit-preserving**, so it
> does *not* by itself prove the absolute unit — and web's derived `PaymentSession.amountCts`
> multiplies by **1000**, not 100 (`amountCts: Math.floor((legacySession.amount ?? 0) * 1000)` in
> `usp-widget/packages/headless-checkout/src/session/payment/utils.ts`). That field isn't used in
> any `/init`/`/validate` body here, but the mismatch means the exact amount encoding the API
> expects is **not confirmed**. Verify against a real `/init` round-trip / with the backend team
> before relying on it; if the API wants integer cents, adjust `buildInitPayload`.

`/validate` is called once the shopper has completed whatever the method needs (nothing further
for CB/Illicado, PayPal approval for PayPal, a 3DS challenge if triggered). It accepts an array —
one entry per method contributing to the payment, since secondary methods can be combined with a
primary in a single payment ("split"):

```json
{
  "results": [ { "id": "…", "method": "creditcard", "partner": "aci", "status": { "state": "SUCCESS", "code": "SUCCEEDED" }, "plugin_result": { "…": "…" } } ],
  "redirection": { "url": null, "form": null, "should_submit_widget": false },
  "global_status": "SUCCESS"
}
```

---

## Three layers of status

Don't conflate these three — the web codebase keeps them deliberately separate, and Android should
too:

1. **Widget/UI lifecycle** (local to the app, not sent over the wire) — mirrors
   `PurseHeadlessCheckoutSessionState`:
   ```kotlin
   enum class DropinSessionState { IDLE, READY, SUBMITTING, SUBMITTED, ERROR, EXPIRED, TERMINATED }
   ```
   Drives the loading/blocking UI (e.g. a full-screen overlay while `SUBMITTING`, or when
   `EXPIRED`).

2. **Per-call transaction status** — returned by every `/init` and `/validate` call:
   ```kotlin
   enum class StatusState { SUCCESS, ERROR, UNCERTAIN, FAILED, WAITING }
   // StatusCode: SUCCEEDED, FAILED, CARD_EXPIRED, INSUFFICIENT_FUNDS, REJECTED_BANK,
   //   BLOCKED_BY_CUSTOMER, WAITING_PARTNER_RESPONSE, ABORTED_BY_CUSTOMER, UNCERTAIN, ... (partial list)
   ```
   Drives immediate per-attempt UI feedback (spinner, inline error, "you'll be redirected").

3. **Backend session/authorization status** — the source of truth once the app (or, more likely,
   the merchant backend) re-queries the session after a redirect returns:
   ```kotlin
   enum class ClientSessionStatus { CREATED, CHOICE_IN_PROGRESS, SUBMITTED, EXPIRED, ABORTED_BY_CUSTOMER, ABORTED_BY_MERCHANT }
   enum class AuthorizationStatus { AUTHORIZED, PENDING, UNCERTAIN, ERROR, ABORTED, REFUSED }
   ```

**There is no single `REDIRECT_REQUIRED` status value anywhere in the reference implementation.**
"A redirect is required" is *inferred* from the `/validate` response containing a `redirection`
object with a non-null `url`/`form`, or a `plugin_result.logs.redirection_url` — see
[Redirect Handling](redirect-handling.md#the-redirect-contract) for the exact shape and priority
order.

---

## See also

- [Getting Started](../integration/getting-started.md)
- [Payment Methods](../integration/payment-methods.md)
- [Redirect Handling](redirect-handling.md)
- [POC Scope](../decisions/poc-scope.md)
