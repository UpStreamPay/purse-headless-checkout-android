# Headless Checkout — Android integration recipes

Code-first recipes for the Purse Headless Checkout Android SDK, one per integration case. They
mirror the web guides at
[docs.purse.tech → Headless Checkout](https://docs.purse.tech/docs/integrate/purse-checkout/headless-checkout/getting-started)
(getting started, primary/secondary methods, gift cards, saved methods, amounts, event handling,
hooks, redirect, customization) — same concepts, Kotlin surface.

The SDK is **headless**: it owns the payment engine (session, methods, split, `/validate`), you own
every pixel. Reactive state is exposed as `StateFlow`/`SharedFlow` — the Kotlin analog of the web
SDK's stores.

All snippets assume:

```kotlin
import eu.purse.checkout.PurseHeadlessCheckout
```

**Conventions used by the snippets.** The SDK exposes its reactive state as `StateFlow`s, and a
flow only emits to whoever *collects* it — a suspending loop that must live inside a coroutine.
The snippets start that coroutine with `lifecycleScope.launch { … }`: `lifecycleScope` is
AndroidX's per-`Activity`/`Fragment` coroutine scope (`androidx.lifecycle.lifecycleScope`, from
`lifecycle-runtime-ktx`), which cancels its coroutines automatically when the screen is destroyed,
so a collector can never leak or touch dead views. Inside `collect { value -> … }` the lambda runs
once per emission, receiving the flow's current value. In Compose, skip both and use
`checkout.isPaymentFulfilled.collectAsState()` / `collectAsStateWithLifecycle()` instead.

---

## 1. Initialize the checkout

Your backend creates a Client Session against the Purse API and hands your app the base64
`widget.data` string (the "V2 params" — identical to web). Decode it with `init`:

```kotlin
val checkout = PurseHeadlessCheckout.init(widgetData)
```

A release build should **lock the environment** instead of trusting whatever the session declares
(the analog of web's build-time `SDK_ENV` enforcement):

```kotlin
val checkout = PurseHeadlessCheckout.init(
    widgetData,
    lockedEnvironment = PurseEnvironment.PRODUCTION,
)
```

`init` throws `PurseSessionException.SessionMalformed` when the blob can't be decoded or lacks
`id`/`expiration_date`. Everything else about the session is surfaced, not rejected.

A session can be `/init`-ed many times but **`/validate`-d only once** — watch `sessionState`:

```kotlin
lifecycleScope.launch {
    checkout.sessionState.collect { state ->
        when (state) {
            SessionState.READY      -> Unit                   // build your UI
            SessionState.SUBMITTING -> showSpinner()
            SessionState.SUBMITTED  -> Unit                   // spent — see recipe 12
            SessionState.ERROR      -> showRetry()            // transient, session still usable
            SessionState.EXPIRED,                             // watchdog fired (server clock)
            SessionState.TERMINATED -> restartCheckout()      // fatal — mint a new session
        }
    }
}
```

The expiry watchdog runs on a **server-corrected clock** (`GET /datetime`), so a wrong device
clock neither kills a valid session nor keeps a dead one alive.

---

## 2. Render the payment method list

`paymentMethods` carries primaries and secondaries with **live disabled states** — suspended
methods (`stable: false`) and unreachable ones (`payment_min_amount` above the order) are already
filtered out for you, exactly like web:

```kotlin
lifecycleScope.launch {
    checkout.paymentMethods.collect { items ->
        methodList.render(
            items.map { item ->
                MethodRow(
                    id = item.id,                              // "purse-creditcard", "paypal-wallet"…
                    iconUrl = checkout.iconUrl(item),          // CDN badge SVG (load with Coil)
                    enabled = item.disabled == null,
                    disabledReason = item.disabled?.code?.name, // e.g. MIN_AMOUNT_NOT_MET
                    isSecondary = item.isSecondary,
                )
            },
        )
    }
}
```

For a vaulted card method you can also render the accepted schemes:

```kotlin
val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
val schemes = checkout.schemeAssets(card) // [(cb.svg, "Cartes Bancaires"), (visa.svg, "Visa"), …]
```

Amount-driven disabled states **recompute automatically** as the split changes: apply a gift card
and a primary whose minimum is no longer reachable dims itself (recipe 10).

---

## 3. Select a primary and submit

The split model is **one primary + N secondaries**. Selecting and submitting:

```kotlin
checkout.setPrimary(method)          // or checkout.clearPrimarySplit()

lifecycleScope.launch {
    // Emits on every split/form change; only true once the split covers the amount
    // AND every form in it is valid.
    checkout.isPaymentFulfilled.collect { fulfilled -> payButton.isEnabled = fulfilled }
}

payButton.setOnClickListener {
    lifecycleScope.launch {
        try {
            val result = withContext(Dispatchers.IO) { checkout.submitPayment() }
            // result.globalStatus, result.raw — but for redirect flows prefer recipe 12.
        } catch (e: PurseCheckoutException) {
            handle(e.code) // typed — see recipe 15
        }
    }
}
```

> Interactive methods (card, Google Pay, PayPal, Klarna, redirection) are normally rendered
> through the **unified payment element** (recipe 4) — or driven through their individual
> coordinators (recipes 5–9) — which wrap `submitPayment()` with the vendor-specific steps. Call
> `submitPayment()` yourself only for flows you drive end to end.

---

## 4. The payment element — render any method with one API

The Compose analog of web's `getPaymentElement`: one call resolves the same plugin `/validate`
will use, renders whatever that method needs on screen — the SecureFields card form, the official
Google Pay / PayPal button, Klarna's native widget, nothing at all for a redirection method — and
wires launchers, `prepare`, and lifecycle automatically. One listener shape
(`PaymentElementListener`) covers every method.

```kotlin
@Composable
fun SelectedMethod(checkout: PurseHeadlessCheckout, method: PrimaryMethod) {
    val element = rememberPaymentElement(checkout, method, listener = object : PaymentElementListener {
        override fun onResult(summary: String, globalStatus: String?) { /* recipe 12 if null */ }
        override fun onCanceled() { }
        override fun onError(message: String?) { }
    })

    Column {
        // The method's surface: card form / wallet button / Klarna widget. No-op when
        // element.hasUi is false (redirection methods — their hosted page IS the payment step).
        PaymentElementUi(element, Modifier.fillMaxWidth())

        // Your own Pay button drives form-like methods; wallet buttons submit themselves.
        if (!element.selfSubmitting) {
            Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
        }
    }
}
```

The element's three flags tell you how to lay out the row, per method:

| Method | `hasUi` | `selfSubmitting` | You render |
|---|---|---|---|
| Card (Purse vault) | ✓ | — | the element (form) + your Pay button |
| Google Pay / PayPal | ✓ | ✓ | the element (their branded button), nothing else |
| Klarna | ✓ | — | the element (widget) + your Pay button |
| Redirection template | — | — | just your Pay button |

### Create vs render vs ready — the element's lifecycle

Two distinct steps hide behind the element, and it helps to keep them apart:

- **Create = prepare (data).** `rememberPaymentElement(...)` triggers the method's `prepare` —
  web's element *initialization*, not its rendering. What that costs depends on the method:

  | Method | What creating the element actually runs |
  |---|---|
  | Card (Purse vault) | nothing — SecureFields mounts on its own; the card's `/init` happens at submit |
  | Google Pay | `POST /init` (gateway config) + the `isReadyToPay` check |
  | PayPal | almost nothing — its `/init` is deferred to the button tap, so an abandoned selection never leaves an orphan PayPal order |
  | Klarna | `POST /init` → `client_token` → inflate + load the native widget |
  | Redirection template | `POST /init` → holds the `step_id` for `/validate` |

  Because an `/init` costs a network round-trip **and a session step**, create the element **when
  the shopper selects the method** — not for every row of the list.

- **Render = pixels.** `PaymentElementUi(element)` places the surface (form / button / widget).
  One deliberate coupling: **Klarna's prepare waits for the render** — its native widget must
  inflate into a real container, so the `/init` only fires once `PaymentElementUi` is actually
  placed. A Klarna element you never render never `/init`s, which is the safe behavior.

- **`ready` = the bridge.** Compose state that flips once prepare (and, for the card, form
  validity) says the method is usable — gate your Pay button on it. The wallet buttons use it
  themselves to decide when to appear.

Selection is handled for you: form-like methods go through `checkout.setPrimary(method)` as in
recipe 3; wallet buttons commit their method on approval.

Recipes 5–9 below show each method's element under the hood — the imperative coordinator path for
non-Compose screens, where `prepare(host)` is the visible name of the "create" step above.

---

## 5. Card payment (Compose + SecureFields)

The element renders the SecureFields card form. PAN/expiry/CVC validation and brand detection
happen **inside** SecureFields — you only observe `element.ready` (the form's validity):

```kotlin
@Composable
fun CardPayment(checkout: PurseHeadlessCheckout, method: PrimaryMethod) {
    val element = rememberPaymentElement(checkout, method, listener = object : PaymentElementListener {
        override fun onResult(summary: String, globalStatus: String?) {
            // globalStatus == null after a 3-D Secure return → confirm via recipe 12.
        }
        override fun onError(message: String?) { }
    })
    LaunchedEffect(method.id) { checkout.setPrimary(method) }   // form-like: selection is your call

    Column {
        PaymentElementUi(element, Modifier.fillMaxWidth().height(220.dp))   // ← the render: the form
        Button(
            onClick = { element.submit() },   // tokenize → /init → /validate → follow 3DS
            enabled = element.ready,
        ) { Text("Pay") }
    }
}
```

Mounting the form arms the fulfillment gate: an **empty card form never reads as payable**
(`isPaymentFulfilled` stays `false` until every field validates).

**Under the hood** the element wraps `rememberCardCoordinator(checkout.cardHost(method), listener)`
+ SecureFields' `VaultForm(coordinator.sdk)` — use those directly when you need the lower-level
surface, e.g. a custom brand picker for co-badged cards (CB + Visa) calling
`coordinator.sdk.setBrandSelection(...)`; by default the detected brand is used.

---

## 6. Google Pay

Google Pay is **rendered, not driven**, exactly like web — its element *is* the official button
with the whole flow wired inside: Google's own brand-guideline `PayButton` (showing the shopper's
card art), appearing only once `isReadyToPay` said yes, and from the tap on the SDK owns
everything — sheet → the **approval** selects the method as primary → `/validate`
→ 3-D Secure if any (web's `validationRequested`). A cancelled sheet leaves the split untouched.
You never call `setPrimary` or `submitPayment`; outcomes land on the listener.

```kotlin
@Composable
fun GooglePayRow(checkout: PurseHeadlessCheckout, method: PrimaryMethod) {
    val element = rememberPaymentElement(checkout, method, listener = object : PaymentElementListener {
        override fun onResult(summary: String, globalStatus: String?) { /* recipe 12 if null */ }
        override fun onCanceled() { /* sheet dismissed — split untouched, nothing submitted */ }
        override fun onError(message: String?) { }
    })

    // ← the render: the official button. Place it unconditionally — it shows itself once ready,
    // and it IS the submit (element.selfSubmitting == true; no Pay button of yours).
    PaymentElementUi(element, Modifier.fillMaxWidth())
}
```

**Under the hood** the element wraps `rememberGooglePayCoordinator(listener)` + `prepare(
checkout.googlePayHost(method))` (`/init` + `isReadyToPay`) + `GooglePayButton(coordinator)` —
use those directly to pass button looks (`theme = ButtonTheme.LIGHT`, `type = ButtonType.BUY`,
`cornerRadius = 8.dp`), from a View-system screen (construct `GooglePayCoordinator(activity,
listener)` in `onCreate`), or to drive the sheet from a custom control via
`coordinator.launchSheet()` — though Google's brand guidelines require the official button asset.

---

## 7. PayPal (wallet, pay-now)

Self-submitting like Google Pay — no `setPrimary`, no `submitPayment`. Creation is cheap (no
`/init`: PayPal's order is only created on the button tap, so an abandoned selection never leaves
an orphan order), and the buyer's **approval** selects the method and triggers `/validate`:

```kotlin
@Composable
fun PaypalRow(checkout: PurseHeadlessCheckout, method: PrimaryMethod) {
    val element = rememberPaymentElement(checkout, method, listener = object : PaymentElementListener {
        override fun onResult(summary: String, globalStatus: String?) { /* recipe 12 if null */ }
        override fun onCanceled() { }
        override fun onError(message: String?) { }
    })

    // ← the render: PayPal's own branded button (payment-buttons artifact). It IS the submit:
    // tap → /init → PayPal app/browser approval → (approval selects PayPal as primary) → /validate.
    PaymentElementUi(element, Modifier.fillMaxWidth())
}
```

**Under the hood** the element wraps `rememberPaypalCoordinator(listener)` +
`prepare(checkout.paypalHost(method))` + `PaypalButton(coordinator)` — use those directly from a
View-system screen (construct `PaypalCoordinator(activity, listener)` in `onCreate`), or drive a
fully custom button via `paypal.submit()` as the escape hatch.

---

## 8. Klarna (installments)

Klarna renders its own native widget into a container you provide, and needs a return-URL
intent-filter in **your** manifest:

```xml
<activity android:name=".CheckoutActivity" android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="${applicationId}.klarna" android:host="return" />
    </intent-filter>
</activity>
```

The render step is explicit: `PaymentElementUi` places the container, and only then does the
element run its prepare (`/init` → `client_token` → widget load) into it:

```kotlin
@Composable
fun KlarnaPayment(checkout: PurseHeadlessCheckout, method: PrimaryMethod) {
    val element = rememberPaymentElement(checkout, method, listener)
    // Klarna is form-like: selecting it as the split's primary is still your call (recipe 3);
    // only the wallet buttons self-select on approval.
    LaunchedEffect(method.id) { checkout.setPrimary(method) }

    PaymentElementUi(element, Modifier.fillMaxWidth())   // ← the render: Klarna's widget appears here

    Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay with Klarna") }
}
```

On submit the order is web-parity: `/validate` first, then — only on its success — Klarna's own
`authorize()` inside the plugin's `postValidation`.

**Under the hood** the element wraps `rememberKlarnaCoordinator(listener)` +
`prepare(checkout.klarnaHost(method, returnUrl))` + `submit()`. For a View-system screen,
construct `KlarnaCoordinator(activity, container, listener)` in `onCreate` — there is no separate
render call on that path: the `ViewGroup` you hand the constructor *is* the render surface, and
`prepare` inflates the widget into it (render and prepare collapse into one step, which is exactly
what the element path un-collapses).

---

## 9. Redirection methods (generic template)

One element serves **every** method the session labels `template: "redirection"` (or
`integration_mode: "redirection"`) — Fintecture, Bizum, Oney, Sequra… — without naming any of
them. It's the one element with `hasUi == false`: there is nothing to render, because the PSP's
hosted page *is* the payment step:

```kotlin
@Composable
fun RedirectionMethodRow(checkout: PurseHeadlessCheckout, method: PrimaryMethod) {
    // Create ONLY once the shopper selects this method — creation runs the /init
    // (a network round-trip and a session step).
    val element = rememberPaymentElement(checkout, method, listener)
    LaunchedEffect(method.id) { checkout.setPrimary(method) }

    // element.hasUi == false — PaymentElementUi renders nothing; your Pay button is the whole UI.
    Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
    // submit: /validate → PSP page in the hardened in-app WebView
}
```

**Under the hood** the element wraps `rememberRedirectionCoordinator(listener)` +
`prepare(checkout.redirectionHost(method))` + `submit()` — construct
`RedirectionCoordinator(activity, listener)` in `onCreate` for a View-system screen.

The shopper returning from the PSP page reports `onResult(..., globalStatus = null)`: that means
"they came back", **not** "it worked" — settle it with recipe 12.

---

## 10. Gift cards & co-payment (secondary methods)

Secondaries (gift cards, vouchers, loyalty) tokenize through `/init`, then draw down the amount
before the primary. Client-side PAN/CVV rules per partner are available up front:

```kotlin
val giftCard = checkout.secondaryMethods.single { it.partner == Partner.Illicado }
val rules = checkout.secondaryConfig(giftCard)   // PAN regex, CVV requirement, take policy

lifecycleScope.launch {
    try {
        val token = withContext(Dispatchers.IO) {
            checkout.getSecondaryToken(giftCard, pan = "9250004100006146860", cvv = "509")
        }
        // token.balance is what the card can cover.
        checkout.applySecondary(token)                          // draw as much as possible…
        // checkout.applySecondary(token, BigDecimal("2.00"))   // …or cap the draw (splittable cards)
    } catch (e: PurseCheckoutException) {
        when (e.code) {
            PurseErrorCode.SECONDARY_METHOD_WRONG_PAN_FORMAT,
            PurseErrorCode.SECONDARY_METHOD_REQUIRED_CVV,
            PurseErrorCode.SECONDARY_METHOD_WRONG_CVV_FORMAT -> markFieldInvalid(e)
            PurseErrorCode.SECONDARY_TOKEN_EMPTY_BALANCE -> showEmptyBalance()
            else -> showError(e)
        }
    }
}
```

Track the running split reactively:

```kotlin
lifecycleScope.launch {
    checkout.remainingAmountToPay.collect { remaining ->
        // amount a primary still has to cover after applied secondaries
        primarySection.isVisible = remaining.signum() > 0
    }
}
lifecycleScope.launch {
    checkout.amountSplit.collect { splits ->  // one PaymentSplit per instrument
        splitSummary.render(splits.map { "${it.source.id} → ${it.amount}" })
    }
}
```

`checkout.removeSecondary(token)` puts the amount back. If secondaries cover everything, no
primary is needed — `isPaymentFulfilled` flips to `true` on its own and `submitPayment()` sends
the gift-card items alone. Non-splittable instruments (loyalty) always take their maximum;
per-session usage limits and balances are enforced by the splitter.

**Keep the gift card for next time** — when the method's `save_token_mode` lets the shopper
choose (`PREFER` / `PREFER_NO`), offer a "save" box; the decision rides that card's own
`/validate` item (`payment_preferences.registration` + `payment_data.card.token_label`):

```kotlin
if (token.canBeRegistered) {                                  // never true for a saved card
    saveBox.isChecked = checkout.registrationOf(token).registered   // default from save_token_mode
    saveBox.setOnCheckedChangeListener { _, checked ->
        checkout.registerSecondary(token, registered = checked, name = "gift one")
        // throws WalletOperationFailed(INVALID_TOKEN_NAME / TOKEN_NAME_EXISTS) on a bad label
    }
}
checkout.applySecondary(token)
```

**Saved gift cards** — a session whose secondary method carries `integrated_tokens` surfaces them
on `savedSecondaryTokens`. The session has no balance for them, so the SDK `/init`s each one in the
background right after `init`; render "checking…" until `balanceResolved`, and one tap draws the
card in — no number or CVC to type:

```kotlin
lifecycleScope.launch {
    checkout.savedSecondaryTokens.collect { saved ->
        savedGiftCards.render(
            saved.map { card ->
                SavedGiftCardRow(
                    label = card.name.ifBlank { "•••• ${card.pan.takeLast(4)}" },
                    balance = card.balance.takeIf { card.balanceResolved },   // null → "checking…"
                    unavailable = card.disabled != null,                     // TokenInitFailed: stays listed, dimmed
                    onUse = { checkout.applySecondary(card) },               // as much as the order needs
                    onRemove = { checkout.removeSecondary(card) },
                )
            },
        )
    }
}
```

`applySecondary(saved)` throws `SecondaryTokenizationFailed(SECONDARY_TOKEN_EMPTY_BALANCE)` while
the balance isn't in yet (or is zero), and `(FAILED_TO_INIT_SECONDARY_TOKEN)` when the card's own
`/init` failed. Rename / delete go through the wallet, exactly like saved cards (§11):
`checkout.editToken(saved, "work card")` / `checkout.deleteToken(saved)` — a `wallet_session` is
required, otherwise `WalletOperationFailed(MISSING_WALLET_SESSION)`.

**An issuer the SDK doesn't ship** — implement `SecondaryConfig` and register it once at app
start; `Partner.Other` / `Method.Other` carry the wire values the session uses:

```kotlin
object AcmeVoucherConfig : SecondaryConfig {
    override val takePolicy = SecondaryTakePolicy.MAX                 // non-splittable
    override fun requiresCvv(pan: String) = false
    override fun validatePan(pan: String) = pan.startsWith("ACME-")
    override fun buildInitPaymentData(pan: String, cvv: String?) = JSONObject().put("voucher_code", pan)
    override fun buildValidatePaymentData(token: SecondaryToken) = JSONObject().put("voucher_code", token.pan)
    // Optional: shouldPoll / pollForToken for a two-phase partner, classifyInitError for typed refusals,
    // parseToken when the /init response isn't the generic `payment_data.cards[0].balance` shape.
}

SecondaryConfigs.register(Partner.Other("acme"), Method.Other("voucher"), AcmeVoucherConfig)
// From here on, `secondaryConfig`, `getSecondaryToken`, saved-token balances and /validate items
// for acme/voucher all go through AcmeVoucherConfig — no SDK change.
```

---

## 11. Saved cards (wallet)

When the session carries `integrated_tokens` they surface on `paymentTokens` (never mixed into
`paymentMethods`), each with display metadata:

```kotlin
lifecycleScope.launch {
    checkout.paymentTokens.collect { tokens ->
        savedCards.render(
            tokens.map { token ->
                SavedCardRow(
                    label = token.name.ifBlank { token.label },  // "my visa" / "VISA •••• 4242"
                    maskedPan = token.maskedPan,
                    favorite = token.favorite,
                    needsCvv = token.requiresCvv,   // inverse of the pay_token_no_cvv / network-token gates
                )
            },
        )
    }
}
```

**Pay with a saved card** — the token becomes the split's primary, and the element renders whatever
the PSP still asks for: a **CVV-only** field when `token.requiresCvv`, **nothing** otherwise. Same
shape as recipe 5, with the token in place of the method:

```kotlin
@Composable
fun SavedCardPayment(checkout: PurseHeadlessCheckout, token: PaymentToken) {
    val element = rememberPaymentElement(checkout, token, listener = object : PaymentElementListener {
        override fun onResult(summary: String, globalStatus: String?) { /* recipe 12 if globalStatus == null */ }
        override fun onError(message: String?) { }
    })
    LaunchedEffect(token.id) { checkout.setPrimaryToken(token) }   // selection is your call

    Column {
        if (element.hasUi) {                                        // requiresCvv → the CVV field
            PaymentElementUi(element, Modifier.fillMaxWidth().height(56.dp))
        }
        Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
    }
}
```

`setPrimaryToken` on a token that requires a CVV arms the same fulfillment gate a fresh card form
arms: `isPaymentFulfilled` stays `false` (and `submitPayment()` throws `ItemNotFulfilled`) until the
CVV field validates — so a screen that forgets to render the element cannot submit the token. On
`/validate` the CVV-only vault token travels as `payment_data.vault_vendor_token_id` next to
`payment_data.token`, exactly like web. A token behind the no-CVV gates is payable the moment it's
selected; `element.ready` is `true` immediately and `element.hasUi` is `false`.

Lower level: `rememberCardCoordinator(remember(token.id) { checkout.cardHost(token) }, listener)` —
the coordinator's `mode` (`TOKEN_CVV` / `TOKEN_NO_CVV`) tells you whether to place `VaultForm(coordinator.sdk)`.

**Save a fresh card** — driven by the method's `save_token_mode`:

```kotlin
val card = checkout.primaryMethods.single { it.method == Method.CreditCard }
if (card.canBeRegistered) {                        // PREFER / PREFER_NO only
    saveCheckbox.isChecked = checkout.registrationOf(card).registered
    saveCheckbox.setOnCheckedChangeListener { _, checked ->
        checkout.register(card, registered = checked, name = "holiday card")
        // throws WalletOperationFailed(INVALID_TOKEN_NAME / TOKEN_NAME_EXISTS) on a bad label
    }
}
// The decision travels into /validate automatically (payment_preferences.registration
// + payment_data.card.token_label) and into SecureFields' own saveToken.
```

**Manage the wallet** — needs a `wallet_session` in the widget data (all suspend, network-bound):

```kotlin
lifecycleScope.launch(Dispatchers.IO) {
    checkout.editToken(token, name = "work card")   // PATCH …/tokens/{id}/name
    checkout.setTokenFavorite(token)                // optimistic, reverts on failure
    checkout.deleteToken(token)                     // also clears it from the split
    checkout.deleteAllTokens()
}
// Without a wallet session these throw WalletOperationFailed(MISSING_WALLET_SESSION).
```

---

## 12. Confirm the payment after a redirect

On web, the final redirect lands on your success page — on Android the shopper lands **back in
your app**, so "they returned" must be settled against the session's own transactions. Never treat
a redirect return as a success by itself:

```kotlin
override fun onResult(summary: String, globalStatus: String?) {
    if (globalStatus != null) {
        // /validate answered directly — SUCCESS really is success.
        render(globalStatus)
        return
    }
    // Came back from 3-D Secure / a PSP page: poll the source of truth.
    lifecycleScope.launch {
        when (val status = checkout.awaitPaymentOutcome(timeoutMs = 60_000, pollIntervalMs = 3_000)) {
            is PaymentSessionStatus.Confirmed -> showSuccess()   // a successful AUTHORIZE/CAPTURE exists
            is PaymentSessionStatus.Failed    -> showFailure()
            is PaymentSessionStatus.Pending   -> showStillProcessing(status.operations)
        }
    }
}
```

Row-count caveat: the response lists **operations, not payments** — one auto-captured payment is
an `AUTHORIZE` **and** a `CAPTURE` row on Payment API v2 (linked by `parent_transaction_id`),
aggregated into one row on v1. `PaymentSessionStatus` already accounts for that; if you parse
`checkout.sessionTransactions()` yourself, don't read two rows as a duplicate.

---

## 13. Events & monitoring

**Plugin lifecycle events** — everything the plugins report (the analog of web's
`element.on(...)`), flattened onto one flow. The SDK already *reacts* to the load-bearing ones
(deactivation, fulfillment, auto-submit, termination); collect it for rendering concerns:

```kotlin
lifecycleScope.launch {
    checkout.pluginEvents.collect { (itemId, event) ->
        when (event) {
            is PluginEvent.InitFailed   -> dimMethod(itemId, event.message)
            is PluginEvent.PartnerError -> if (event.recoverable) showTransientBanner(itemId)
            PluginEvent.Redirect        -> showLeavingAppHint()
            PluginEvent.Cancelled       -> restorePayButton()
            else                        -> Unit
        }
    }
}
```

**Analytics bus** — the same six public codes web exposes (`READY`, `EXPIRED_SESSION`,
`PAYMENT_FULFILLED`, `OTHER_ERROR`, `CLIENT_HOOK_ERROR`, `PAYMENT_ERROR`):

```kotlin
val unsubscribe = CheckoutEventBus.getInstance().subscribe { event ->
    analytics.track(event.code.name, event.payload?.toString())
}
// … later
unsubscribe()
```

Every event also ships automatically (batched: 16 events / 10 s / 64 KB, flushed when the app
backgrounds) to Purse's monitoring API — nothing to wire.

---

## 14. Hooks

Run merchant logic around `/validate` and take the redirect over — web's `onBeforeValidate` /
`onAfterValidate` / `redirectionHandler`, same three points:

```kotlin
val checkout = PurseHeadlessCheckout.init(
    widgetData,
    hooks = PurseCheckoutHooks(
        onBeforeValidate = { splits ->
            // Throwing here ABORTS the submit: nothing is sent, submitPayment throws
            // PurseCheckoutException.HookFailed (code ON_BEFORE_VALIDATE_HOOK_FAILED, your
            // exception as the cause) and the checkout is back to READY, split untouched.
            backend.reserveStock(splits.map { it.source.id to it.amount })
        },
        onAfterValidate = {
            // Runs after /validate answers, before any redirection. Errors are swallowed
            // (reported as a HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR monitoring event).
            backend.notifyValidateDone()
        },
        onRedirection = { action ->
            // Called with the resolved redirect BEFORE the SDK opens its in-app WebView.
            // Return true to handle it yourself (the SDK then opens nothing), false to let
            // the SDK proceed. Confirming the outcome on return is yours: paymentStatus().
            when (action) {
                is RedirectAction.Get -> { openInCustomTab(action.url); true }
                is RedirectAction.PostForm, is RedirectAction.PostHtml -> false // WebView-only shapes
            }
        },
    ),
)
```

A hook that throws never corrupts the checkout. `onBeforeValidate` aborts before the POST and the
checkout stays submittable; `onRedirection` opens nothing and the failure reaches the payment
element's `onError` (`"onRedirection hook failed: …"`) — the split is intact and the shopper can
submit again. Both are also posted to monitoring (`HEADLESS_CHECKOUT_ON_BEFORE_VALIDATE_HOOK_FAILED`,
`HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR`).

Express flows a plugin auto-submits (a wallet sheet approval) skip `onBeforeValidate`, exactly like
web's `xpay` trigger. `onRedirection` is consulted by **every** method that can redirect — card
3-D Secure, Google Pay, PayPal's chained 3-D Secure, Klarna, and the generic redirection template.

---

## 15. Error handling

Every SDK exception carries a machine-readable `code` (shared vocabulary with web) and a
documentation link:

```kotlin
try {
    withContext(Dispatchers.IO) { checkout.submitPayment() }
} catch (e: PurseCheckoutException) {
    when (e.code) {
        PurseErrorCode.PAYMENT_NOT_FULFILLED_ON_SUBMIT -> highlightMissingAmount()
        PurseErrorCode.ELEMENT_NOT_FULFILLED           -> focusInvalidForm()
        PurseErrorCode.VALIDATE_FAILED                 -> showRetry()
        else -> log(e.code, e.documentationLink)
    }
} catch (e: PaymentApiException) {
    // Transport/API layer: e.status (HTTP), e.errorCode (body), e.isSessionTerminal.
    if (e.isSessionTerminal) restartCheckout() else showRetry()
}
```

Transient `/init` failures are already retried for you (3 attempts, backoff, concurrent duplicates
coalesced, terminal failures fast-rejected) — don't wrap `/init`-driven calls in your own retry
loop.

---

## 16. Customization — locale, texts, theme

```kotlin
val checkout = PurseHeadlessCheckout.init(
    widgetData,
    options = PluginOptions(
        locale = "fr-FR",   // browser-data locale; card-form copy localizes via resources
        cardFormTexts = CardFormTexts(
            // One FieldTexts per secure field — label, placeholder, help text, and the two error
            // messages. Every null falls back to the SDK's values-fr/ resources.
            cardNumber = CardFormTexts.FieldTexts(label = "N° de carte", formatError = "Ce numéro est invalide."),
            cvv = CardFormTexts.FieldTexts(label = "CVV / CVC", helperText = "3 chiffres au dos de la carte."),
        ),
        theme = PurseTheme(
            // `global` is the base every scope falls back to (web's `global`).
            global = PurseTheme.Text(color = "#1A1A1A", fontFamily = "sans-serif", fontSize = "14px"),
            globalInvalid = PurseTheme.Text(color = "#B3261E"),        // labels, messages AND inputs turn red
            input = PurseTheme.FieldStyle(
                placeholderColor = "#9AA0A6",
                backgroundColor = "#FFFFFF",
                fontSize = "16px",
            ),
            inputFocus = PurseTheme.FieldStyle(color = "#0B57D0"),
            label = PurseTheme.Text(fontWeight = "600"),
            helperText = PurseTheme.Text(fontSize = "12px", color = "#5F6368"),
            gap = "12px",
        ),
    ),
)
```

The theme reaches the **input fields** (SecureFields' `VaultStyles`), the **labels**, the **help
texts** and the **error messages** the SDK's card form renders around them (`PurseCardForm`, which
is what `PaymentElementUi` shows for a card element). Resolution follows web's `Theme.build()`:
each key is read from the scope variant, then the scope, then the global variant, then `global`,
and anything you leave `null` keeps web's default (`#1f2933` text, `#ba2525` invalid, `#0f8613`
valid, 14px, 8px gap). Values are CSS-like strings: `#rrggbb` / `#rrggbbaa` / `transparent`
colours, `"14px"` sizes (read as sp), `"400"`/`"bold"` weights, generic font families.

Without overrides the card form falls back to the SDK's localized resources (`values/`,
`values-fr/`, …), following the device locale — the same copy as web's hosted form (`Card number`,
`Expiration date`, `Security code`, `Cardholder name`, their placeholders, help texts and
`… required.` / `Invalid ….` messages).

---

## 17. Lifecycle & teardown

```kotlin
override fun onDestroy() {
    super.onDestroy()
    if (isFinishing) checkout.teardown()   // NOT on configuration changes
}
```

`teardown()` stops the expiry watchdog, gives every live plugin its `destroyClient`, and flushes
the analytics queue. The instance is unusable afterwards — build a new one from fresh widget data
for the next payment (sessions are single-shot anyway).

---

## 18. BNPL instalment simulation

Instalment partners flagged `simulable` quote their schedule up front. Gate the affordance on the
flag, then ask for the schedule for the amount you want financed — usually the remaining amount:

```kotlin
val bnpl = checkout.primaryMethods.firstOrNull { it.simulable } ?: return

lifecycleScope.launch {
    try {
        val schedule = checkout.simulateLoan(
            bnpl,
            LoanProduct(amount = checkout.remainingAmountToPay.value, currencyCode = checkout.currency),
        )
        instalmentsView.render(
            count = schedule.installmentCount,          // e.g. 3
            first = schedule.initialInstallmentAmount,  // first instalment
            each = schedule.installmentAmount,          // the recurring ones
            last = schedule.finalInstallmentAmount,
            cost = schedule.totalCost,                  // price of the credit
            total = schedule.totalAmount,               // everything the shopper pays
            legal = schedule.legalText,                 // display when present
        )
    } catch (e: PurseCheckoutException) {
        when (e.code) {
            PurseErrorCode.METHOD_IS_NOT_SIMULABLE -> hideInstalments()   // shouldn't happen behind the flag
            PurseErrorCode.INVALID_LOAN_SIMULATION -> showUnavailable()   // partner answered without a schedule
            else -> throw e
        }
    }
}
```

`simulateLoans(...)` returns every schedule the partner offers (the `/multi` endpoint) when you want
to let the shopper pick between 3×, 4×, 10×… The list skips unusable candidates and may be empty.

Like web, a schedule carries counts and amounts but **no per-instalment dates** — the API doesn't
return them. Simulation is a quote only: paying still goes through the normal `setPrimary` +
`submitPayment` path.

---

## Where to go deeper

- [`docs/architecture/headless-checkout.md`](architecture/headless-checkout.md) — module map, the
  purse-payment concept, web-parity notes, what's deliberately deferred.
- [`docs/plugins/`](plugins/) — per-plugin internals (Google Pay gateway configs, PayPal order
  lifecycle, Klarna widget states, redirection resolution priority).
- The `:app` demo module — a working playground driving every recipe above against a sandbox
  session minted through alfred.

---

## 19. Refresh the session — cart changed, or session expired

`setSession` swaps the session under a live checkout (web's `setSession`). Two triggers:

```kotlin
// 1 · The cart changed: your backend minted a new Client Session for the new amount.
lifecycleScope.launch {
    val widgetData = backend.createClientSession(cart)      // your own API
    checkout.setSession(widgetData)                          // suspend; syncs the server clock first
}

// 2 · The session expired under the shopper: recover instead of restarting.
lifecycleScope.launch {
    checkout.sessionState.collect { state ->
        if (state == SessionState.EXPIRED) {
            try {
                checkout.setSession(backend.createClientSession(cart))
            } catch (e: PurseCheckoutException.SessionExpired) {
                // The replacement was itself expired — the old session stays in force; ask again.
            }
        }
    }
}
```

What the shopper keeps: any primary method whose configuration is unchanged in the new session
keeps its selection, its collected data and its rendered element — a half-typed card form survives
a cart change. Anything else is rebuilt from the new session: changed or vanished methods get a new
`itemGenerations` generation (so `rememberPaymentElement` hands you a fresh element), applied gift
cards are dropped (they were tokenized against the old session), and the amount is the new one.

Observe `checkout.paymentSession` for the swap itself — it's the right trigger to redraw the amount
header and the method list — and `checkout.itemGenerations` if you remember anything per method
outside `rememberPaymentElement`. Same session id is a no-op; a call while a payment is being
submitted throws `IllegalStateException`.
