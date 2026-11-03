# Consumer API Reference — Headless Checkout (Android)

Audience: a merchant engineer building a checkout UI on top of this SDK. Start with
[getting started](getting-started.md); this page documents the full **public** surface — every function, reactive property ("event"), and callback a host app can
call or observe — module by module. It does not cover internals (plugin protocol classes, mappers,
splitter math); see [Headless checkout architecture](../architecture/headless-checkout.md) for that.

Everything here is the Kotlin analog of web's `@upstreampay/headless-checkout` +
`@upstreampay/partner-ui` public exports. Where the shape differs from web, it's called out.

---

## 1. Bootstrapping

### Plugins are active by default — no setup call needed

Every plugin on the classpath — Purse-vaulted card and the generic redirection template from the
SDK itself, Google Pay / Klarna / PayPal from their optional artifacts — is registered
automatically in `PluginRegistry`; there is nothing to call. Whether a method actually shows
up is gated by the **session** alone — the same thing that already decides it server-side, in the
merchant's Purse configuration. `paymentMethods` simply won't include a method the session doesn't
offer.

If you need to suppress a shipped method for a specific app build (e.g. its UI isn't ready yet even
though the backend might offer it), opt it out on the checkout — scoped to that instance, never
process-wide:

```kotlin
PurseHeadlessCheckout.init(widgetData, disabledMethods = setOf(MethodKey(Partner.Klarna, Method.Installments)))
```

`register` / `registerGeneric` / `registerVault` exist for
tests that want to register a plugin in isolation.

### `PurseHeadlessCheckout.init`

```kotlin
fun init(
    widgetData: String,
    lockedEnvironment: PurseEnvironment? = null,
    hooks: PurseCheckoutHooks? = null,
    options: PluginOptions = PluginOptions(),
    disabledMethods: Set<MethodKey> = emptySet(),
): PurseHeadlessCheckout
```

| Param | Meaning |
|---|---|
| `widgetData` | The base64-encoded `widget.data` string from the merchant backend's Client Session response. |
| `lockedEnvironment` | Overrides whatever `widgetData` declares. **A release build must always pass `PurseEnvironment.PRODUCTION`** rather than trusting a session-declared environment. The lock pins the tier's **host**, not the API version: a session `api_url` on that host (e.g. an orchestration session's `…/payment/v2`) is kept; one on any other host, or not `https`, is replaced by the tier's `…/payment/v1` root. |
| `hooks` | Merchant hooks around "create payment" — see [`PurseCheckoutHooks`](#pursecheckouthooks) below. |
| `options` | Presentation preferences forwarded to every plugin (locale, theme, card-form texts). |
| `disabledMethods` | `(partner, method)` pairs this app build opts out of: never listed on `paymentMethods`, their saved instruments included, whatever the session offers. Scoped to this checkout and re-applied by `setSession`. |

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
| `params` | `PurseSessionParams` | Decoded `widget.data` (`apiKey`, `apiUrl`, `environment`, `entityId`, `paymentSession`). Replaced by `setSession`. |
| `session` | `PaymentSession` | The built session model (amount, currency, expiration, vault config, means of payment). Replaced by `setSession` — hold the checkout, not the session; observe `paymentSession`. |
| `paymentApiRoot` | `String` | Resolved Payment API root; calls go to `{root}/{entityId}/sessions/{sessionId}/…`. |
| `currency` | `String` | Session currency code. |
| `isInformationRequest` | `Boolean` | Whether this session is a zero-amount information request rather than a real charge. |
| `currentSessionId` | `String` | The Payment API session id (`session.id`). |
| `instanceId` | `String` | This checkout's id in Purse's monitoring (`instanceId` on every event). Stable across `setSession` and `saveState` / `restore`; correlate your own logs with it. |

### `setSession` — refresh the session without rebuilding the checkout

```kotlin
suspend fun setSession(widgetData: String)
```

Web's `setSession`. Hand the existing checkout a **new** Client Session — the cart changed and your
backend minted one for the new amount, or the current one expired (`sessionState == EXPIRED`) and
you fetched a fresh one — without tearing the screen down. What survives follows web's
`payment.setSession`, plus one thing:

| | Survives | Torn down |
|---|---|---|
| Primary method whose `protocols[partner][method]` is **deep-equal** in both sessions | its collected data (`setCollected`), its form-fulfilment gate, its save-token decision (`register`), its live plugin — and, beyond web, its place as the split's **primary** when it was selected | — |
| Primary method whose configuration **changed**, or that **disappeared** | — | plugin (`destroyClient`), collected data, fulfilment, registration; its `itemGenerations` entry is bumped so UI remembered for it rebuilds |
| Saved cards (`paymentTokens`) | rebuilt from the new session; survive as selected primary when their method survives and the new session still lists them | — |
| Applied gift cards (`appliedSecondaries`) and their registrations | — | dropped — they were tokenized against the old session |
| Amount, expiry watchdog, `sessionState` | — | the new session's amount; the watchdog restarts; state returns to `READY` |

Same session id → ignored with a warning, as on web. **Throws** `PurseCheckoutException.SessionExpired`
(`EXPIRED_SESSION`) when the new session is already expired — the old one stays in force —
`PurseCheckoutException.SubmitBlocked` (`SUBMIT_BLOCKED`) while a payment is being submitted, and
`PurseCheckoutException.CheckoutTornDown` (`CHECKOUT_TORN_DOWN`) after `teardown()`. Emits
`HEADLESS_CHECKOUT_SET_SESSION`.

Keying UI on `(item.id, generation)` is what makes "keep what is still valid" work on the screen:
`rememberPaymentElement` already does it, so an unchanged card keeps its SecureFields form and
whatever the shopper typed, while a changed method gets a fresh element. See
[recipes §15](recipes.md#15-refresh-the-session).

---

### `saveState` / `restore` — survive process death

```kotlin
fun saveState(): CheckoutSavedState
companion object {
    fun restore(state: CheckoutSavedState, hooks: PurseCheckoutHooks? = null, options: PluginOptions = PluginOptions()): PurseHeadlessCheckout
}
```

`saveState` is a cheap, synchronous snapshot (`Parcelable`, also `toJson()` / `fromJson()`): the
session's `widget.data`, the locked environment and `disabledMethods`, the primary selection, the
applied gift cards with their slices, the save decisions (`register` / `registerSecondary`), what the
native steps collected (JSON-representable values only — a Klarna authorize callback is not), and
whether a submit has left the device. **No card data**, ever. Throws `CheckoutTornDown` after
`teardown()`.

`restore` decodes the session again and replays the snapshot. Anything the new session no longer
offers, or a slice it cannot honour, is skipped with a warning. When `CheckoutSavedState.submitted`
is `true` the restored session is spent — `sessionState` is `SUBMITTED`, `submitPayment` throws
`SubmitBlocked` — and the merchant confirms the outcome with `awaitPaymentOutcome()`. `hooks` and
`options` are not saved: pass the same ones as at `init`. A snapshot from a newer SDK is refused
(`IllegalArgumentException` from `CheckoutSavedState.fromJson`).

Hold the checkout in a `ViewModel` so a rotation never touches it; the card form's text does not
survive an Activity recreation (SecureFields keeps it in memory only, and saving card data would be
wrong) — recipe 16 shows the `SavedStateHandle` wiring.

---

## 3. Reactive state — "events" (`StateFlow`)

The reactive surface is exposed as `StateFlow` — the Kotlin analog of web's Svelte stores. Collect
it from a coroutine scope; there is no callback-based equivalent.

| Property | Type | Fires when |
|---|---|---|
| `sessionState` | `StateFlow<SessionState>` | Lifecycle transitions: `READY` → `SUBMITTING` → `SUBMITTED`/`ERROR`, or `EXPIRED` at init, on the watchdog's tick, or when a `submitPayment` re-checks the clock (and back to `READY` after `setSession`). |
| `isTornDown` | `StateFlow<Boolean>` | `true` once `teardown()` ran; every later operation throws `CheckoutTornDown`. |
| `paymentSession` | `StateFlow<PaymentSession>` | The session in force; emits again when `setSession` swaps it — rebuild session-derived UI (amount header, method list) from here. |
| `itemGenerations` | `StateFlow<Map<String, Int>>` | Per-item generation counters bumped by `setSession` for items that did not survive; key anything you remember per item on `(item.id, generation)` — `generationOf(item)` reads the current one. |
| `paymentMethods` | `StateFlow<List<PaymentItem>>` | Any time the split changes — each item's `disabled` state is recomputed against the new `remainingAmountToPay`. While `submitPayment` is in flight every otherwise-selectable item (here, on `paymentTokens` and on `savedSecondaryTokens`) reads `DisabledState.Submitting`, lifted when it returns. |
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

There is no `onAmountUpdate` push into a rendering surface the way web's
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

Four concrete subtypes:

**`PrimaryMethod`** — exactly one covers the amount left after secondaries.
- `integrationType: IntegrationMode?` — `redirection` / `popup`, from `merchant_specifics`.
- `supportedBrands: List<CardBrand>` — card schemes accepted; empty when not vaulted.

**`SecondaryMethod`** — a co-payment method (gift card, voucher, loyalty).
- `saveTokenMode: SaveTokenMode` / `canBeRegistered: Boolean` — whether a gift card typed for this
  method can be kept for the next session (see `registerSecondary`).

**`PaymentToken`** — a saved instrument under a primary method — a *card-on-file*, or a vaulted
*PayPal account*; can be the split's primary. Exposed on `paymentTokens`, never on `paymentMethods`
— see §7.
- `isVaultedCard: Boolean` — the method captures card data through a vault (`vault.vendor ≠ none`),
  so the token is a card-on-file whose plugin can render a CVV form. `false` for a saved PayPal.
- `requiresCvv` / `paysWithoutCvv` — whether paying still asks for the CVV. Only ever `true` for a
  card-on-file outside the no-CVV gates (`pay_token_no_cvv`, network token); a CVV is a card-form
  concept, so a PayPal token always pays without one and is payable the moment it's selected.

**`SavedSecondaryToken`** — a saved *gift card / voucher* under a secondary method (web's
`type: 'token'` secondary). Exposed on `savedSecondaryTokens`, never on `paymentMethods`.
- `pan` / `cvv` — the stored `uniqueness_token` / `pin_code` the partner re-initialises with.
- `name`, `expirationDate`, `createdAt`, `favorite` — display metadata.
- `balance` / `totalBalance` / `balanceResolved` — the session carries no balance, so the SDK
  `/init`s each saved card in the background right after construction; `balanceResolved` flips to
  `true` when it lands. A failed `/init` leaves the token listed with
  `disabled = DisabledState.TokenInitFailed`.
- `takePolicy` — from the method's `SecondaryConfig`.
- `asSecondaryToken()` — the splitter-side instrument (`isSaved = true`).

### `DisabledState` (sealed)

Web's codes with web's names; every state carries a displayable `message` (web's wording) and the
amount-driven ones their context. `code` is the enum to branch on.

| State | Behaviour | Meaning |
|---|---|---|
| `MethodUnstable` | filtered out of the list | The platform suspended the method (`stable == false`). |
| `AmountConditionUnreachable(minAmount, maxAmount, orderAmount)` | filtered out of the list | The method's minimum is above the whole order. |
| `PluginIncompatible` | filtered out of the list | The plugin rejected this client/session in its eligibility check. |
| `FatalErrorBeforeRender(reason)` | filtered out of the list | The plugin failed before mounting anything. |
| `PaymentFulfilled` | UI cleared, recomputed on amount change | The remaining amount owed by a primary is already zero. |
| `MinAmountNotMet(minAmount, restToPay)` | UI cleared, recomputed on amount change | The item's minimum is above what's left to pay. |
| `MaxAmountExceeded(maxAmount, restToPay)` | UI cleared, recomputed on amount change | Never applies to secondaries — a gift card can hold more than the order needs. |
| `TokenInitFailed` | technically disabled: no UI, permanent | A saved gift card's `/init` failed. |
| `PartnerUiFatalError(reason)` | technically disabled: no UI, permanent | The partner plugin reported an unrecoverable failure. |
| `PartnerUiRecoverableError(reason)` | dimmed, UI stays mounted, clears on `Ready` or an amount change | The partner is broken for now, not for good. |
| `Submitting` | all items, for the duration of `submitPayment` | A payment is being submitted. |

Predicates: `isFiltered`, `isTechnicallyDisabled`, `requiresUiClear`, `isRecoverable`,
`isRecomputable`. A disabled item cannot enter the split — `setPrimary` / `setPrimaryToken` throw
`MethodDisabled`, `applySecondary(saved)` throws `SecondaryTokenizationFailed` — and a technically
disabled one gets a `PaymentElement` with no UI. Merchants show `message`; the demo shows `code`.

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
| `setPrimary` | `fun setPrimary(method: PrimaryMethod?)` | Choose (or clear, with `null`) the primary covering the remaining amount. Throws `MethodDisabled` (`METHOD_DISABLED`) for a disabled method, judged on the live state. Triggers a `refresh()` of all reactive state. |
| `secondaryConfig` | `fun secondaryConfig(method: SecondaryMethod): SecondaryConfig?` | The per-partner form/take-policy rules for a secondary method — `null` if none registered (shipped or via `SecondaryConfigs.register`; e.g. `ancv-connect` is not shipped). |
| `getSecondaryToken` | `suspend fun getSecondaryToken(method: SecondaryMethod, pan: String, cvv: String? = null): SecondaryToken` | `POST /init` — tokenizes a card and returns its balance **without** touching the split. Validate PAN/CVV via `secondaryConfig` first. |
| `applySecondary` | `fun applySecondary(token: SecondaryToken, amount: BigDecimal? = null)` | Draws an already-tokenized `token` into the split. `amount == null` takes as much as the order needs, capped by the balance and the method's `payment_max_amount`. An explicit `amount` is checked like web's `ensureTakePolicy`: throws `InvalidTake` — `INVALID_TAKE_AMOUNT` (negative, above the balance or above what is left), `MAX_AMOUNT_REACHED` (above the method's maximum), `USAGE_LIMIT_ERROR` (the method's `usage_per_session_limit` is already used up by other tokens in the split). |
| `removeSecondary` | `fun removeSecondary(token: SecondaryToken)` | Removes a previously applied token from the split. |
| `appliedSecondaries` | `List<SecondaryToken>` | Applied tokens, in the order they were added. |
| `registrationOf` | `fun registrationOf(token: SecondaryToken): RegistrationState` | The save decision for a **temporary** gift card — defaults from its method's `SaveTokenMode`; always `registered = false` for a saved one. |
| `registerSecondary` | `fun registerSecondary(token: SecondaryToken, registered: Boolean = true, name: String? = null)` | Ask to keep a temporary gift card (optionally named) — travels into that card's `/validate` item as `payment_preferences.registration` + `payment_data.card.token_label`. Throws `WalletOperationFailed(TOKEN_NOT_REGISTERABLE / INVALID_TOKEN_NAME / TOKEN_NAME_EXISTS)`. |
| `savedSecondaryTokens` | `StateFlow<List<SavedSecondaryToken>>` | The session's saved gift cards, balances resolving in the background. |
| `applySecondary` | `fun applySecondary(token: SavedSecondaryToken, amount: BigDecimal? = null)` | Draws a saved gift card in (latest state is used, a stale instance is fine). Throws `SecondaryTokenizationFailed(SECONDARY_TOKEN_EMPTY_BALANCE)` while unresolved / empty, `(FAILED_TO_INIT_SECONDARY_TOKEN)` when its `/init` failed. |
| `removeSecondary` | `fun removeSecondary(token: SavedSecondaryToken)` | Puts a saved gift card's amount back. |
| `editToken` / `deleteToken` | `suspend fun editToken(token: SavedSecondaryToken, name: String)` / `suspend fun deleteToken(token: SavedSecondaryToken)` | Rename / delete a saved gift card through the wallet — same rules and errors as the card overloads (§7). Delete also clears it from the split. |

**`getSecondaryToken` throws:**
- `PurseCheckoutException.NoSecondaryConfig` — no config registered for this `(partner, method)`.
- `PurseCheckoutException.InvalidSecondaryCard` — client-side PAN/CVV validation failed.
- `PurseCheckoutException.SecondaryTokenizationFailed` — `/init` wasn't successful, or the card
  carries no balance.
- `PaymentApiException` — network/transport failure.

Every `suspend` function of the checkout runs its network call on `Dispatchers.IO` itself — call it
from any dispatcher, the main one included. No public function blocks.

### `SecondaryToken`

```kotlin
data class SecondaryToken(
    val id: String, val partner: Partner, val method: Method,
    val pan: String, val cvv: String?, val name: String,
    val balance: BigDecimal, val totalBalance: BigDecimal = balance,
    val expirationDate: String = "", val takePolicy: SecondaryTakePolicy = SecondaryTakePolicy.NONE,
    val limit: BigDecimal? = null,
    val saveTokenMode: SaveTokenMode = SaveTokenMode.NEVER, val isSaved: Boolean = false,
) { val canBeRegistered: Boolean }
```

`balance` is what's spendable now; `totalBalance` is the full instrument balance when a partner
distinguishes it. `takePolicy` is `NONE` (splittable — `applySecondary`'s `amount` caps the draw)
or `MAX` (non-splittable — always takes the full cap regardless of `amount`). `saveTokenMode` is
the method's effective save-token policy (set by `getSecondaryToken`); `canBeRegistered` is `true`
for a temporary token under `PREFER` / `PREFER_NO`, never for a saved one (`isSaved`).

### `SecondaryConfig` — also the extension point for issuers the SDK doesn't ship

The interface a merchant's form UI queries to drive validation before calling `getSecondaryToken`,
and what the SDK itself uses to build `/init` / `/validate` bodies and read balances:

| Member | Meaning |
|---|---|
| `takePolicy: SecondaryTakePolicy` | `NONE` / `MAX` — see above. **Required.** |
| `requiresCvv(pan: String): Boolean` | Whether to show/require a CVV field for this PAN. **Required.** |
| `validatePan(pan: String): Boolean` | Client-side PAN format check. **Required.** |
| `validateCvv(cvv: String): Boolean` | Client-side CVV format check (default: always valid). |
| `nameLogKey: String?` | `plugin_result.logs` key holding the display label (default parse). |
| `buildInitPaymentData(pan, cvv)` / `buildValidatePaymentData(token)` | `payment_data` bodies (default: `{ card: { number, csc } }`). |
| `parseToken(method, initResponse, pan, cvv)` | `/init` response → `SecondaryToken` (default: generic card shape). |
| `shouldPoll(initResponse)` / `pollForToken(api, initResponse, initPayload)` | Two-phase partners: a `SUCCESS` that only says `PROCESSING` is polled through the config (default: never). |
| `classifyInitError(initResponse): PurseCheckoutException?` | Map a partner refusal onto a typed exception (default: generic `SecondaryTokenizationFailed`). |

Shipped partners: `illicado-giftcard`, `buybox-giftcard`, `maxxing-loyaltycard`,
`ogloba-giftcard`, `mybeezbox-giftcard`, `easy2play-giftcard`, `easy2play-voucher`.
`ancv-connect` is not shipped (its two-phase holder→poll flow is exactly what `pollForToken` is
for — a host can register one).

**Adding an issuer without changing the SDK** — `SecondaryConfigs` is a process-wide registry:

| Function | Notes |
|---|---|
| `SecondaryConfigs.register(partner: Partner, method: Method, config: SecondaryConfig)` | Register (or override) the config for `(partner, method)`; `Partner.Other("acme")` / `Method.Other("voucher")` work — the key is the wire values. Do it once at app start. |
| `SecondaryConfigs.unregister(partner, method)` | Drop a registration (shipped or host-added). |
| `SecondaryConfigs.isRegistered(partner, method)` / `forMethod(...)` | Query. |
| `SecondaryConfigs.reset()` | Back to the shipped catalogue only. |

Once registered, that method's `secondaryConfig(...)`, `getSecondaryToken(...)`, saved-token balance
resolution and `/validate` items all go through it — same as a built-in.

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
shopper pays in all — `initial + (count - 1) × installmentAmount + final`, rounded to two decimals,
as web's `mapPluginResultToSimulation` computes it. `installmentCount` is the API's
`instalment_number`: the instalments that follow the first one, so a 3× schedule has a count of 2 and
three lines: initial, one recurring, final. Like web, the schedule carries **counts and amounts
only**: the API returns no per-instalment dates.

**Throws:**
- `PurseCheckoutException.MethodNotSimulable` (`METHOD_IS_NOT_SIMULABLE`) — the method has no
  `simulable` label. No API call is made.
- `PurseCheckoutException.LoanSimulationFailed` (`INVALID_LOAN_SIMULATION`) — the API answered
  without a schedule: no `plugin_result`, or an API-level refusal such as Riverty's
  `409 EMPTY_SIMULATION` when the partner doesn't offer the method for this order. The API's own
  code is on `cause` (`PaymentApiException.errorCode`). Same single code as web.
- `PaymentApiException` — network/transport failure (no HTTP answer at all).

---

## 6. Interactive plugins (Google Pay, PayPal, Klarna, Card, Redirection)

Interactive plugins collect data outside the headless split/validate flow (a payment sheet, a
native widget, a card form) before `submitPayment` runs. This section is the **API surface**; each
PSP's own behaviour — what its `/init` returns, where its native step sits relative to `/validate`,
its quirks and failure modes, with sequence diagrams — has a page of its own under
**[docs/plugins](../plugins/README.md)**.

Google Pay, PayPal and Klarna ship as optional artifacts (`eu.purse:headless-checkout-googlepay`,
`-paypal`, `-klarna`); their classes below exist only when the artifact is on the classpath. Card and
redirection are in `headless-checkout-android` itself.

Three pieces make up the pattern for every such plugin:

1. A **`*Host` interface** in `eu.purse.partnerui` — what the plugin's coordinator needs from the checkout,
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
| `setFulfilled` | `fun setFulfilled(item: PaymentItem, fulfilled: Boolean)` | Report whether `item`'s own form is complete and valid — see [above](#form-completeness-gates-the-payment). Coordinators that collect a form call this for you. |
| `pluginContext` | `fun pluginContext(item: PaymentItem): PartnerUiContext` | Build the context object a plugin coordinator needs to drive its own steps before submit. |
| `host` | `fun host(item: PaymentItem): PluginHost` | The `PluginHost` a coordinator drives `item` through — a `PrimaryMethod`, or one of its saved `PaymentToken`s (the token reaches the plugin as `PartnerUiContext.token`). Throws `IllegalArgumentException` for a secondary method or a saved gift card. Asking for a host also gives the plugin web's `onSessionSet`. |

### `PartnerUiContext`

The object every plugin lifecycle hook receives:

```kotlin
data class PartnerUiContext(
    val partner: Partner, val method: Method, val session: LegacyPaymentSession,
    val amount: BigDecimal, val api: PaymentApiClient,
    val register: Boolean = false, val token: PayToken? = null,
    val collected: Map<String, Any?> = emptyMap(),
    val options: PluginOptions = PluginOptions(),
    val dispatchEvent: (PluginEvent) -> Unit = {},
) {
    val configuration: MethodConfiguration?   // session.protocols[partner][method].configurations
    val vaultTenantId: String?                // vault.vendor_front_data.purse.tenant_id
}
```

Most consumers won't build this directly — `pluginContext` and `host` do it for you. `amount` is what
a primary would still cover; `collected` carries whatever `setCollected` last stashed for this item.

### `PluginHost`

One interface for every coordinator (web has no equivalent: its plugins hold the checkout context
directly). Implemented by `PurseHeadlessCheckout.host(item)`; you only meet it when you drive a
coordinator yourself instead of going through `rememberPaymentElement`.

| Member | Meaning |
|---|---|
| `environment: PurseEnvironment` | Drives the native SDK's own environment (Google Pay `ENVIRONMENT_TEST`/`PRODUCTION`, PayPal `SANDBOX`/`LIVE`, SecureFields' `VaultEnvironment`). |
| `redirectionReturnUrl: String?` | The session's own return URL, threaded into a genuine 3-D Secure redirect so the in-app WebView recognizes reaching it as completion. Never auto-followed. |
| `buildContext(): PartnerUiContext` | — |
| `setCollected(data: Map<String, Any?>)` | — |
| `setAsPrimary()` | Commit the item as the split's primary. Self-submitting flows call it on approval (web's `validationRequested`); a cancelled sheet leaves the split untouched. Throws `MethodDisabled` when the current session no longer offers the item, so a stale host never charges the primary already engaged. |
| `setFulfilled(fulfilled: Boolean)` | The form's completeness, forwarded to `PurseHeadlessCheckout.setFulfilled(item, …)` — the card coordinator calls it, you don't. |
| `suspend submitPayment(): ValidationResult` | Run the plugin-driven `/validate` pipeline over the current split. |
| `suspend delegateRedirection(action): Boolean` | Your `onRedirection` hook, when set (`RedirectionDelegate`). |

### Coordinators

Every method's interactive half is a `PaymentCoordinator`, obtained through a `remember*Coordinator`
composable. They are **Compose-only**: the composable wires Compose-managed `ActivityResult` launchers,
so a coordinator is created exactly where it renders and there is no "construct in `onCreate`"
rule. A View-based app hosts one through a `ComposeView`. `rememberPaymentElement` does all of this
for you; reach for a coordinator directly only to customise a button or drive a native step from your
own control.

What they share:

| Member | Meaning |
|---|---|
| `prepare(host)` | Bind the checkout's `host(item)` and run whatever the method needs before the shopper acts (`/init`, `isReadyToPay`, widget load). Klarna's takes the return URL too. |
| `submit()` | Run the native step (if any), then `/validate` through the host, then follow the redirect the answer carries. No-op while a previous tap's flow is in progress. Google Pay's equivalent is `launchSheet()`. |
| `PaymentElementListener` | The one outcome contract — see below. |

**`PaymentElementListener`** callbacks (every member has a default):

| Callback | Fires |
|---|---|
| `onReady(ready: Boolean)` | The method's readiness changed — `isReadyToPay` for Google Pay, `prepare()` done for PayPal and redirection, the widget loaded for Klarna, the form's validity for the card. |
| `onOutcome(outcome: PaymentOutcome)` | The attempt ended — see the sealed class below. Once per attempt. |
| `onRequiresAction()` | A redirect step is being launched (3-D Secure, PayPal's approval, the PSP's page). |
| `onPaymentId(paymentId: String)` | Informational — a `payment_id` the flow could read, when present. Never required for the payment to resolve. |

**`PaymentOutcome`** (sealed, `eu.purse.partnerui`):

```kotlin
sealed class PaymentOutcome {
    data class Completed(val result: ValidationResult) : PaymentOutcome()   // status: PaymentStatus, isSuccess
    data object ReturnedFromRedirect : PaymentOutcome()
    data object Canceled : PaymentOutcome()
    data class Failed(val error: PurseException) : PaymentOutcome()
}
```

| Outcome | Meaning |
|---|---|
| `Completed(result)` | `/validate` answered without a redirect. `result.status` is the API's verdict (`PaymentStatus.SUCCESS`, `FAILED`, `ERROR`, `UNCERTAIN`, `WAITING`; `UNKNOWN` for a value this SDK doesn't know, the raw string stays on `result.globalStatus`). A non-`SUCCESS` status is a declined payment, not an error. |
| `ReturnedFromRedirect` | The shopper came back into the app — **not** that the payment succeeded. Don't render it as either success or failure; re-check with `paymentStatus()` / `awaitPaymentOutcome()`. |
| `Canceled` | The shopper backed out (sheet dismissed, approval abandoned, redirect page closed, Klarna declined to authorize). The split is untouched; they may try again. |
| `Failed(error)` | The attempt produced no payment. `error` is typed: the checkout's own exceptions (`PurseCheckoutException.*`, `PaymentApiException`) as they are, a partner SDK failure as `PartnerUiException` (code `PARTNER_UI_ERROR`, the partner's exception as `cause`). A `ValidateFailed` whose `outcomeUnknown` is `true` must be confirmed against the session before showing a failure. |

The demo's three-way mapping (`DemoResult.of`) is the shape to copy:

```kotlin
when (outcome) {
    is PaymentOutcome.Completed -> if (outcome.isSuccess) Authorized else Declined(outcome.status)
    PaymentOutcome.ReturnedFromRedirect -> Unconfirmed        // go and check the session
    PaymentOutcome.Canceled -> BackToMethods
    is PaymentOutcome.Failed -> if (outcome.error is ValidateFailed && outcome.error.outcomeUnknown) Unconfirmed else Failed(outcome.error)
}
```

A `Failed` before any submit (a widget that fails to load, a `/init` that throws) is not a failed
payment either — the item is disabled on `paymentMethods` with the matching state; leave the shopper
able to pick another method.

#### Google Pay

Artifact `eu.purse:headless-checkout-googlepay`, package `eu.purse.partnerui.plugins.generics.googlepay`.


> Deep dive — `/init` shapes per gateway, sequence diagram, quirks, failure modes:
> **[docs/plugins/google-pay.md](../plugins/google-pay.md)**.

`rememberGooglePayCoordinator(listener)`. `prepare(host)` runs `/init` + Worldline refine and
`isReadyToPay`; `launchSheet()` shows the sheet (call it from the button's tap, after `onReady(true)`);
an approved sheet commits the method as primary and captures. `isReady` and
`allowedPaymentMethodsJson` are Compose state for the SDK's `GooglePayButton(coordinator)`, which
shows itself only once ready and passes button looks (`theme`, `type`, `cornerRadius`).

#### PayPal

Artifact `eu.purse:headless-checkout-paypal`, package `eu.purse.partnerui.plugins.partners.paypal.wallet`.


> Deep dive — the out-of-app approval round trip, sequence diagram, quirks, failure modes:
> **[docs/plugins/paypal.md](../plugins/paypal.md)**.

`rememberPaypalCoordinator(listener)`. `prepare(host)` does **no network work** — the `/init` that
creates the PayPal order waits for `submit()`, so merely showing the button never leaves an orphan
order. `submit()` runs `/init`, launches PayPal's approval (the PayPal app when installed, else a
Custom Tab), and captures via `/validate` once the buyer approves; with a saved token on the host it
captures straight away. `onRequiresAction` fires for the approval, and again only if `/validate` chains
a 3-D Secure step. **No manifest work**: the approval returns to an Activity `eu.purse.partnerui` ships
(scheme `${applicationId}.paypal`).

#### Klarna

Artifact `eu.purse:headless-checkout-klarna`, package `eu.purse.partnerui.plugins.partners.klarna`.


> Deep dive — why `authorize()` runs after `/validate`, sequence diagram, quirks, failure modes:
> **[docs/plugins/klarna.md](../plugins/klarna.md)**.

`rememberKlarnaCoordinator(listener)`. Set `container` (the `ViewGroup` the native widget inflates
into — an `AndroidView`), then `prepare(host, returnUrl)` runs `/init` and loads the widget;
`onReady(true)` fires when its content rendered. `submit()` runs `/validate`, which itself triggers
Klarna's `authorize()` inside `KlarnaPlugin.postValidation`, only once `/validate` succeeded. The
return URL must resume the **same Activity** hosting the widget — see
[§7](#7-declaring-your-own-deep-link-for-klarna).

#### Card (Purse vault / SecureFields)

> Deep dive — session prerequisites, sequence diagram, quirks, failure modes:
> **[docs/plugins/card-purse-vault.md](../plugins/card-purse-vault.md)**.

`rememberCardCoordinator(host, listener)` owns the SecureFields instance. The host's token (and its
`requiresCvv`) selects the `CardCoordinator.Mode`: `FRESH_CARD` (full form), `TOKEN_CVV` (a saved card
whose PSP still wants the CVV: CVV-only form) or `TOKEN_NO_CVV` (nothing to render, payable as-is).

**`PurseCardForm(coordinator: CardCoordinator, modifier: Modifier = Modifier)`** — the card form
web's hosted form renders: each secure field with its **label** above, its **help text** under it
while focused, and its **error message** in place of the help text when the shopper leaves it empty
(`requiredError`) or SecureFields rejects its content (`formatError`). It renders the four fields in
`FRESH_CARD`, only the CVV in `TOKEN_CVV`, nothing in `TOKEN_NO_CVV`, and sizes itself. This is what
`PaymentElementUi` shows for a card element; SecureFields' bare `VaultForm(coordinator.sdk)` /
`VaultField(...)` remain available for a custom layout.

Copy comes from `PluginOptions.cardFormTexts` (`CardFormTexts`, one `CardFormTexts.FieldTexts` per
field: `label`, `placeholder`, `helperText`, `requiredError`, `formatError`), every `null` falling
back to the SDK's localized `purse_card_*` resources — web's `hostedForm` translations, in English and
French. Styling comes from `PluginOptions.theme` (`PurseTheme`): the `input*` scopes reach
SecureFields' `VaultStyles`, `label*` and `helperText*` reach the texts around them, `global*` is the
base every scope falls back to and `gap` the spacing between fields. `PurseTheme.resolve()` applies
web's `Theme.build()` order — scope variant → scope → global variant → global — over web's default
theme, so a partial theme never leaves a hole. See [recipes §13](recipes.md#13-locale-texts-theme).

| Member | Signature | Notes |
|---|---|---|
| `sdk` | `val sdk: SecureFieldsAndroid` | The live SecureFields instance — render it through `PurseCardForm(coordinator)`, or bare `VaultForm` / `VaultField` for your own layout, only when `mode.rendersForm`. |
| `theme` | `val theme: PurseTheme.Resolved` | The merchant's `PluginOptions.theme` resolved over web's defaults. |
| `mode` | `val mode: CardCoordinator.Mode` | See above; `mode.rendersForm` is `false` only for `TOKEN_NO_CVV`. |
| `formValid` | `var formValid: Boolean` (Compose state) | `false` until every configured field is valid (always `true` in `TOKEN_NO_CVV`). Drive your "Pay" button off this, or off `isPaymentFulfilled`, which the coordinator keeps in step for you. |
| `submit` | `fun submit()` | Tokenizes via SecureFields (the card, or just the CVV), then `/init` + `/validate` through the host; in `TOKEN_NO_CVV` it skips tokenization. A no-op (never an error) while the form isn't complete and valid. |

One trap worth naming: `rememberCardCoordinator` keys its SecureFields instance on the `host` object,
so build the host with `remember(...)` rather than inline —

```kotlin
val host = remember(method.id) { checkout.host(method) }   // NOT checkout.host(method)
val coordinator = rememberCardCoordinator(host, listener)
```

— otherwise every recomposition hands it a new host, tearing down and rebuilding the card form (and
losing whatever the shopper has typed) each time anything else on the screen changes.
`rememberPaymentElement` does this for you.

#### Redirection (generic template)

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

`rememberRedirectionCoordinator(listener)`. There is nothing to render. `prepare(host)` runs `/init`
on selection (it consumes a step against the session, so don't fire one per offered row on screen
entry) and `onReady(true)` follows immediately; `submit()` runs `/validate` and opens the PSP's page.
A `/validate` carrying **no** redirect is reported with a summary saying `"(no redirect returned)"` —
for this template it means the PSP never handed over its page.

### Registering a new plugin, or opting a built-in one out (`PluginRegistry`)

The `register*` functions are only relevant if you're adding a plugin that isn't one of Purse's own —
built-in ones are already registered, and the optional partner artifacts register themselves, see
[§1](#plugins-are-active-by-default--no-setup-call-needed).

| Function | Signature |
|---|---|
| `install` | `fun install(module: PartnerModule)` — add a whole module (its plugins + how they render); every Purse partner artifact on the classpath is installed automatically through `ServiceLoader`, so this is for a module of your own, or for a build that strips `META-INF/services`. Installing the same module twice is a no-op. |
| `modules` | `val modules: List<PartnerModule>` — what is installed, built-ins (card, redirection) first. |
| `moduleFor` | `fun moduleFor(plugin: PartnerUiPlugin): PartnerModule?` — the module that renders a plugin. |
| `register` | `fun register(partner: Partner, method: Method, builder: PluginBuilder)` |
| `registerFallback` | `fun registerFallback(method: Method, builder: PluginBuilder)` |
| `registerVault` | `fun registerVault(vendor: VaultVendor, builder: PluginBuilder)` |
| `registerGeneric` | `fun registerGeneric(template: String, builder: PluginBuilder)` — a reusable **template** keyed by `configurations.template`, naming no partner or method; the session decides which methods it covers. |
| `getPlugin` | `fun getPlugin(partner: Partner, method: Method, session: LegacyPaymentSession): PartnerUiPlugin?` |

Resolution order: vault vendor → generic template (strict equality) →
`merchant_specifics.integration_mode = redirection` → exact `(partner, method)` → method fallback.
`getPlugin` is also the honest way to ask *what will actually handle this item* — e.g.
`getPlugin(...) is RedirectionPlugin` to decide how to render a row, rather than re-reading `template`
yourself and disagreeing with the registry. Full chain and rationale:
[docs/plugins/README.md](../plugins/README.md#resolution--which-plugin-handles-an-item).

---

### Writing your own partner module (`PartnerModule`)

An artifact that adds a payment method contributes one `PartnerModule` — the seam Purse's own
Google Pay, Klarna and PayPal artifacts use. `PluginRegistry` finds them through `ServiceLoader`
(`META-INF/services/eu.purse.partnerui.PartnerModule`, a `class` with a public no-arg constructor)
or you hand one over with `PluginRegistry.install(module)`.

```kotlin
interface PartnerModule {
    fun register(registry: PluginRegistry)                       // add your plugins: register / registerFallback / registerGeneric / registerVault
    fun renders(plugin: PartnerUiPlugin): Boolean                // `plugin is MyPlugin`
    @Composable fun rememberSurface(plugin: PartnerUiPlugin, request: SurfaceRequest): PaymentSurface
}

class SurfaceRequest(val host: PluginHost, val isToken: Boolean, val key: Any, val listener: PaymentElementListener, val appReturnUrl: String? = null)
class PaymentSurface(val hasUi: Boolean, val selfSubmitting: Boolean, onSubmit: () -> Unit, val ui: @Composable (Modifier) -> Unit) {
    fun submit()
    companion object { fun headless(onSubmit: () -> Unit): PaymentSurface }   // nothing on screen; submit runs the flow
}
```

`rememberSurface` is where you create your coordinator (`PaymentCoordinator` subclass), wire its
Activity-result launchers and run `prepare(request.host)`; `rememberPaymentElement` then wraps the
returned `PaymentSurface` exactly as it does for the shipped methods. `PartnerArtifacts.forMethod` /
`missingPluginMessage` are the table behind the "add this dependency" hint the SDK gives when a
Purse artifact is missing; they know only Purse's own artifacts.

---

## 7. Declaring your own deep link for Klarna

Unlike Google Pay's 3-D Secure return (a self-contained Activity `eu.purse.partnerui` ships), Klarna's
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

Pass the matching URL (e.g. `"$packageName.klarna://return"`) as `rememberPaymentElement`'s
`klarnaReturnUrl` (its default is exactly that), or to `KlarnaCoordinator.prepare(host, returnUrl)`.
No manifest change is required for Google Pay or PayPal — the 3-D Secure return Activity ships in
`headless-checkout-android`, PayPal's approval return Activity in `headless-checkout-paypal`.

---

## 8. Submitting payment

```kotlin
suspend fun submitPayment(): ValidationResult
```

Runs the plugin-driven `/validate` pipeline over the current split. Must be called once
`isPaymentFulfilled` is `true`. Sets `sessionState` to `SUBMITTING`, then `SUBMITTED`/`ERROR` —
or back to `READY` when a merchant `onBeforeValidate` hook aborted it before anything was sent.

**One submit per session.** Before anything leaves the device the session's expiry is re-checked
against the server clock, then an atomic latch moves `READY`/`ERROR` to `SUBMITTING`: a second call
while one is in flight (a double tap, from any thread), or after the API has answered about the
payment — approved *or declined* — is refused with `SubmitBlocked` and never reaches the PSP. Only a
`/validate` the API refused outright (a 5xx, a transport failure) leaves the session submittable.
While in flight, every item reads `DisabledState.Submitting`; the lock lifts when the call returns.

**Throws:**
- `PurseCheckoutException.PaymentNotFulfilled` — the split doesn't cover the amount, or no primary
  is set (a zero-amount session needs a primary too).
- `PurseCheckoutException.ItemNotFulfilled` — an item in the split has a form that isn't complete and
  valid (see [§6](#form-completeness-gates-the-payment)).
- `PurseCheckoutException.SessionExpired` — the session expired (`EXPIRED_SESSION`); `sessionState`
  moves to `EXPIRED`. Recover with `setSession`.
- `PurseCheckoutException.SubmitBlocked` — a submit is in flight or the session is spent
  (`SUBMIT_BLOCKED`; `state` says which). Also posted as `HEADLESS_CHECKOUT_SUBMIT_BLOCKED`.
- `PurseCheckoutException.CheckoutTornDown` — after `teardown()`.
- `PurseCheckoutException.HookFailed` — your `onBeforeValidate` hook threw (code
  `ON_BEFORE_VALIDATE_HOOK_FAILED`, your exception as `cause`); nothing was sent.
- `PurseCheckoutException.NoPluginRegistered` — no plugin registered for the chosen primary's
  `(partner, method)`. For Google Pay, Klarna or PayPal this means the optional artifact is missing;
  `PartnerArtifacts.missingPluginMessage(partner, method)` (and the warning `init` logs) name it.
- `PurseCheckoutException.ValidateFailed` — `/validate` failed. **Check `outcomeUnknown`**: `true`
  means the request left the device and no answer came back, so the payment may exist server-side —
  reconcile with `awaitPaymentOutcome()` instead of telling the shopper it failed.

Most callers reach `submitPayment` indirectly, through `PluginHost.submitPayment`, which coordinators
call for you; the element's `PaymentOutcome.Failed` carries the same exception.

### Re-checking status after a redirect or an unknown outcome

```kotlin
suspend fun sessionTransactions(): JSONArray
suspend fun paymentStatus(): PaymentSessionStatus
suspend fun awaitPaymentOutcome(timeoutMs: Long = 60_000, pollIntervalMs: Long = 3_000): PaymentSessionStatus
```

`GET /{entityId}/sessions/{sessionId}` — the source of truth to check after a redirect/3-D Secure
return, or after a `ValidateFailed` whose `outcomeUnknown` is `true`, rather than trusting a
locally-held result. Runs on `Dispatchers.IO`.

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
| `PurseCheckoutException.NoPluginRegistered` | `submitPayment` | No `eu.purse.partnerui` plugin for the chosen `(partner, method)`. |
| `PurseCheckoutException.ValidateFailed` | `submitPayment` | `/validate` failed. `outcomeUnknown == true` means the request left and nothing answered: reconcile with `awaitPaymentOutcome()`. |
| `PurseCheckoutException.SessionExpired` | `submitPayment`, `setSession` | The session (or the new one) is expired (`EXPIRED_SESSION`). |
| `PurseCheckoutException.SubmitBlocked` | `submitPayment`, `setSession` | A submit is in flight, or the session's one validation is spent (`SUBMIT_BLOCKED`). |
| `PurseCheckoutException.CheckoutTornDown` | everything after `teardown()` | The checkout was released (`CHECKOUT_TORN_DOWN`). |
| `PurseCheckoutException.MethodDisabled` | `setPrimary`, `setPrimaryToken`, a self-submitting coordinator's approval | The item's `disabled` state is set — `disabled` says why (`METHOD_DISABLED`). |
| `PurseCheckoutException.InvalidTake` | `applySecondary` with an explicit amount | `INVALID_TAKE_AMOUNT`, `MAX_AMOUNT_REACHED` or `USAGE_LIMIT_ERROR`. |
| `PurseCheckoutException.HookFailed` | `submitPayment` (`onBeforeValidate`), coordinators (`onRedirection`) | A merchant hook threw — `hook` names it, `cause` is the merchant's exception. `ON_BEFORE_VALIDATE_HOOK_FAILED` aborts the submit with nothing sent; `POST_VALIDATE_FAILED` (`onRedirection`) opens no redirect. Neither moves the split. |
| `PurseCheckoutException.NoSecondaryConfig` | `getSecondaryToken` | No `SecondaryConfig` registered for this secondary method. |
| `PurseCheckoutException.InvalidSecondaryCard` | `getSecondaryToken` | Client-side PAN/CVV validation failed. |
| `PurseCheckoutException.SecondaryTokenizationFailed` | `getSecondaryToken` | `/init` unsuccessful, or the card carries no balance. |
| `PurseCheckoutException.MethodNotSimulable` | `simulateLoan` / `simulateLoans` | The method has no `simulable` label (`METHOD_IS_NOT_SIMULABLE`). |
| `PurseCheckoutException.LoanSimulationFailed` | `simulateLoan` / `simulateLoans` | The simulation API answered without a schedule — no `plugin_result`, or a refusal like `EMPTY_SIMULATION` (`INVALID_LOAN_SIMULATION`; the API's code is on the cause). |
| `PurseSessionException.SessionMalformed` | `PurseHeadlessCheckout.init`, `setSession` | `widget.data` couldn't be decoded, or is missing required fields (`SESSION_MALFORMED`). |
| `PaymentApiException` | Any Payment API call | Network/transport failure, non-2xx/3xx status, or an unusable response body (`API_ERROR`; the backend's own reason is `errorCode`, `outcomeUnknown` when no answer came back). |

All of the above extend `eu.purse.core.error.PurseException`: unchecked, with a stable `code`
(`PurseErrorCode`, web's names), a `message` and a `documentationLink`. Catch the root for generic
handling and branch on `code`. Every code the SDK raises is exercised by
`PurseErrorCodeCoverageTest`.

---

## 11. Supporting value types

| Type | Values / shape | Notes |
|---|---|---|
| `SessionState` | `READY, SUBMITTING, SUBMITTED, ERROR, EXPIRED, TERMINATED` | Web's `PurseHeadlessCheckoutSessionState` minus `idle` (a checkout cannot exist without a session). |
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
