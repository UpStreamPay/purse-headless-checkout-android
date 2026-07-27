# Consumer API Reference — Headless Checkout (Android)

Audience: a merchant engineer building a checkout UI on top of this SDK. This page documents the
full **public** surface — every function, reactive property ("event"), and callback a host app can
call or observe — module by module. It does not cover internals (plugin protocol classes, mappers,
splitter math); see [Headless checkout architecture](../architecture/headless-checkout.md) for that.

Everything here is the Kotlin analog of web's `@upstreampay/headless-checkout` +
`@upstreampay/partner-ui` public exports. Where the shape differs from web, it's called out.

---

## 1. Bootstrapping

### Register plugins (once, at app startup)

```kotlin
GooglePayModule.register()   // eu.purse.partnerui.plugins.generics.googlepay
KlarnaModule.register()      // eu.purse.partnerui.plugins.partners.klarna
```

Each plugin module is a separate opt-in registration — call only the ones the app actually ships.
An unregistered `(partner, method)` makes [`submitPayment`](#8-submitting-payment) throw
`PurseCheckoutException.NoPluginRegistered` the moment that method is used as the primary.

### `PurseHeadlessCheckout.init`

```kotlin
fun init(widgetData: String, lockedEnvironment: PurseEnvironment? = null): PurseHeadlessCheckout
```

| Param | Meaning |
|---|---|
| `widgetData` | The base64-encoded `widget.data` string from the merchant backend's Client Session response. |
| `lockedEnvironment` | Overrides whatever `widgetData` declares. **A release build must always pass `PurseEnvironment.PRODUCTION`** rather than trusting a session-declared environment. |

**Throws:** `PurseSessionException.SessionMalformed` if `widgetData` can't be decoded, or the
session is missing `id` / `expiration_date`.

Call this once per checkout session. The returned `PurseHeadlessCheckout` instance is the object
every other API in this document hangs off.

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
| `isPaymentFulfilled` | `StateFlow<Boolean>` | Same triggers as above — `true` once the split covers the full amount. |
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
`<purse-partner-ui>` element gets it — the host owns re-rendering on every emission. See the
[pain points recap](../decisions/pain-points-recap.md#b-reactive-model-mismatch-svelte-stores-vs-kotlin-stateflow)
for what this costs versus web.

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

---

## 6. Interactive plugins (Google Pay, Klarna)

Interactive plugins collect data outside the headless split/validate flow (a payment sheet, a
native widget) before `submitPayment` runs. Three pieces make up the pattern for every such plugin:

1. A **`*Host` interface** in `:partner-ui` — what the plugin's coordinator needs from the checkout,
   implemented by `PurseHeadlessCheckout`.
2. A **`*Coordinator`** class — drives the plugin's native SDK, reports outcomes via a `Listener`.
3. A **`setCollected`** call — stashes sheet/widget output so `submitPayment`'s `/validate` payload
   can use it.

### Generic checkout functions backing every interactive plugin

| Function | Signature | Notes |
|---|---|---|
| `setCollected` | `fun setCollected(item: PaymentItem, data: Map<String, Any?>)` | Stash data an interactive plugin collected (e.g. a Google Pay token + network), keyed to `item`. Threaded into that item's `preValidation` on the next `submitPayment`. |
| `pluginContext` | `fun pluginContext(item: PaymentItem): PartnerUiContext` | Build the context object a plugin coordinator needs to drive its own steps before submit. |
| `googlePayHost` | `fun googlePayHost(item: PaymentItem): GooglePayHost` | The `GooglePayHost` implementation for `item`. |
| `klarnaHost` | `fun klarnaHost(item: PaymentItem, klarnaReturnUrl: String): KlarnaHost` | The `KlarnaHost` implementation for `item`. `klarnaReturnUrl` falls back to the session's own return URL if you don't have a better one. |

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

Most consumers won't build this directly — `pluginContext`/`googlePayHost`/`klarnaHost` build it for
you. `amount` is what a primary would still cover; `collected` carries whatever `setCollected` last
stashed for this item.

### Google Pay

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
| `onResult(summary: String)` | Terminal success with no follow-up redirect needed. |
| `onRequiresAction()` | A 3-D Secure step is being launched (informational). |
| `onPaymentId(paymentId: String)` | Informational — a `payment_id` extracted from the widget's own confirmation-page redirect, when present. Never required for the payment to resolve. |
| `onCanceled()` | Shopper dismissed the sheet or the redirect WebView without completing. |
| `onError(message: String?)` | Any failure — sheet, `/validate`, or redirect launch. |

**Note on `onResult`/redirect return:** a returned-from-redirect `Approved` outcome means only that
the shopper came back into the app — **not** that the payment succeeded. Re-check status
independently (e.g. `sessionTransactions()`) rather than trusting it. See the
[pain points recap](../decisions/pain-points-recap.md).

### Klarna

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
| `submit` | `fun submit()` | Runs `/validate` **first** (opposite order from Google Pay), then drives the widget's `authorize()`/`finalize()`. Call once `Listener.onReady(true)` fired and the shopper tapped "Pay with Klarna". |

**`KlarnaCoordinator.Listener`** callbacks:

| Callback | Fires |
|---|---|
| `onReady(ready: Boolean)` | The widget finished loading content — show the pay trigger. |
| `onResult(summary: String)` | Terminal success with no follow-up redirect needed. |
| `onRequiresAction()` | A redirect step (external banking/identity-verification app) is being launched. |
| `onCanceled()` | Klarna declined to authorize/finalize, or the redirect was dismissed. |
| `onError(message: String?)` | Any failure — widget load, `/validate`, authorize/finalize, or redirect launch. |

Same caveat as Google Pay: a returned `Approved` redirect outcome is not proof of payment success.

### Registering a new plugin (`PluginRegistry`)

Only relevant if you're adding a plugin, not consuming an existing one:

| Function | Signature |
|---|---|
| `register` | `fun register(partner: Partner, method: Method, builder: PluginBuilder)` |
| `registerFallback` | `fun registerFallback(method: Method, builder: PluginBuilder)` |
| `getPlugin` | `fun getPlugin(partner: Partner, method: Method, session: LegacyPaymentSession): PartnerUiPlugin?` |

Resolution order: vault vendor → generic template → exact `(partner, method)` → method fallback.

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
No manifest change is required for Google Pay — its redirect Activity ships inside `:partner-ui`.

---

## 8. Submitting payment

```kotlin
suspend fun submitPayment(): ValidationResult
```

Runs the plugin-driven `/validate` pipeline over the current split. Must be called once
`isPaymentFulfilled` is `true`. Sets `sessionState` to `SUBMITTING`, then `SUBMITTED`/`ERROR`.

**Throws:**
- `PurseCheckoutException.PaymentNotFulfilled` — the split doesn't cover the amount, or no primary
  is set.
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
| `PurseCheckoutException.NoPluginRegistered` | `submitPayment` | No `:partner-ui` plugin for the chosen `(partner, method)`. |
| `PurseCheckoutException.ValidateFailed` | `submitPayment` | Client-side or API validation step failed. |
| `PurseCheckoutException.NoSecondaryConfig` | `getSecondaryToken` | No `SecondaryConfig` registered for this secondary method. |
| `PurseCheckoutException.InvalidSecondaryCard` | `getSecondaryToken` | Client-side PAN/CVV validation failed. |
| `PurseCheckoutException.SecondaryTokenizationFailed` | `getSecondaryToken` | `/init` unsuccessful, or the card carries no balance. |
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
| `MethodSettings` | `registrationMode, saveToken, directDebit, paymentMinAmount, paymentMaxAmount, integrationMode, merchantClientId, …` | Per-method settings a UI may want to branch on. |

---

## See also

- [Headless checkout architecture](../architecture/headless-checkout.md) — module layout, the
  purse-payment concept, and plugin lifecycle internals.
- [Pain points recap](../decisions/pain-points-recap.md) — where integrating this headless on
  Android diverges from integrating headless on web.
