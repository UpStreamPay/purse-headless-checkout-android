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
redirection template) is registered automatically in `PluginRegistry`; there is nothing to call. Whether a method actually shows
up is gated by the **session** alone — the same thing that already decides it server-side, in the
merchant's Purse configuration. `paymentMethods` simply won't include a method the session doesn't
offer.

If you need to suppress a shipped method for a specific app build (e.g. its UI isn't ready yet even
though the backend might offer it), opt it out explicitly:

```kotlin
PluginRegistry.disable(Partner.Klarna, Method.Installments)   // eu.purse.partnerui
```

`PluginRegistry.enable(partner, method)` reverses it. `register` / `registerGeneric` / `registerVault` exist for
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
| `params` | `PurseSessionParams` | Decoded `widget.data` (`apiKey`, `apiUrl`, `environment`, `entityId`, `paymentSession`). Replaced by `setSession`. |
| `session` | `PaymentSession` | The built session model (amount, currency, expiration, vault config, means of payment). Replaced by `setSession` — hold the checkout, not the session; observe `paymentSession`. |
| `paymentApiRoot` | `String` | Resolved Payment API root; calls go to `{root}/{entityId}/sessions/{sessionId}/…`. |
| `currency` | `String` | Session currency code. |
| `isInformationRequest` | `Boolean` | Whether this session is a zero-amount information request rather than a real charge. |
| `currentSessionId` | `String` | The Payment API session id (`session.id`). |

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
(`EXPIRED_SESSION`) when the new session is already expired — the old one stays in force — and
`IllegalStateException` while a payment is being submitted. Emits `HEADLESS_CHECKOUT_SET_SESSION`.

Keying UI on `(item.id, generation)` is what makes "keep what is still valid" work on the screen:
`rememberPaymentElement` already does it, so an unchanged card keeps its SecureFields form and
whatever the shopper typed, while a changed method gets a fresh element. Recipe 19 shows the
expired-session recovery loop.

---

## 3. Reactive state — "events" (`StateFlow`)

The reactive surface is exposed as `StateFlow` — the Kotlin analog of web's Svelte stores. Collect
it from a coroutine scope; there is no callback-based equivalent.

| Property | Type | Fires when |
|---|---|---|
| `sessionState` | `StateFlow<SessionState>` | Lifecycle transitions: `READY` → `SUBMITTING` → `SUBMITTED`/`ERROR`, or `EXPIRED` at init if the session already expired (and back to `READY` after `setSession`). |
| `paymentSession` | `StateFlow<PaymentSession>` | The session in force; emits again when `setSession` swaps it — rebuild session-derived UI (amount header, method list) from here. |
| `itemGenerations` | `StateFlow<Map<String, Int>>` | Per-item generation counters bumped by `setSession` for items that did not survive; key anything you remember per item on `(item.id, generation)` — `generationOf(item)` reads the current one. |
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
| `secondaryConfig` | `fun secondaryConfig(method: SecondaryMethod): SecondaryConfig?` | The per-partner form/take-policy rules for a secondary method — `null` if none registered (shipped or via `SecondaryConfigs.register`; e.g. `ancv-connect` is not shipped). |
| `getSecondaryToken` | `suspend fun getSecondaryToken(method: SecondaryMethod, pan: String, cvv: String? = null): SecondaryToken` | `POST /init` — tokenizes a card and returns its balance **without** touching the split. Validate PAN/CVV via `secondaryConfig` first. |
| `applySecondary` | `fun applySecondary(token: SecondaryToken, amount: BigDecimal? = null)` | Draws an already-tokenized `token` into the split, clamped to `min(amount, balance, remaining, limit)`. `amount == null` takes as much as the order needs. |
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

Blocking network work happens via `suspend`; move it to `Dispatchers.IO` at the call site.

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
shopper pays in all — `initial + (count - 2) × installmentAmount + final`, rounded to two decimals.
`installmentCount` counts every instalment, the first and the last included, so a 3× schedule is
three lines: initial, one recurring, final. (Web's `mapPluginResultToSimulation` adds `count - 1`
recurring instalments on top of initial and final and so over-counts one; do not copy it.) Like web,
the schedule carries **counts and amounts only**: the API returns no per-instalment dates.

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
| `setAsPrimary()` | Commit the item as the split's primary. Self-submitting flows call it on approval (web's `validationRequested`); a cancelled sheet leaves the split untouched. |
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
| `onResult(summary: String, globalStatus: String? = null)` | Terminal, non-error outcome. `globalStatus` is the `/validate` response's own `global_status` when the outcome came from one, and `null` after a redirect return — see the note below. |
| `onRequiresAction()` | A redirect step is being launched (3-D Secure, PayPal's approval, the PSP's page). |
| `onPaymentId(paymentId: String)` | Informational — a `payment_id` the flow could read, when present. Never required for the payment to resolve. |
| `onCanceled()` | The shopper backed out (sheet dismissed, approval abandoned, redirect page closed, Klarna declined to authorize). |
| `onError(message: String?)` | Any failure — native step, `/init`, `/validate`, or redirect launch. |

**Note on `onResult` after a redirect:** a returned-from-redirect outcome means only that the shopper
came back into the app — **not** that the payment succeeded. That's the case `onResult` reports with a
`null` `globalStatus`: don't render it as either success or failure. Re-check status independently
(`paymentStatus()` / `awaitPaymentOutcome()`). A non-`SUCCESS` `globalStatus` is a declined payment,
not an error. The demo's own three-way mapping (`PaymentOutcome.of`) is the shape to copy:

```kotlin
when {
    globalStatus == null -> Unconfirmed   // came back from a redirect — go and check the session
    globalStatus.equals("SUCCESS", true) -> Authorized
    else -> Failed
}
```

An `onError` before any submit (a widget that fails to load, a `/init` that throws) is not a failed
payment either — leave the shopper able to pick another method.

#### Google Pay

> Deep dive — `/init` shapes per gateway, sequence diagram, quirks, failure modes:
> **[docs/plugins/google-pay.md](../plugins/google-pay.md)**.

`rememberGooglePayCoordinator(listener)`. `prepare(host)` runs `/init` + Worldline refine and
`isReadyToPay`; `launchSheet()` shows the sheet (call it from the button's tap, after `onReady(true)`);
an approved sheet commits the method as primary and captures. `isReady` and
`allowedPaymentMethodsJson` are Compose state for the SDK's `GooglePayButton(coordinator)`, which
shows itself only once ready and passes button looks (`theme`, `type`, `cornerRadius`).

#### PayPal

> Deep dive — the out-of-app approval round trip, sequence diagram, quirks, failure modes:
> **[docs/plugins/paypal.md](../plugins/paypal.md)**.

`rememberPaypalCoordinator(listener)`. `prepare(host)` does **no network work** — the `/init` that
creates the PayPal order waits for `submit()`, so merely showing the button never leaves an orphan
order. `submit()` runs `/init`, launches PayPal's approval (the PayPal app when installed, else a
Custom Tab), and captures via `/validate` once the buyer approves; with a saved token on the host it
captures straight away. `onRequiresAction` fires for the approval, and again only if `/validate` chains
a 3-D Secure step. **No manifest work**: the approval returns to an Activity `:partner-ui` ships
(scheme `${applicationId}.paypal`).

#### Klarna

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
theme, so a partial theme never leaves a hole. See recipe 16.

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

Pass the matching URL (e.g. `"$packageName.klarna://return"`) as `rememberPaymentElement`'s
`klarnaReturnUrl` (its default is exactly that), or to `KlarnaCoordinator.prepare(host, returnUrl)`.
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

Most callers reach `submitPayment` indirectly, through `PluginHost.submitPayment`, which coordinators
call for you.

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
| `PurseCheckoutException.LoanSimulationFailed` | `simulateLoan` / `simulateLoans` | The simulation API answered without a schedule — no `plugin_result`, or a refusal like `EMPTY_SIMULATION` (`INVALID_LOAN_SIMULATION`; the API's code is on the cause). |
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
