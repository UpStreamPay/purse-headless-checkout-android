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
            SessionState.SUBMITTED  -> Unit                   // spent — see recipe 11
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
and a primary whose minimum is no longer reachable dims itself (recipe 8).

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
            // result.globalStatus, result.raw — but for redirect flows prefer recipe 11.
        } catch (e: PurseCheckoutException) {
            handle(e.code) // typed — see recipe 13
        }
    }
}
```

> Interactive methods (card, Google Pay, PayPal, Klarna, redirection) are normally submitted
> **through their coordinator** (recipes 4–7), which wraps `submitPayment()` with the
> vendor-specific steps. Call `submitPayment()` yourself only for flows you drive end to end.

---

## 4. Card payment (Compose + SecureFields)

The card form is Compose-first. `rememberCardCoordinator` owns the SecureFields instance;
`VaultForm` renders it. PAN/expiry/CVC validation and brand detection happen **inside**
SecureFields — you only observe validity:

```kotlin
@Composable
fun CardPayment(checkout: PurseHeadlessCheckout, method: PrimaryMethod) {
    // remember(method.id) is load-bearing: a fresh host each recomposition would rebuild
    // the form and lose whatever the shopper typed.
    val host = remember(method.id) { checkout.cardHost(method) }
    val coordinator = rememberCardCoordinator(host, listener = object : CardCoordinator.Listener {
        override fun onReady(ready: Boolean) { /* form validity — gate your Pay button */ }
        override fun onResult(summary: String, globalStatus: String?) {
            // globalStatus == null after a 3-D Secure return → confirm via recipe 11.
        }
        override fun onRequiresAction() { /* 3-D Secure launching (informational) */ }
        override fun onCanceled() { }
        override fun onError(message: String?) { }
    })

    Column {
        VaultForm(coordinator.sdk, Modifier.fillMaxWidth().height(220.dp))
        Button(
            onClick = { coordinator.submit() },   // tokenize → /init → /validate → follow 3DS
            enabled = coordinator.formValid,
        ) { Text("Pay") }
    }
}
```

Mounting the form arms the fulfillment gate: an **empty card form never reads as payable**
(`isPaymentFulfilled` stays `false` until every field validates).

Co-badged cards (e.g. CB + Visa) default to SecureFields' detected brand; a custom brand picker can
call `coordinator.sdk.setBrandSelection(...)`.

---

## 5. Google Pay

Imperative coordinator — **construct it in `onCreate`** (it registers ActivityResult launchers):

```kotlin
class CheckoutActivity : ComponentActivity() {

    private val googlePay = GooglePayCoordinator(this, object : GooglePayCoordinator.Listener {
        override fun onReady(ready: Boolean) { showGooglePayButton(ready) } // isReadyToPay result
        override fun onResult(summary: String, globalStatus: String?) { /* recipe 11 if null */ }
        override fun onRequiresAction() { }
        override fun onCanceled() { }
        override fun onError(message: String?) { }
    })

    fun onSessionReady(checkout: PurseHeadlessCheckout) {
        val item = checkout.primaryMethods.first { it.method == Method.GooglePay }
        checkout.setPrimary(item)
        googlePay.prepare(checkout.googlePayHost(item))   // /init + isReadyToPay
    }

    fun onGooglePayButtonTapped() {
        googlePay.launchSheet()   // sheet → token → /validate → 3DS if any — all reported on the listener
    }
}
```

---

## 6. PayPal (wallet, pay-now)

Same shape; `prepare` is cheap (no `/init` — PayPal's order is only created on the button tap, so
an abandoned selection never leaves an orphan order):

```kotlin
private val paypal = PaypalCoordinator(this, paypalListener)

fun onSessionReady(checkout: PurseHeadlessCheckout) {
    val item = checkout.primaryMethods.first { it.partner == Partner.Paypal }
    checkout.setPrimary(item)
    paypal.prepare(checkout.paypalHost(item))
}

fun onPaypalButtonTapped() = paypal.submit()  // /init → PayPal app/browser approval → /validate
```

---

## 7. Klarna (installments)

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

```kotlin
private val klarna = KlarnaCoordinator(this, klarnaContainer /* a ViewGroup */, klarnaListener)

fun onKlarnaSelected(checkout: PurseHeadlessCheckout, item: PrimaryMethod) {
    checkout.setPrimary(item)
    klarna.prepare(checkout.klarnaHost(item, "$packageName.klarna://return")) // /init + widget load
}

fun onPayTapped() = klarna.submit()  // /validate first, then Klarna authorize() (web-parity order)
```

---

## 8. Redirection methods (generic template)

One coordinator serves **every** method the session labels `template: "redirection"` (or
`integration_mode: "redirection"`) — Fintecture, Bizum, Oney, Sequra… — without naming any of them:

```kotlin
private val redirection = RedirectionCoordinator(this, redirectionListener)

fun onRedirectionMethodSelected(checkout: PurseHeadlessCheckout, item: PrimaryMethod) {
    checkout.setPrimary(item)
    // Only on selection — /init costs a network round-trip and a session step.
    redirection.prepare(checkout.redirectionHost(item))
}

fun onPayTapped() = redirection.submit()  // /validate → PSP page in the hardened in-app WebView
```

The shopper returning from the PSP page reports `onResult(..., globalStatus = null)`: that means
"they came back", **not** "it worked" — settle it with recipe 11.

---

## 9. Gift cards & co-payment (secondary methods)

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

---

## 10. Saved cards (wallet)

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
                    noCvv = token.paysWithoutCvv,   // pay_token_no_cvv / network-token gates
                )
            },
        )
    }
}
```

**Pay with a saved card** — the token becomes the split's primary:

```kotlin
checkout.setPrimaryToken(token)
lifecycleScope.launch { withContext(Dispatchers.IO) { checkout.submitPayment() } }
```

> `securefields-android` 1.0.0 has no CVV-re-entry-against-token mode, so saved-token payments
> currently ride the no-CVV gates (`token.paysWithoutCvv`). Check that flag before showing the
> "pay with this card" affordance.

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

## 11. Confirm the payment after a redirect

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

## 12. Events & monitoring

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

## 13. Hooks

Run merchant logic around `/validate` — e.g. persist the split server-side before committing:

```kotlin
val checkout = PurseHeadlessCheckout.init(
    widgetData,
    hooks = PurseCheckoutHooks(
        onBeforeValidate = { splits ->
            // Throwing here ABORTS the submit (the exception propagates out of submitPayment).
            backend.reserveStock(splits.map { it.source.id to it.amount })
        },
        onAfterValidate = {
            // Runs after /validate answers, before any redirection. Errors are swallowed.
            backend.notifyValidateDone()
        },
    ),
)
```

Express flows a plugin auto-submits (a wallet sheet approval) skip `onBeforeValidate`, exactly like
web's `xpay` trigger.

---

## 14. Error handling

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

## 15. Customization — locale, texts, theme

```kotlin
val checkout = PurseHeadlessCheckout.init(
    widgetData,
    options = PluginOptions(
        locale = "fr-FR",   // browser-data locale; card-form copy localizes via resources
        cardFormTexts = CardFormTexts(
            cardNumberPlaceholder = "N° de carte",     // overrides win over values-fr/ resources
            cvvPlaceholder = "CVV / CVC",
        ),
        theme = PurseTheme(
            input = PurseTheme.FieldStyle(
                color = "#1A1A1A",
                placeholderColor = "#9AA0A6",
                backgroundColor = "#FFFFFF",
                fontSize = "16px",
            ),
            inputFocus = PurseTheme.FieldStyle(color = "#0B57D0"),
            inputInvalid = PurseTheme.FieldStyle(color = "#B3261E"),
        ),
    ),
)
```

Without overrides the card form falls back to the SDK's localized resources (`values/`,
`values-fr/`, …), following the device locale.

---

## 16. Lifecycle & teardown

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

## Where to go deeper

- [`docs/architecture/headless-checkout.md`](architecture/headless-checkout.md) — module map, the
  purse-payment concept, web-parity notes, what's deliberately deferred.
- [`docs/plugins/`](plugins/) — per-plugin internals (Google Pay gateway configs, PayPal order
  lifecycle, Klarna widget states, redirection resolution priority).
- The `:app` demo module — a working playground driving every recipe above against a sandbox
  session minted through alfred.
