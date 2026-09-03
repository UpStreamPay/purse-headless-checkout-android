# Consumer API Reference — Headless Checkout (Android)

Audience: a merchant engineer building a checkout UI on top of this SDK. This page documents the
full **public** surface — every function, reactive property ("event"), and callback a host app can
call or observe — module by module. It does not cover internals (plugin protocol classes, mappers,
splitter math); see [Headless checkout architecture](../architecture/headless-checkout.md) for that.

Everything here is the Kotlin analog of web's `@upstreampay/headless-checkout` +
`@upstreampay/partner-ui` public exports. Where the shape differs from web, it's called out.

---

## 1. Bootstrapping

### Plugins are active by default — no setup call needed

Every plugin `:partner-ui` ships (Google Pay, Klarna, PayPal, Purse-vaulted card, the generic
redirection template) is registered automatically; you don't call `GooglePayModule.register()` /
`KlarnaModule.register()` / `PaypalModule.register()` / `CardModule.register()` /
`RedirectionModule.register()` yourself. Whether a method actually shows
up is gated by the **session** alone — the same thing that already decides it server-side, in the
merchant's Purse configuration. `paymentMethods` simply won't include a method the session doesn't
offer.

If you need to suppress a shipped method for a specific app build (e.g. its UI isn't ready yet even
though the backend might offer it), opt it out explicitly:

```kotlin
PluginRegistry.disable(Partner.Klarna, Method.Installments)   // eu.purse.partnerui
```

`PluginRegistry.enable(partner, method)` reverses it. Calling `GooglePayModule.register()` /
`KlarnaModule.register()` yourself is harmless (idempotent) but unnecessary — they exist mainly for
tests that want to register a plugin in isolation.

### `PurseHeadlessCheckout.init`

```kotlin
fun init(
    widgetData: String,
    lockedEnvironment: PurseEnvironment? = null,
    hooks: PurseCheckoutHooks? = null,
    options: PluginOptions = PluginOptions(),
): PurseHeadlessCheckout
```

| Param | Meaning |
|---|---|
| `widgetData` | The base64-encoded `widget.data` string from the merchant backend's Client Session response. |
| `lockedEnvironment` | Overrides whatever `widgetData` declares. **A release build must always pass `PurseEnvironment.PRODUCTION`** rather than trusting a session-declared environment. |
| `hooks` | Merchant hooks around "create payment" — see [`PurseCheckoutHooks`](#pursecheckouthooks) below. |
| `options` | Presentation preferences forwarded to every plugin (locale, theme, card-form texts). |

**Throws:** `PurseSessionException.SessionMalformed` if `widgetData` can't be decoded, or the
session is missing `id` / `expiration_date`.

Call this once per checkout session. The returned `PurseHeadlessCheckout` instance is the object
every other API in this document hangs off.

### `PurseCheckoutHooks`

Web's `PurseHeadlessCheckoutHooks` (`onBeforeValidate` / `onAfterValidate` / `redirectionHandler`):

```kotlin
class PurseCheckoutHooks(
    val onBeforeValidate: (suspend (splits: List<PaymentSplit>) -> Unit)? = null,
    val onAfterValidate: (suspend () -> Unit)? = null,
    val onRedirection: (suspend (action: RedirectAction) -> Boolean)? = null,
)
```

| Hook | When | If it throws |
|---|---|---|
| `onBeforeValidate` | After the item payloads are built, right before `POST /validate`. Skipped when a plugin auto-submits (web's `xpay` trigger). | The submit is **aborted before anything is sent**: `submitPayment` throws `PurseCheckoutException.HookFailed` (code `ON_BEFORE_VALIDATE_HOOK_FAILED`, your exception as `cause`), `sessionState` returns to `READY`, split and collected data untouched. |
| `onAfterValidate` | After `/validate` answers, before plugin `postValidation` (so before any redirect). | Swallowed, like web — reported as a `HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR` monitoring event. |
| `onRedirection` | `/validate` asked for a redirect, resolved to a `RedirectAction` (`Get` / `PostForm` / `PostHtml`), before the SDK opens its in-app WebView. Return `true` to take it over (the SDK opens nothing; confirm the outcome yourself with `paymentStatus()`), `false` to let the SDK proceed. Consulted by every coordinator that can redirect. | **Nothing is opened**; the failure reaches the payment element's `onError` as `"onRedirection hook failed: …"` (typed internally as `HookFailed`, code `POST_VALIDATE_FAILED` — web has no dedicated code, its handler runs inside `postValidation`), the split stays intact and the shopper can submit again. |

---

## 2. Session identity (read-only properties)

| Property | Type | Meaning |
|---|---|---|
| `params` | `PurseSessionParams` | Decoded `widget.data` (`apiKey`, `apiUrl`, `environment`, `entityId`, `paymentSession`). |
| `session` | `PaymentSession` | The built session model (amount, currency, expiration, vault config, means of payment). |
| `paymentApiRoot` | `String` | Resolved Payment API root; calls go to `{root}/{entityId}/sessions/{sessionId}/…`. |
| `currency` | `String` | Session currency code. |
| `isInformationRequest` | `Boolean` | Whether this session is a zero-amount information request rather than a real charge. |
| `currentSessionId` | `String` | The Payment API session id (`session.id`). |

---

## 3. Reactive state — "events" (`StateFlow`)

The reactive surface is exposed as `StateFlow` — the Kotlin analog of web's Svelte stores. Collect
it from a coroutine scope; there is no callback-based equivalent.

| Property | Type | Fires when |
|---|---|---|
| `sessionState` | `StateFlow<SessionState>` | Lifecycle transitions: `READY` → `SUBMITTING` → `SUBMITTED`/`ERROR`, or `EXPIRED` at init if the session already expired. |
| `paymentMethods` | `StateFlow<List<PaymentItem>>` | Any time the split changes — each item's `disabled` state is recomputed against the new `remainingAmountToPay`. |
| `remainingAmountToPay` | `StateFlow<BigDecimal>` | After `setPrimary`, `applySecondary`, or `removeSecondary`. |
| `isPaymentFulfilled` | `StateFlow<Boolean>` | Same triggers as above, plus every `setFulfilled` — `true` once the split covers the full amount **and** every item in it whose plugin collects a form reports that form complete and valid (see [§6](#form-completeness-gates-the-payment)). |
| `amountSplit` | `StateFlow<List<PaymentSplit>>` | Same triggers — the current list of `(amount, source)` slices that `submitPayment` will send. |

Typical wiring in an Activity/Fragment:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        checkout.paymentMethods.collect { methods -> renderMethodList(methods) }
    }
}
```

There is no `onDataUpdate`/`onAmountUpdate` push into a rendering surface the way web's
`<purse-partner-ui>` element gets it — the host owns re-rendering on every emission.

---

## 4. Payment model types

### `PaymentItem` (sealed)

One `protocols.{partner}.{method}` entry. `id` follows web's `"{partner}-{method}"` convention
(e.g. `"purse-creditcard"`, `"paypal-wallet"`).

| Member | Type | Meaning |
|---|---|---|
| `id` | `String` | Stable id. |
| `partner` / `method` | `Partner` / `Method` | Wire-value enums. |
| `configuration` | `MethodConfiguration` | Raw `configurations` block (labels, template, vault, settings). |
| `disabled` | `DisabledState?` | Why this item currently can't be selected — `null` if selectable. |
| `isSecondary` | `Boolean` | `true` for `SecondaryMethod`. |
| `minAmount` / `maxAmount` | `BigDecimal?` | From `configuration.settings`. |

Two concrete subtypes:

**`PrimaryMethod`** — exactly one covers the amount left after secondaries.
- `integrationType: IntegrationMode?` — `redirection` / `popup`, from `merchant_specifics`.
- `supportedBrands: List<CardBrand>` — card schemes accepted; empty when not vaulted.

**`SecondaryMethod`** — a co-payment method (gift card, voucher, loyalty). No extra fields beyond
the base `PaymentItem`.

### `DisabledState` (sealed)

| Code | Meaning |
|---|---|
| `PaymentFulfilled` | The remaining amount owed by a primary is already zero. |
| `MinAmountNotMet(minAmount, restToPay)` | The item's minimum is above what's left to pay. |
| `MaxAmountExceeded(maxAmount)` | Never applies to secondaries — a gift card can hold more than the order needs. |
| `PluginIncompatible` | The resolved plugin rejected the current client/session in its eligibility check. |

Web additionally has technical/fatal codes (`TOKEN_INIT_FAILED`, `PARTNER_UI_FATAL_ERROR`, …) that
only arise once a plugin is actually rendering/submitting — out of scope here; see the pain points
recap.

### `PaymentSplit`

```kotlin
data class PaymentSplit(val amount: BigDecimal, val source: SplitSource)
```

One slice of the order amount assigned to a `SplitSource` (either a `PrimaryMethod`/`SecondaryMethod`
or an applied `SecondaryToken`). The full list is what `submitPayment` sends to `/validate`.

---

## 5. Building the split (functions)

| Function | Signature | Notes |
|---|---|---|
| `primaryMethods` | `List<PrimaryMethod>` | Filtered view of `paymentMethods.value`. |
| `secondaryMethods` | `List<SecondaryMethod>` | Filtered view of `paymentMethods.value`. |
| `setPrimary` | `fun setPrimary(method: PrimaryMethod?)` | Choose (or clear, with `null`) the primary covering the remaining amount. Triggers a `refresh()` of all reactive state. |
| `secondaryConfig` | `fun secondaryConfig(method: SecondaryMethod): SecondaryConfig?` | The per-partner form/take-policy rules for a secondary method — `null` if none registered (e.g. `ancv-connect`, not ported yet). |
| `getSecondaryToken` | `suspend fun getSecondaryToken(method: SecondaryMethod, pan: String, cvv: String? = null): SecondaryToken` | `POST /init` — tokenizes a card and returns its balance **without** touching the split. Validate PAN/CVV via `secondaryConfig` first. |
| `applySecondary` | `fun applySecondary(token: SecondaryToken, amount: BigDecimal? = null)` | Draws an already-tokenized `token` into the split, clamped to `min(amount, balance, remaining, limit)`. `amount == null` takes as much as the order needs. |
| `removeSecondary` | `fun removeSecondary(token: SecondaryToken)` | Removes a previously applied token from the split. |
| `appliedSecondaries` | `List<SecondaryToken>` | Applied tokens, in the order they were added. |

**`getSecondaryToken` throws:**
- `PurseCheckoutException.NoSecondaryConfig` — no config registered for this `(partner, method)`.
- `PurseCheckoutException.InvalidSecondaryCard` — client-side PAN/CVV validation failed.
- `PurseCheckoutException.SecondaryTokenizationFailed` — `/init` wasn't successful, or the card
  carries no balance.
- `PaymentApiException` — network/transport failure.

Blocking network work happens via `suspend`; move it to `Dispatchers.IO` at the call site.

### `SecondaryToken`

```kotlin
data class SecondaryToken(
    val id: String, val partner: Partner, val method: Method,
    val pan: String, val cvv: String?, val name: String,
    val balance: BigDecimal, val totalBalance: BigDecimal = balance,
    val expirationDate: String = "", val takePolicy: SecondaryTakePolicy = SecondaryTakePolicy.NONE,
    val limit: BigDecimal? = null,
)
```

`balance` is what's spendable now; `totalBalance` is the full instrument balance when a partner
distinguishes it. `takePolicy` is `NONE` (splittable — `applySecondary`'s `amount` caps the draw)
or `MAX` (non-splittable — always takes the full cap regardless of `amount`).

### `SecondaryConfig`

The interface a merchant's form UI queries to drive validation before calling `getSecondaryToken`:

| Member | Meaning |
|---|---|
| `takePolicy: SecondaryTakePolicy` | `NONE` / `MAX` — see above. |
| `requiresCvv(pan: String): Boolean` | Whether to show/require a CVV field for this PAN. |
| `validatePan(pan: String): Boolean` | Client-side PAN format check. |
| `validateCvv(cvv: String): Boolean` | Client-side CVV format check (default: always valid). |

Registered partners today: `illicado-giftcard`, `buybox-giftcard`, `maxxing-loyaltycard`,
`ogloba-giftcard`, `mybeezbox-giftcard`, `easy2play-giftcard`, `easy2play-voucher`.
`ancv-connect` is not yet registered (needs a two-phase holder→poll `/init` flow).

### Loan simulation (BNPL)

Some instalment methods quote their schedule before the shopper commits — web's
`method.simulateLoan(product)`. A method advertises it through the `simulable` configuration
label; everything else fails fast with a dedicated code rather than a partner error.

| Member | Signature | Notes |
|---|---|---|
| `PrimaryMethod.simulable` | `val simulable: Boolean` | `configuration.labels` contains `"simulable"`. Check it before showing a "see instalments" affordance. |
| `simulateLoan` | `suspend fun simulateLoan(method: PrimaryMethod, product: LoanProduct): LoanSimulation` | `POST /simulation/{entityId}/{partner}/{method}` (entity-scoped, not under the session). Runs on `Dispatchers.IO`. |
| `simulateLoans` | `suspend fun simulateLoans(method: PrimaryMethod, product: LoanProduct): List<LoanSimulation>` | The `/multi` variant — every schedule the partner offers. Candidates without schedule data are skipped, so the list may be empty. |

```kotlin
data class LoanProduct(val amount: BigDecimal, val currencyCode: String)

data class LoanSimulation(
    val initialInstallmentAmount: BigDecimal, val finalInstallmentAmount: BigDecimal,
    val installmentAmount: BigDecimal, val installmentCount: Int,
    val totalCost: BigDecimal, val totalAmount: BigDecimal,
    val legalText: String?, val aprc: BigDecimal?,
)
```

`totalCost` is the price of the credit (excluding the financed amount); `totalAmount` is what the
shopper pays in all — `(count - 1) × installmentAmount + initial + final`, rounded to two decimals,
exactly web's arithmetic. Like web, the schedule carries **counts and amounts only**: the API
returns no per-instalment dates.

**Throws:**
- `PurseCheckoutException.MethodNotSimulable` (`METHOD_IS_NOT_SIMULABLE`) — the method has no
  `simulable` label. No API call is made.
- `PurseCheckoutException.LoanSimulationFailed` (`INVALID_LOAN_SIMULATION`) — the API answered
  without a `plugin_result`.
- `PaymentApiException` — network/transport failure.

---

## 6. Interactive plugins (Google Pay, PayPal, Klarna, Card, Redirection)

Interactive plugins collect data outside the headless split/validate flow (a payment sheet, a
native widget, a card form) before `submitPayment` runs. This section is the **API surface**; each
PSP's own behaviour — what its `/init` returns, where its native step sits relative to `/validate`,
its quirks and failure modes, with sequence diagrams — has a page of its own under
**[docs/plugins](../plugins/README.md)**.

Three pieces make up the pattern for every such plugin:

1. A **`*Host` interface** in `:partner-ui` — what the plugin's coordinator needs from the checkout,
   implemented by `PurseHeadlessCheckout`.
2. A **`*Coordinator`** class — drives the plugin's native SDK, reports outcomes via a `Listener`.
3. A **`setCollected`** call — stashes sheet/widget output so `submitPayment`'s `/validate` payload
   can use it.

### Form completeness gates the payment

A plugin that collects a form (today: the card) reports that form's validity through
`setFulfilled(item, …)`. While any item in the current split reports `false`:

- `isPaymentFulfilled` is `false`, so a "Pay" button bound to it is disabled; and
- `submitPayment` throws `PurseCheckoutException.ItemNotFulfilled`.

`CardCoordinator` does this for you: mounting the SecureFields form reports `false`, and every
SecureFields validity change is mirrored through — so **an empty or half-filled card form can never
look payable**, and the coordinator's own `submit()` is a no-op until the form validates. Web reaches
the same guarantee from the other end (`setAsPrimarySource` throws `ELEMENT_NOT_FULFILLED` for an
element whose `fulfilled` event hasn't fired); the reactive form here lets you select a method
*before* its form is filled in, which is what a mobile "pick a row, then fill it in" flow needs.

Items that never call `setFulfilled` are **ungated** — a redirection method, or an xpay like Google
Pay whose sheet *is* the form. That matches web, where those plugins dispatch no `fulfilled` event
either. If you write your own coordinator around a form, call `setFulfilled` from it.

### Generic checkout functions backing every interactive plugin

| Function | Signature | Notes |
|---|---|---|
| `setCollected` | `fun setCollected(item: PaymentItem, data: Map<String, Any?>)` | Stash data an interactive plugin collected (e.g. a Google Pay token + network), keyed to `item`. Threaded into that item's `preValidation` on the next `submitPayment`. |
| `setFulfilled` | `fun setFulfilled(item: PaymentItem, fulfilled: Boolean)` | Report whether `item`'s own form is complete and valid — see [below](#form-completeness-gates-the-payment). Coordinators that collect a form call this for you. |
| `pluginContext` | `fun pluginContext(item: PaymentItem): PartnerUiContext` | Build the context object a plugin coordinator needs to drive its own steps before submit. |
| `googlePayHost` | `fun googlePayHost(item: PaymentItem): GooglePayHost` | The `GooglePayHost` implementation for `item`. |
| `klarnaHost` | `fun klarnaHost(item: PaymentItem, klarnaReturnUrl: String): KlarnaHost` | The `KlarnaHost` implementation for `item`. `klarnaReturnUrl` falls back to the session's own return URL if you don't have a better one. |
| `paypalHost` | `fun paypalHost(item: PaymentItem): PaypalHost` | The `PaypalHost` implementation for `item`. No return URL argument — the PayPal SDK supplies its own, and the matching Activity ships in `:partner-ui`. |
| `cardHost` | `fun cardHost(item: PaymentItem): CardHost` | The `CardHost` implementation for `item` — the card `PrimaryMethod` (fresh card, full form) or one of its saved `PaymentToken`s (a CVV-only form when `token.requiresCvv`, no form otherwise; the token reaches the plugin as `PartnerUiContext.token`). Throws `IllegalArgumentException` for anything else. |
| `redirectionHost` | `fun redirectionHost(item: PaymentItem): RedirectionHost` | The `RedirectionHost` implementation for `item`. Not tied to a partner or method — ask for one per item the session routes to the redirection template. |

### `PartnerUiContext`

The object every plugin lifecycle hook receives:

```kotlin
data class PartnerUiContext(
    val partner: Partner, val method: Method, val session: LegacyPaymentSession,
    val amount: BigDecimal, val api: PaymentApiClient,
    val register: Boolean = false, val token: String? = null,
    val collected: Map<String, Any?> = emptyMap(),
    val options: PluginOptions = PluginOptions(),
    val dispatchEvent: (PluginEvent) -> Unit = {},
)
```

Most consumers won't build this directly — `pluginContext` and the `*Host` builders above do it for
you. `amount` is what a primary would still cover; `collected` carries whatever `setCollected` last
stashed for this item.

### Google Pay

> Deep dive — `/init` shapes per gateway, sequence diagram, quirks, failure modes:
> **[docs/plugins/google-pay.md](../plugins/google-pay.md)**.

**`GooglePayHost`** (implemented by `PurseHeadlessCheckout.googlePayHost(item)`):

| Member | Meaning |
|---|---|
| `environment: PurseEnvironment` | Drives Google Pay's `WalletConstants.ENVIRONMENT_TEST`/`PRODUCTION`. |
| `redirectionReturnUrl: String?` | Session's own configured return URL — threaded into a genuine 3-D Secure redirect purely so the in-app WebView recognizes reaching it as completion. |
| `buildContext(): PartnerUiContext` | — |
| `setCollected(data: Map<String, Any?>)` | — |
| `suspend submitPayment(): ValidationResult` | — |

**`GooglePayCoordinator(activity: ComponentActivity, listener: Listener)`** — construct in the host
Activity's `onCreate` (it registers `ActivityResult` launchers, which must happen before the
Activity is `STARTED`).

| Function | Signature | Notes |
|---|---|---|
| `prepare` | `fun prepare(host: GooglePayHost)` | Runs `/init` + Worldline refine, checks `isReadyToPay`. Call once the session is initialized. |
| `launchSheet` | `fun launchSheet()` | Shows the Google Pay sheet. Call after `Listener.onReady(true)` and the button is tapped. No-op while a prior flow is still in progress. |

**`GooglePayCoordinator.Listener`** callbacks:

| Callback | Fires |
|---|---|
| `onReady(ready: Boolean)` | After `prepare()` resolves `isReadyToPay` — show the button only when `true`. |
| `onResult(summary: String, globalStatus: String? = null)` | Terminal, non-error outcome with no follow-up redirect needed. `globalStatus` is the `/validate` response's own `global_status` when the outcome came from one, and `null` after a redirect return — see the note below. |
| `onRequiresAction()` | A 3-D Secure step is being launched (informational). |
| `onPaymentId(paymentId: String)` | Informational — a `payment_id` extracted from the widget's own confirmation-page redirect, when present. Never required for the payment to resolve. |
| `onCanceled()` | Shopper dismissed the sheet or the redirect WebView without completing. |
| `onError(message: String?)` | Any failure — sheet, `/validate`, or redirect launch. |

**Note on `onResult`/redirect return:** a returned-from-redirect `Approved` outcome means only that
the shopper came back into the app — **not** that the payment succeeded. That's precisely the case
`onResult` reports with a `null` `globalStatus`: don't render it as either success or failure. Re-check
status independently (e.g. `sessionTransactions()`) rather than trusting it. Note also that a genuine
redirect *replaces* the widget-confirmation one, so this is the same case where `onPaymentId` has
nothing to report. See the [pain points recap](../decisions/pain-points-recap.md).

`onResult`'s `globalStatus` is not a success flag either — a non-`SUCCESS` value is a declined payment,
not an error. The demo's own three-way mapping (`PaymentOutcome.of`) is the shape to copy:

```kotlin
when {
    globalStatus == null -> Unconfirmed   // came back from a redirect — go and check the session
    globalStatus.equals("SUCCESS", true) -> Authorized
    else -> Failed
}
```

### PayPal

> Deep dive — the out-of-app approval round trip, sequence diagram, quirks, failure modes:
> **[docs/plugins/paypal.md](../plugins/paypal.md)**.

**`PaypalHost`** (implemented by `PurseHeadlessCheckout.paypalHost(item)`):

| Member | Meaning |
|---|---|
| `environment: PurseEnvironment` | `PRODUCTION` → PayPal `Environment.LIVE`, everything else → `SANDBOX`. |
| `redirectionReturnUrl: String?` | Same role as Google Pay's — a *chained* 3-D Secure hop after `/validate`, **not** PayPal's own approval return (the SDK supplies that itself). |
| `buildContext(): PartnerUiContext` | — |
| `setCollected(data: Map<String, Any?>)` | — |
| `suspend submitPayment(): ValidationResult` | — |

**`PaypalCoordinator(activity: ComponentActivity, listener: Listener)`** — construct in the host
Activity's `onCreate` (it registers `ActivityResult` launchers, which must happen before the
Activity is `STARTED`).

| Function | Signature | Notes |
|---|---|---|
| `prepare` | `fun prepare(host: PaypalHost)` | Attaches the host and reports readiness. **No network work** — see the note below. Call once the session is initialized. |
| `submit` | `fun submit()` | Runs `/init` (creating the PayPal order), then launches PayPal's approval; captures via `/validate` once the buyer approves. Call on the branded button's tap. No-op while a prior flow is still in progress. |

**`PaypalCoordinator.Listener`** callbacks:

| Callback | Fires |
|---|---|
| `onReady(ready: Boolean)` | After `prepare()` — show the branded `PayPalButton` only when `true`. |
| `onResult(summary: String, globalStatus: String? = null)` | Terminal, non-error outcome with no follow-up redirect needed — same semantics as Google Pay's. |
| `onRequiresAction()` | PayPal's approval is being launched — and again if `/validate` chains a 3-D Secure step. |
| `onPaymentId(paymentId: String)` | Same as Google Pay's — informational, when `/validate` carries one. |
| `onCanceled()` | Buyer dismissed PayPal's approval, or the chained redirect, without completing. |
| `onError(message: String?)` | Any failure — `/init`, a missing session `client_id`, the approval failing to present, `/validate`, or redirect launch. |

**Note on where `/init` runs.** Unlike every other coordinator, PayPal's `prepare` makes no network
call: the `/init` that creates the PayPal order waits for `submit()`, so merely showing the button
never leaves an orphan order behind. Nothing to do on your side beyond calling `setPrimary` on the
tap, as you would for Google Pay.

**No manifest work.** PayPal's approval return goes to an Activity `:partner-ui` ships (scheme
`${applicationId}.paypal`), not to yours — the opposite of Klarna, see [§7](#7-declaring-your-own-deep-link-for-klarna).

Same caveats as Google Pay on `onResult`/redirect return, above.

### Klarna

> Deep dive — why `authorize()` runs after `/validate`, sequence diagram, quirks, failure modes:
> **[docs/plugins/klarna.md](../plugins/klarna.md)**.

**`KlarnaHost`** (implemented by `PurseHeadlessCheckout.klarnaHost(item, returnUrl)`):

| Member | Meaning |
|---|---|
| `returnUrl: String` | The deep-link URL `KlarnaPaymentView.returnURL` should carry. Must resume the **same Activity instance** hosting the widget — see [§7](#7-declaring-your-own-deep-link-for-klarna) below. |
| `buildContext(): PartnerUiContext` | — |
| `setCollected(data: Map<String, Any?>)` | — |
| `suspend submitPayment(): ValidationResult` | — |

**`KlarnaCoordinator(activity: ComponentActivity, container: ViewGroup, listener: Listener)`** —
construct in the host Activity's `onCreate`. `container` is the `ViewGroup` the native widget
renders into.

| Function | Signature | Notes |
|---|---|---|
| `prepare` | `fun prepare(host: KlarnaHost)` | Runs `/init`, inflates the native `KlarnaPaymentView` into `container`. |
| `submit` | `fun submit()` | Runs `/validate`, which itself triggers Klarna's own `authorize()`/`finalize()` internally (via `KlarnaPlugin.postValidation`, only once `/validate` succeeds) — not something `submit()` drives as a separate step. Call once `Listener.onReady(true)` fired and the shopper tapped your submit action. |

**`KlarnaCoordinator.Listener`** callbacks:

| Callback | Fires |
|---|---|
| `onReady(ready: Boolean)` | The widget finished loading content — show the pay trigger. |
| `onResult(summary: String, globalStatus: String? = null)` | Terminal, non-error outcome with no follow-up redirect needed — same semantics as Google Pay's. |
| `onRequiresAction()` | A redirect step (external banking/identity-verification app) is being launched. |
| `onPaymentId(paymentId: String)` | Same as Google Pay's — informational, when `/validate` carries one. |
| `onCanceled()` | Klarna declined to authorize/finalize, or the redirect was dismissed. |
| `onError(message: String?)` | Any failure — widget load, `/validate`, authorize/finalize, or redirect launch. |

Same caveats as Google Pay: a returned `Approved` redirect outcome is not proof of payment success,
and an `onError` before any submit (a widget that fails to load) is not a failed payment — leave the
shopper able to pick another method rather than showing them a failure screen.

### Card (Purse vault / SecureFields)

> Deep dive — session prerequisites, sequence diagram, quirks, failure modes:
> **[docs/plugins/card-purse-vault.md](../plugins/card-purse-vault.md)**.

**Compose-first** — unlike `GooglePayCoordinator`/`KlarnaCoordinator` above (plain classes driving
View-system widgets), the card plugin's interactive half is a `@Composable` function. Rendering it
goes through the SDK's own `PurseCardForm(coordinator)` (or SecureFields' bare `VaultForm`/`VaultField`
when you want no labels or messages) — your app needs a Compose entry point somewhere in its view
hierarchy (this SDK's own demo is Compose throughout; a `ComposeView` embedded in an otherwise
View-based layout works just as well).

**`PurseCardForm(coordinator: CardCoordinator, modifier: Modifier = Modifier)`** — the card form
web's hosted form renders: each secure field with its **label** above, its **help text** under it
while focused, and its **error message** in place of the help text when the shopper leaves it empty
(`requiredError`) or SecureFields rejects its content (`formatError`). It renders the four fields in
`FRESH_CARD`, only the CVV in `TOKEN_CVV`, nothing in `TOKEN_NO_CVV`, and sizes itself — no fixed
height needed. This is what `PaymentElementUi` shows for a card element.

Copy comes from `PluginOptions.cardFormTexts` (`CardFormTexts`, one `CardFormTexts.FieldTexts` per
field: `label`, `placeholder`, `helperText`, `requiredError`, `formatError`), every `null` falling
back to the SDK's localized `purse_card_*` resources — web's `hostedForm` translations, in English and
French. Styling comes from `PluginOptions.theme` (`PurseTheme`): the `input*` scopes reach
SecureFields' `VaultStyles`, `label*` and `helperText*` reach the texts around them, `global*` is the
base every scope falls back to and `gap` the spacing between fields. `PurseTheme.resolve()` applies
web's `Theme.build()` order — scope variant → scope → global variant → global — over web's default
theme, so a partial theme never leaves a hole. See recipe 16.

One trap worth naming: `rememberCardCoordinator` keys its SecureFields instance on the `host` object,
so build the host with `remember(...)` rather than inline —

```kotlin
val host = remember(method.id) { checkout.cardHost(method) }   // NOT checkout.cardHost(method)
val coordinator = rememberCardCoordinator(host, listener)
```

— otherwise every recomposition hands it a new host, tearing down and rebuilding the card form (and
losing whatever the shopper has typed) each time anything else on the screen changes.

**`CardHost`** (implemented by `PurseHeadlessCheckout.cardHost(item)`):

| Member | Meaning |
|---|---|
| `environment: PurseEnvironment` | Drives SecureFields' own `VaultEnvironment` (a distinct enum — converted internally). |
| `vaultTenantId: String?` | Session's `vault.vendor_front_data.purse.tenant_id` — required by SecureFields' `tenantId` param. |
| `supportedBrands: List<CardBrand>` | `item.configuration.vault.supportedBrands` — which card schemes SecureFields should accept. Empty falls back to VISA/MASTERCARD/CB. |
| `redirectionReturnUrl: String?` | Same role as `GooglePayHost.redirectionReturnUrl` — threaded into a genuine 3-D Secure redirect. |
| `buildContext(): PartnerUiContext` | — |
| `setCollected(data: Map<String, Any?>)` | — |
| `setFulfilled(fulfilled: Boolean)` | The form's completeness, forwarded to `PurseHeadlessCheckout.setFulfilled(item, …)` — the coordinator calls it, you don't. |
| `suspend submitPayment(): ValidationResult` | — |

**`rememberCardCoordinator(host: CardHost, listener: CardCoordinator.Listener): CardCoordinator`** —
a composable, not a class you construct. No `onCreate`/`STARTED` lifecycle caveat (unlike Google
Pay/Klarna) — call it from anywhere in your composition; its 3-D Secure redirect launcher
(`rememberLauncherForActivityResult`) manages its own lifecycle.

| Member | Signature | Notes |
|---|---|---|
| `sdk` | `val sdk: SecureFieldsAndroid` | The live SecureFields instance. Render it through `PurseCardForm(coordinator)` (labels, help texts, errors, theme), or with SecureFields' bare `VaultForm(coordinator.sdk)` / `VaultField(coordinator.sdk.cardNumber)` for your own layout — only when `mode.rendersForm`. |
| `theme` | `val theme: PurseTheme.Resolved` | The merchant's `PluginOptions.theme` resolved over web's defaults — what `PurseCardForm` styles with, exposed for a custom layout that wants the same values. |
| `mode` | `val mode: CardCoordinator.Mode` | `FRESH_CARD` (a `PrimaryMethod` host), `TOKEN_CVV` (a `PaymentToken` host whose `requiresCvv` is `true`: CVV-only form) or `TOKEN_NO_CVV` (payable as-is, nothing to render). `mode.rendersForm` is `false` only for the last. |
| `formValid` | `var formValid: Boolean` (Compose state) | Whether the card form currently validates — `false` until every configured field is valid (always `true` in `TOKEN_NO_CVV`). Drive your "Pay" button's `enabled` off this, or off `isPaymentFulfilled`, which the coordinator keeps in step for you. |
| `submit` | `fun submit()` | Tokenizes via SecureFields (the card, or just the CVV of a saved one), then runs `/init`+`/validate` through `host`; in `TOKEN_NO_CVV` it skips tokenization. Call once `formValid` is `true` and the shopper tapped "Pay". No-op while a prior tap's flow is still in progress, and a no-op (never an error — an unfilled form is not a failed payment) while the form isn't complete and valid. |

**`CardCoordinator.Listener`** callbacks:

| Callback | Fires |
|---|---|
| `onReady(ready: Boolean)` | The form's validity changed (mirrors `formValid`, provided as a callback too for symmetry with the other two coordinators). |
| `onResult(summary: String, globalStatus: String? = null)` | Terminal, non-error outcome with no follow-up redirect needed — same semantics as Google Pay's. |
| `onRequiresAction()` | A 3-D Secure step is being launched (informational). |
| `onPaymentId(paymentId: String)` | Same as Google Pay's — informational, when `/validate` carries one. |
| `onCanceled()` | The redirect was dismissed without completing. |
| `onError(message: String?)` | Any failure — tokenization, `/validate`, or redirect launch. |

Same caveat as Google Pay/Klarna: a returned `Approved` redirect outcome is not proof of payment
success.

### Redirection (generic template)

> Deep dive — how detection works, the browser block, sequence diagram, quirks, failure modes:
> **[docs/plugins/redirection.md](../plugins/redirection.md)**.

The odd one out: it isn't a PSP integration but a **template**, claimed by whichever methods the
session marks `configurations.template = "redirection"` (or whose
`merchant_specifics.integration_mode` says the same). So you don't check for a partner or a method —
you ask the registry:

```kotlin
fun isRedirection(item: PrimaryMethod) =
    PluginRegistry.getPlugin(item.partner, item.method, checkout.session.source) is RedirectionPlugin
```

There is nothing to render. `/init` runs on selection (it consumes a step against the session, so
don't fire one per offered row on screen entry), `onReady(true)` follows immediately, and your own
"Pay" button does the rest.

**`RedirectionHost`** (implemented by `PurseHeadlessCheckout.redirectionHost(item)`):

| Member | Meaning |
|---|---|
| `redirectionReturnUrl: String?` | Same role as `GooglePayHost.redirectionReturnUrl` — the WebView's "known terminal page" hint, and the check that suppresses the widget's own confirmation page. |
| `buildContext(): PartnerUiContext` | — |
| `setCollected(data: Map<String, Any?>)` | — |
| `suspend submitPayment(): ValidationResult` | — |

**`RedirectionCoordinator(activity, listener)`** — construct in `onCreate` (it registers an
`ActivityResult` launcher). One instance drives one item at a time; `prepare` rebinds it.

| Member | Signature | Notes |
|---|---|---|
| `prepare` | `fun prepare(host: RedirectionHost)` | `POST /init`, stash `step_id` + the browser block, then `onReady(true)`. Call when the shopper selects the method. |
| `submit` | `fun submit()` | `/validate`, then open the PSP's hosted page. No-op while a prior tap's flow is in progress. |

**`RedirectionCoordinator.Listener`** callbacks:

| Callback | Fires |
|---|---|
| `onReady(ready: Boolean)` | `/init` returned — there's no form, so the shopper can submit right away. |
| `onResult(summary: String, globalStatus: String? = null)` | Terminal. `globalStatus == null` after a redirect return means the outcome is unknown; a non-null one whose summary says `"(no redirect returned)"` means `/validate` carried no page to open — see the deep dive. |
| `onRequiresAction()` | The PSP's hosted page is being opened — for this template, the payment step itself. |
| `onPaymentId(paymentId: String)` | Same as Google Pay's — informational, when `/validate` carries one. |
| `onCanceled()` | The shopper dismissed the page without completing. |
| `onError(message: String?)` | `/init`, `/validate`, or redirect launch failed. |

Same caveat as the other three: a returned `Approved` redirect outcome is not proof of payment
success.

### Registering a new plugin, or opting a built-in one out (`PluginRegistry`)

The `register*` functions are only relevant if you're adding a plugin that isn't bundled in
`:partner-ui` itself — built-in ones are already registered, see
[§1](#plugins-are-active-by-default--no-setup-call-needed).

| Function | Signature |
|---|---|
| `register` | `fun register(partner: Partner, method: Method, builder: PluginBuilder)` |
| `registerFallback` | `fun registerFallback(method: Method, builder: PluginBuilder)` |
| `registerVault` | `fun registerVault(vendor: VaultVendor, builder: PluginBuilder)` |
| `registerGeneric` | `fun registerGeneric(template: String, builder: PluginBuilder)` — a reusable **template** keyed by `configurations.template`, naming no partner or method; the session decides which methods it covers. |
| `disable` | `fun disable(partner: Partner, method: Method)` — suppress a built-in plugin for this app build. |
| `enable` | `fun enable(partner: Partner, method: Method)` — reverse a previous `disable`. |
| `getPlugin` | `fun getPlugin(partner: Partner, method: Method, session: LegacyPaymentSession): PartnerUiPlugin?` |

Resolution order: `disable`d check → vault vendor → generic template (strict equality) →
`merchant_specifics.integration_mode = redirection` → exact `(partner, method)` → method fallback.
`getPlugin` is also the honest way to ask *what will actually handle this item* — e.g.
`getPlugin(...) is RedirectionPlugin` to decide how to render a row, rather than re-reading `template`
yourself and disagreeing with the registry. Full chain and rationale:
[docs/plugins/README.md](../plugins/README.md#resolution--which-plugin-handles-an-item).

---

## 7. Declaring your own deep link for Klarna

Unlike Google Pay's 3-D Secure return (a self-contained Activity `:partner-ui` ships), Klarna's
`returnURL` must resume the **same Activity** hosting the live `KlarnaPaymentView`. Your app must
declare a matching `singleTask` intent-filter:

```xml
<activity android:name=".MainActivity" android:launchMode="singleTask" ...>
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="${applicationId}.klarna" android:host="return" />
    </intent-filter>
</activity>
```

Pass the matching URL (e.g. `"$packageName.klarna://return"`) as `klarnaHost`'s `klarnaReturnUrl`.
No manifest change is required for Google Pay or PayPal — both of their return Activities ship
inside `:partner-ui`.

---

## 8. Submitting payment

```kotlin
suspend fun submitPayment(): ValidationResult
```

Runs the plugin-driven `/validate` pipeline over the current split. Must be called once
`isPaymentFulfilled` is `true`. Sets `sessionState` to `SUBMITTING`, then `SUBMITTED`/`ERROR` —
or back to `READY` when a merchant `onBeforeValidate` hook aborted it before anything was sent.

**Throws:**
- `PurseCheckoutException.PaymentNotFulfilled` — the split doesn't cover the amount, or no primary
  is set.
- `PurseCheckoutException.ItemNotFulfilled` — an item in the split has a form that isn't complete and
  valid (see [§6](#form-completeness-gates-the-payment)).
- `PurseCheckoutException.HookFailed` — your `onBeforeValidate` hook threw (code
  `ON_BEFORE_VALIDATE_HOOK_FAILED`, your exception as `cause`); nothing was sent.
- `PurseCheckoutException.NoPluginRegistered` — no `:partner-ui` plugin registered for the chosen
  primary's `(partner, method)`.
- `PurseCheckoutException.ValidateFailed` — a client-side or API validation step failed.
- `PaymentApiException` — network/transport failure.

Most callers reach `submitPayment` indirectly, through `GooglePayHost.submitPayment` /
`KlarnaHost.submitPayment`, which coordinators call for you.

### Re-checking status after a redirect

```kotlin
fun sessionTransactions(): JSONArray
```

`GET /{entityId}/sessions/{sessionId}` — the source of truth to check after a redirect/3-D Secure
return, rather than trusting a locally-held result. Blocking; call off the main thread.

---

## 9. Result & redirect types

### `ValidationResult`

```kotlin
data class ValidationResult(val globalStatus: String, val redirection: Redirection?, val raw: JSONObject) {
    val isSuccess: Boolean  // globalStatus == "SUCCESS"
    data class Redirection(val shouldSubmitWidget: Boolean, val url: String?, val form: String?, val name: String?)
}
```

`raw` is the full `/validate` response body — kept around for anything a plugin's own
post-validation step still needs.

### `RedirectionCheckoutResult` (via `RedirectionCheckoutContract`)

```kotlin
sealed interface RedirectionCheckoutResult {
    data object Approved      // shopper returned via the deep link — re-check status
    data object Canceled      // shopper dismissed the Custom Tab/WebView without returning
    data class Failed(val message: String?)
}
```

You don't launch this contract directly for Google Pay/Klarna — the coordinators do it for you and
surface the outcome through their own `Listener`. It's exposed here for anyone building a new
interactive plugin.

---

## 10. Errors

| Exception | Thrown by | Meaning |
|---|---|---|
| `PurseCheckoutException.PaymentNotFulfilled` | `submitPayment` | Split doesn't cover the amount / no primary set. |
| `PurseCheckoutException.ItemNotFulfilled` | `submitPayment` | An item in the split has an incomplete or invalid form (web's `ELEMENT_NOT_FULFILLED`). |
| `PurseCheckoutException.NoPluginRegistered` | `submitPayment` | No `:partner-ui` plugin for the chosen `(partner, method)`. |
| `PurseCheckoutException.ValidateFailed` | `submitPayment` | Client-side or API validation step failed. |
| `PurseCheckoutException.HookFailed` | `submitPayment` (`onBeforeValidate`), coordinators (`onRedirection`) | A merchant hook threw — `hook` names it, `cause` is the merchant's exception. `ON_BEFORE_VALIDATE_HOOK_FAILED` aborts the submit with nothing sent; `POST_VALIDATE_FAILED` (`onRedirection`) opens no redirect. Neither moves the split. |
| `PurseCheckoutException.NoSecondaryConfig` | `getSecondaryToken` | No `SecondaryConfig` registered for this secondary method. |
| `PurseCheckoutException.InvalidSecondaryCard` | `getSecondaryToken` | Client-side PAN/CVV validation failed. |
| `PurseCheckoutException.SecondaryTokenizationFailed` | `getSecondaryToken` | `/init` unsuccessful, or the card carries no balance. |
| `PurseCheckoutException.MethodNotSimulable` | `simulateLoan` / `simulateLoans` | The method has no `simulable` label (`METHOD_IS_NOT_SIMULABLE`). |
| `PurseCheckoutException.LoanSimulationFailed` | `simulateLoan` | The simulation API answered without schedule data (`INVALID_LOAN_SIMULATION`). |
| `PurseSessionException.SessionMalformed` | `PurseHeadlessCheckout.init` | `widget.data` couldn't be decoded, or is missing required fields. |
| `PaymentApiException` | Any Payment API call | Network/transport failure, non-2xx/3xx status, or an unusable response body. |

All of the above are unchecked (`Exception` subclasses) — catch at whatever layer makes sense for
your app's error UI.

---

## 11. Supporting value types

| Type | Values / shape | Notes |
|---|---|---|
| `SessionState` | `IDLE, READY, SUBMITTING, SUBMITTED, ERROR, EXPIRED, TERMINATED` | Web's `PurseHeadlessCheckoutSessionState`. |
| `PurseEnvironment` | `DEVELOPMENT, TEST, SANDBOX, PRODUCTION` | Each carries its own `paymentApiRoot`. |
| `PurseSessionParams` | `apiKey, apiUrl, environment, entityId, paymentSession` | The decoded `widget.data`. |
| `MethodConfiguration` | `stable, template, labels, position, vault, settings` | Raw `configurations` block; `isSecondary` derives from `labels`. |
| `LoanProduct` / `LoanSimulation` | see [Loan simulation](#loan-simulation-bnpl) | Input and result of `simulateLoan`. |
| `MethodSettings` | `registrationMode, saveToken, directDebit, paymentMinAmount, paymentMaxAmount, integrationMode, merchantClientId, …` | Per-method settings a UI may want to branch on. |

---

## See also

- [Headless checkout architecture](../architecture/headless-checkout.md) — module layout, the
  purse-payment concept, and plugin lifecycle internals.
- [Pain points recap](../decisions/pain-points-recap.md) — where integrating this headless on
  Android diverges from integrating headless on web.
