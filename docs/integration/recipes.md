# Recipes

One task per section. Start with [getting started](getting-started.md); look up signatures in the
[API reference](consumer-api.md). Snippets assume Compose; in a View-based screen collect flows with
`lifecycleScope.launch { checkout.x.collect { … } }` instead of `collectAsState()`.

Google Pay, PayPal and Klarna live in their own artifacts (`eu.purse:headless-checkout-googlepay`,
`-paypal`, `-klarna`); the recipes for them assume the matching dependency is declared — see
[getting started §2](getting-started.md#2-install).

1. [Session state](#1-session-state)
2. [Method list](#2-method-list)
3. [Card](#3-card)
4. [Google Pay and PayPal](#4-google-pay-and-paypal)
5. [Klarna](#5-klarna)
6. [Redirection methods](#6-redirection-methods)
7. [Gift cards and co-payment](#7-gift-cards-and-co-payment)
8. [Saved cards and saved PayPal accounts](#8-saved-cards-and-saved-paypal-accounts)
9. [Confirm after a redirect: ask your backend](#9-confirm-after-a-redirect-ask-your-backend)
10. [Hooks](#10-hooks)
11. [Events and monitoring](#11-events-and-monitoring)
12. [Errors](#12-errors)
13. [Locale, texts, theme](#13-locale-texts-theme)
14. [BNPL instalment simulation](#14-bnpl-instalment-simulation)
15. [Refresh the session](#15-refresh-the-session)
16. [Survive process death](#16-survive-process-death)
17. [Teardown](#17-teardown)
18. [Below the element: coordinators](#18-below-the-element-coordinators)

---

## 1. Session state

A session can be `/init`-ed many times but `/validate`-d once. The expiry watchdog runs on a
server-corrected clock.

```kotlin
val state by checkout.sessionState.collectAsState()
when (state) {
    SessionState.READY -> Unit
    SessionState.SUBMITTING -> Spinner()
    SessionState.SUBMITTED -> Unit                      // spent, see recipe 9
    SessionState.ERROR -> Retry()                       // transient, session still usable
    SessionState.EXPIRED, SessionState.TERMINATED -> NewSession()   // recipe 15
}
```

## 2. Method list

`paymentMethods` already excludes suspended methods and those unreachable for the amount; the
remaining `disabled` states recompute as the split moves.

```kotlin
val items by checkout.paymentMethods.collectAsState()
items.forEach { item ->
    MethodRow(
        id = item.id,                               // "purse-creditcard", "paypal-wallet", …
        icon = checkout.iconUrl(item),              // CDN SVG
        enabled = item.disabled == null,
        reason = item.disabled?.code?.name,         // e.g. MIN_AMOUNT_NOT_MET
        isSecondary = item.isSecondary,
    )
}
val schemes = checkout.schemeAssets(cardMethod)     // accepted card schemes, icon + label
```

Google Pay and PayPal are self-submitting: render their element always (it shows itself once ready)
and never call `setPrimary` for them.

## 3. Card

```kotlin
val element = rememberPaymentElement(checkout, cardMethod, listener)
LaunchedEffect(cardMethod.id) { checkout.setPrimary(cardMethod) }
PaymentElementUi(element, Modifier.fillMaxWidth())          // SecureFields form: PAN, expiry, CVV, holder
Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
```

An empty or invalid form is never payable: `isPaymentFulfilled` stays `false` until every field
validates. `submit()` tokenizes, then `/init` + `/validate`, then follows 3-D Secure.

**Save the card** when the method allows it:

```kotlin
if (cardMethod.canBeRegistered) {                           // save_token_mode PREFER / PREFER_NO
    checkout.register(cardMethod, registered = true, name = "holiday card")
    // throws WalletOperationFailed(INVALID_TOKEN_NAME / TOKEN_NAME_EXISTS)
}
```

## 4. Google Pay and PayPal

Requires `eu.purse:headless-checkout-googlepay` and/or `eu.purse:headless-checkout-paypal`.

```kotlin
val element = rememberPaymentElement(checkout, method, listener)
PaymentElementUi(element, Modifier.fillMaxWidth().height(56.dp))   // the branded button is the submit
```

The approval selects the method as primary and runs `/validate`. A cancelled sheet leaves the split
untouched and reports `PaymentOutcome.Canceled`. PayPal's `/init` runs on the tap, so an unused button never
creates an orphan order. No manifest work for either.

## 5. Klarna

Requires `eu.purse:headless-checkout-klarna` and Klarna's Maven repository
(`maven("https://x.klarnacdn.net/mobile-sdk/")`). Declare the deep link on the Activity hosting the
widget (see getting started §2), then:

```kotlin
val element = rememberPaymentElement(checkout, klarnaMethod, listener)   // klarnaReturnUrl defaults to "{packageName}.klarna://return"
LaunchedEffect(klarnaMethod.id) { checkout.setPrimary(klarnaMethod) }
PaymentElementUi(element, Modifier.fillMaxWidth())          // the widget; /init runs once it is placed
Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay with Klarna") }
```

`submit()` runs `/validate` first, then Klarna's own `authorize()`. A declined authorization is
reported as `PaymentOutcome.Canceled`.

## 6. Redirection methods

Any method the session marks `template = "redirection"` or `integration_mode = "redirection"`
(Fintecture, Bizum, Oney, Sequra, …). Nothing to render; the PSP page is the payment step.

```kotlin
val element = rememberPaymentElement(checkout, method, listener)   // runs /init: create on selection only
LaunchedEffect(method.id) { checkout.setPrimary(method) }
Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
```

To know whether a method is one of these, ask the registry rather than reading the template:

```kotlin
PluginRegistry.getPlugin(method.partner, method.method, checkout.session.source) is RedirectionPlugin
```

## 7. Gift cards and co-payment

```kotlin
val giftCard = checkout.secondaryMethods.single { it.partner == Partner.Illicado }
val rules = checkout.secondaryConfig(giftCard)                 // requiresCvv(pan), validatePan, validateCvv

val token = withContext(Dispatchers.IO) { checkout.getSecondaryToken(giftCard, pan, cvv) }   // /init, split untouched
checkout.applySecondary(token)                                  // as much as the order needs…
checkout.applySecondary(token, BigDecimal("2.00"))              // …or a capped slice (splittable cards only)
checkout.removeSecondary(token)
```

`remainingAmountToPay` and `amountSplit` follow every change. Gift cards covering everything make
the payment fulfilled with no primary.

**Keep the gift card** for next time, when the method lets the shopper choose:

```kotlin
if (token.canBeRegistered) checkout.registerSecondary(token, registered = true, name = "gift one")
```

**Saved gift cards** arrive on `savedSecondaryTokens`. Their balance is fetched in the background:

```kotlin
val saved by checkout.savedSecondaryTokens.collectAsState()
saved.forEach { card ->
    SavedGiftCardRow(
        balance = card.balance.takeIf { card.balanceResolved },   // null → "checking…"
        unavailable = card.disabled != null,
        onUse = { checkout.applySecondary(card) },
        onRemove = { checkout.removeSecondary(card) },
    )
}
```

**An issuer the SDK does not ship**: implement `SecondaryConfig` and register it once at app start.

```kotlin
object AcmeVoucherConfig : SecondaryConfig {
    override val takePolicy = SecondaryTakePolicy.MAX
    override fun requiresCvv(pan: String) = false
    override fun validatePan(pan: String) = pan.startsWith("ACME-")
    override fun buildInitPaymentData(pan: String, cvv: String?) = JSONObject().put("voucher_code", pan)
    override fun buildValidatePaymentData(token: SecondaryToken) = JSONObject().put("voucher_code", token.pan)
}
SecondaryConfigs.register(Partner.Other("acme"), Method.Other("voucher"), AcmeVoucherConfig)
```

## 8. Saved cards and saved PayPal accounts

`paymentTokens` carries the session's `integrated_tokens`. `isVaultedCard` tells a card-on-file from
a PayPal account; `requiresCvv` tells whether the PSP still wants the CVV.

```kotlin
val element = rememberPaymentElement(checkout, token, listener)
LaunchedEffect(token.id) { checkout.setPrimaryToken(token) }
if (element.hasUi) PaymentElementUi(element, Modifier.fillMaxWidth())   // CVV-only field, when required
Button(onClick = { element.submit() }, enabled = element.ready) { Text("Pay") }
```

A token that requires a CVV gates `isPaymentFulfilled` like a fresh card. A token without one, and
a saved PayPal account, are payable at selection with nothing to render.

**Manage the wallet** (needs a `wallet_session` in the widget data; all suspend):

```kotlin
checkout.editToken(token, name = "work card")
checkout.setTokenFavorite(token)
checkout.deleteToken(token)
checkout.deleteAllTokens()
// without a wallet session: WalletOperationFailed(MISSING_WALLET_SESSION)
```

## 9. Confirm after a redirect: ask your backend

`PaymentOutcome.ReturnedFromRedirect` means the shopper came back, nothing more: a 3-D Secure or PSP
page's final redirect is built server-side and the app cannot read it. The verdict lives in the
session's transactions, and only **your backend** can read them — with its own merchant credentials.
The Payment API answers `401` to the client key the app holds. Web works the same way: it concludes
on `/validate`, the redirection return and the success/failure URLs, and the merchant confirms on its
server.

```kotlin
override fun onOutcome(outcome: PaymentOutcome) {
    when (outcome) {
        is PaymentOutcome.Completed -> render(outcome.status)                // SUCCESS or a decline
        PaymentOutcome.ReturnedFromRedirect -> scope.launch {
            showConfirming()
            when (myBackend.paymentVerdict(orderId)) {                      // your own endpoint
                Verdict.PAID -> showSuccess()
                Verdict.REFUSED -> showFailure()
                Verdict.PENDING -> showStillProcessing()                     // retry a few times, then let the order page settle it
            }
        }
        PaymentOutcome.Canceled -> Unit
        is PaymentOutcome.Failed -> showError(outcome.error)
    }
}
```

Your backend reads the session (or the PSP notification it already receives) and answers. The
session's rows are operations, not payments: one captured payment is an `AUTHORIZE` and a `CAPTURE`
row (see [plugins/README.md](../plugins/README.md#reading-the-sessions-transactions--operations-not-payments)).
If your backend relays those rows as they are, `PaymentSessionStatus.from(rows)` classifies them on
the device.

The same call covers a submit that was interrupted: a `ValidateFailed` whose `outcomeUnknown` is
`true` (the request left, nothing answered) means the payment may exist — ask your backend before
showing an error. A return from the redirect WebView after the app was killed lands here too, never
on `Canceled`.

`checkout.sessionTransactions()`, `paymentStatus()` and `awaitPaymentOutcome()` read that same route
with the client key, so they cannot confirm anything: they are **deprecated** and go at the next
major. Don't poll them. The demo app shows the flow above: its result screen asks the alfred backend
for the session's transactions.

## 10. Hooks

```kotlin
PurseHeadlessCheckout.init(
    widgetData,
    hooks = PurseCheckoutHooks(
        onBeforeValidate = { splits -> backend.reserveStock(splits) },   // throwing aborts the submit, nothing sent
        onAfterValidate = { backend.notifyValidateDone() },              // errors swallowed, reported to monitoring
        onRedirection = { action ->                                      // true = you open it, false = SDK WebView
            when (action) {
                is RedirectAction.Get -> { openInCustomTab(action.url); true }
                else -> false
            }
        },
    ),
)
```

Self-submitting flows (a wallet approval) skip `onBeforeValidate`. When you take a redirect over,
confirming the outcome on return is yours (recipe 9).

## 11. Events and monitoring

```kotlin
LaunchedEffect(checkout) {
    checkout.pluginEvents.collect { (itemId, event) ->
        when (event) {
            is PluginEvent.InitFailed -> dim(itemId)
            is PluginEvent.PartnerError -> if (event.recoverable) banner(itemId)
            PluginEvent.Redirect -> leavingAppHint()
            else -> Unit
        }
    }
}
val unsubscribe = CheckoutEventBus.getInstance().subscribe { event -> analytics.track(event.code.name) }
```

Every event also reaches Purse's monitoring API automatically, batched and flushed when the app
goes to background. What is sent — and what never is — is in [Monitoring and analytics](monitoring.md).

## 12. Errors

Every SDK exception carries a `code` shared with web.

```kotlin
try {
    withContext(Dispatchers.IO) { checkout.submitPayment() }
} catch (e: PurseCheckoutException) {
    when (e.code) {
        PurseErrorCode.PAYMENT_NOT_FULFILLED_ON_SUBMIT -> highlightMissingAmount()
        PurseErrorCode.ELEMENT_NOT_FULFILLED -> focusInvalidForm()
        else -> showError(e.code)
    }
} catch (e: PaymentApiException) {                     // transport / HTTP
    if (e.isSessionTerminal) newSession() else retry()
}
```

Transient `/init` failures are retried by the SDK; do not add your own retry loop.

**A method shows up but cannot pay.** If the session offers Google Pay, PayPal or Klarna and the
matching artifact is not on your classpath, the method is still listed (the session decides that),
but its payment element has no UI and `submit()` reports a `PaymentOutcome.Failed(NoPluginRegistered)`
whose message reads:

```
no plugin registered for klarna/installments — add the `eu.purse:headless-checkout-klarna` dependency to offer this method
```

The same line is logged at `PurseHeadlessCheckout.init` (tag `PurseHeadlessCheckout`, level W), and
`submitPayment` throws `PurseCheckoutException.NoPluginRegistered`. Add the dependency, or hide the
method in this build with `init(disabledMethods = setOf(MethodKey(partner, method)))`.

## 13. Locale, texts, theme

```kotlin
PurseHeadlessCheckout.init(
    widgetData,
    options = PluginOptions(
        locale = "fr-FR",
        cardFormTexts = CardFormTexts(
            cardNumber = CardFormTexts.FieldTexts(label = "N° de carte", formatError = "Ce numéro est invalide."),
            cvv = CardFormTexts.FieldTexts(label = "CVV", helperText = "3 chiffres au dos de la carte."),
        ),
        theme = PurseTheme(
            global = PurseTheme.Text(color = "#1A1A1A", fontFamily = "sans-serif", fontSize = "14px"),
            globalInvalid = PurseTheme.Text(color = "#B3261E"),
            input = PurseTheme.FieldStyle(placeholderColor = "#9AA0A6", backgroundColor = "#FFFFFF", fontSize = "16px"),
            inputFocus = PurseTheme.FieldStyle(color = "#0B57D0"),
            label = PurseTheme.Text(fontWeight = "600"),
            helperText = PurseTheme.Text(fontSize = "12px", color = "#5F6368"),
            gap = "12px",
        ),
    ),
)
```

Scopes: `global`, `input`, `label`, `helperText`, each with `Invalid` / `Valid` variants and `inputFocus`.
Resolution is scope variant → scope → global variant → global, over web's defaults. Every `null`
text falls back to the SDK's localized resources (English, French).

## 14. BNPL instalment simulation

```kotlin
val bnpl = checkout.primaryMethods.firstOrNull { it.simulable } ?: return
val schedule = checkout.simulateLoan(bnpl, LoanProduct(checkout.remainingAmountToPay.value, checkout.currency))
// schedule.installmentCount, initialInstallmentAmount, installmentAmount, finalInstallmentAmount,
// totalCost (price of the credit), totalAmount (everything paid), aprc, legalText
val all = checkout.simulateLoans(bnpl, product)          // every schedule the partner offers (3×, 4×, …)
```

`installmentCount` counts the instalments after the first one, so the shopper pays
`installmentCount + 1` times (a 3× schedule has a count of 2). `LoanSimulationFailed` means the
partner refused the amount; `MethodNotSimulable` cannot happen behind the `simulable` flag. The
demo's `SimulationPreview.kt` is a complete rendering.

## 15. Refresh the session

The cart changed, or the session expired: mint a new Client Session and hand it over.

```kotlin
checkout.setSession(newWidgetData)      // suspend; SessionExpired if the new one is already expired
```

A primary whose configuration is unchanged keeps its selection, its element and what the shopper
typed. Everything else rebuilds; applied gift cards are dropped. Observe `paymentSession` for the
swap and `itemGenerations` if you remember anything per method outside `rememberPaymentElement`.

## 16. Survive process death

Android may kill the app while the shopper is in a bank app, PayPal or a 3-D Secure page. Snapshot
the checkout into your saved instance state and rebuild it from there; the checkout itself belongs
in a `ViewModel` so a rotation never touches it.

```kotlin
class CheckoutViewModel(private val handle: SavedStateHandle) : ViewModel() {
    val checkout: PurseHeadlessCheckout =
        handle.get<CheckoutSavedState>(KEY)?.let { PurseHeadlessCheckout.restore(it, hooks, options) }
            ?: PurseHeadlessCheckout.init(widgetData, lockedEnvironment = PurseEnvironment.PRODUCTION, hooks, options)

    init {
        handle.setSavedStateProvider(KEY) { bundleOf(KEY to checkout.saveState()) }   // Parcelable
    }
}
```

What comes back: the session, the primary selection, the applied gift cards and their slices, the
save decisions, and what the native steps collected. What does not: a card form's input (never
saved, by design) and `hooks` / `options` (they hold code — pass the same ones to `restore`).

If a submit had already left the device, the restored `sessionState` is `SUBMITTED` and
`submitPayment` refuses: confirm the outcome from your backend (recipe 9) instead of
letting the shopper pay twice. The return from the redirect Activity after the process was killed
lands on the recreated payment element's listener as `PaymentOutcome.ReturnedFromRedirect`, which
leads to the same check.

`CheckoutSavedState` is also `toJson()` / `fromJson()` for a store of your own; a snapshot from a
newer SDK version is refused with `IllegalArgumentException`.

## 17. Teardown

```kotlin
override fun onDestroy() {
    super.onDestroy()
    if (isFinishing) checkout.teardown()     // not on configuration changes
}
```

`teardown()` releases the watchdog, the background work and every live partner client. Afterwards
`isTornDown` is `true` and any operation throws `CheckoutTornDown` (`CHECKOUT_TORN_DOWN`); a second
`teardown()` is a no-op.

## 18. Below the element: coordinators

`rememberPaymentElement` wraps one coordinator per method. Reach for them only to customise a button
or drive a native step from your own control:

```kotlin
import eu.purse.partnerui.plugins.vaults.purse.creditcard.rememberCardCoordinator      // in headless-checkout-android
import eu.purse.partnerui.rememberRedirectionCoordinator                               // in headless-checkout-android
import eu.purse.partnerui.plugins.generics.googlepay.rememberGooglePayCoordinator      // in headless-checkout-googlepay
import eu.purse.partnerui.plugins.partners.paypal.wallet.rememberPaypalCoordinator     // in headless-checkout-paypal
import eu.purse.partnerui.plugins.partners.klarna.rememberKlarnaCoordinator            // in headless-checkout-klarna

val host = remember(method.id) { checkout.host(method) }             // remember it: the card form is keyed on it
val card = rememberCardCoordinator(host, listener)                    // PurseCardForm(card) + card.submit()
val googlePay = rememberGooglePayCoordinator(listener)                // prepare(host); GooglePayButton(googlePay, theme = …)
val paypal = rememberPaypalCoordinator(listener)                      // prepare(host); paypal.submit()
val klarna = rememberKlarnaCoordinator(listener)                      // container = …; prepare(host, returnUrl); submit()
val redirection = rememberRedirectionCoordinator(listener)            // prepare(host); submit()
```

Each partner's `remember*Coordinator` ships with its artifact, in the plugin's own package.

Per-method internals (`/init` shapes, sequence diagrams, failure modes): [docs/plugins](../plugins/README.md).
