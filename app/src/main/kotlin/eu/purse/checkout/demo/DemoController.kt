package eu.purse.checkout.demo

import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SavedSecondaryToken
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.core.payment.Method
import eu.purse.core.vault.VaultVendor
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayCoordinator
import eu.purse.partnerui.plugins.generics.redirection.RedirectionCoordinator
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import eu.purse.partnerui.plugins.partners.klarna.KlarnaCoordinator
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalCoordinator
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardCoordinator
import eu.purse.partnerui.redirect.RedirectionResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64

/** The three screens this demo walks through, in order. */
enum class DemoStep { Session, Pay, Result }

/**
 * All of the demo's state and logic in one place, so the screens ([SessionScreen], [PayScreen],
 * [ResultScreen]) stay pure UI. Everything mutable here is Compose state, so the screens recompose
 * as coordinator callbacks land.
 *
 * **Construct this from the host Activity's `onCreate`** — it builds [GooglePayCoordinator] and
 * [KlarnaCoordinator], which register `ActivityResult` launchers and so must be created before the
 * Activity is STARTED (see their own docs). Deliberately not a `ViewModel`: like the rest of this
 * demo it's bare on purpose, and Klarna's widget is bound to this Activity instance anyway.
 *
 * Google Pay is treated differently from the other primaries throughout, and that isn't an
 * inconsistency: it's an **xpay** — its branded button appears as soon as `isReadyToPay` resolves and
 * is itself the submit action, mirroring `usp-widget`'s own `googlepay` plugin. Klarna and the card
 * have no such one-tap shape, so they're rows you select, and one shared "Pay" button submits
 * whichever is selected. See [googlePayBusy] and [submitEnabled].
 */
class DemoController(private val activity: ComponentActivity) {

    val alfredBaseUrl: String = AlfredClient.baseUrlFromBuildConfig()
    private val alfred = AlfredClient(alfredBaseUrl)
    private val scope = activity.lifecycleScope

    // --- navigation -------------------------------------------------------------------------------

    var step by mutableStateOf(DemoStep.Session)
        private set

    // --- 1 · session ------------------------------------------------------------------------------

    /** The base64 `widget.data` blob — filled by [createSession], or pasted by hand. */
    var widgetData by mutableStateOf("")

    var creatingSession by mutableStateOf(false)
        private set

    /** Shown as an error card on screen 1; `null` when there's nothing wrong. */
    var sessionError by mutableStateOf<String?>(null)
        private set

    var checkout by mutableStateOf<PurseHeadlessCheckout?>(null)
        private set

    /** The SDK's decoded state, dumped for the "Session details" expander — the smoke test's proof. */
    var sessionDetails by mutableStateOf("")
        private set

    // The orchestration session's own top-level `id` (what POST /orchestration_session returns —
    // distinct from `widget.data`'s `payment_session.id`), kept only as a fallback default for the
    // transactions lookup. See [resolveAlfredSessionId].
    private var lastOrchestrationSessionId: String? = null

    // --- 2 · pay ----------------------------------------------------------------------------------

    /** `isReadyToPay` resolved true — show the branded Google Pay button. */
    var googlePayReady by mutableStateOf(false)
        private set

    /** Google Pay's own primary item, when the session offers it. */
    private var googlePayItem: PrimaryMethod? = null

    /**
     * `true` from the tap on the Google Pay button until its flow ends. Two jobs: it disables that
     * button so a double tap can't fire two sheets (a second `/validate` for the same session comes
     * back `409`), and it keeps the shared "Pay" button switched off — once the sheet is approved
     * the SDK commits Google Pay as the primary itself (web's `validationRequested`), which
     * fulfills the split and would otherwise be indistinguishable from "secondaries alone cover
     * it, no primary needed" and light that button up mid-flow.
     */
    var googlePayBusy by mutableStateOf(false)
        private set

    /** The session offers PayPal wallet — show the branded PayPal button. */
    var paypalReady by mutableStateOf(false)
        private set

    /** PayPal's own primary item, when the session offers it. */
    private var paypalItem: PrimaryMethod? = null

    /**
     * `true` from the tap on the PayPal button until its flow ends — the same two jobs
     * [googlePayBusy] does. PayPal's window is the wider of the two: it spans a `/init`, a trip out
     * to PayPal's approval and back, and then `/validate`, so a second tap would also leave an
     * orphan PayPal order behind, not just a duplicate `/validate`.
     */
    var paypalBusy by mutableStateOf(false)
        private set

    /** The selected row from [payableMethods] (`null` = none, or secondaries alone). */
    var selectedPrimary by mutableStateOf<PrimaryMethod?>(null)
        private set

    /**
     * The selected saved card (`paymentTokens`), mutually exclusive with [selectedPrimary] — both
     * occupy the split's single primary slot. Its row mounts [SavedCardForm]: a CVV field when the
     * PSP still wants one (`token.requiresCvv`), nothing otherwise.
     */
    var selectedToken by mutableStateOf<PaymentToken?>(null)
        private set

    /** Whether the selected primary can actually be submitted (Klarna's widget loaded, the card form
     * validates). Reset to `false` the instant a different row is selected. */
    var primaryReady by mutableStateOf(false)
        private set

    /** A submit is in flight — drives the blocking overlay and guards re-entry. */
    var submitting by mutableStateOf(false)
        private set

    /** A redirection row's `/init` came back — shown as "ready" text in place of a form. */
    var redirectionPrepared by mutableStateOf(false)
        private set

    /** The card form's live `submit`, reported up by [CardPaymentForm] once its coordinator exists. */
    private var cardSubmit: (() -> Unit)? = null

    private val giftCards = mutableMapOf<String, GiftCardFormState>()

    // --- 3 · result -------------------------------------------------------------------------------

    var outcome by mutableStateOf<PaymentOutcome?>(null)
        private set

    /** Prefilled by [initCheckout], editable — the id the transactions lookup uses. */
    var alfredSessionId by mutableStateOf("")

    /** The raw transactions JSON, for the expander. */
    var transactions by mutableStateOf<String?>(null)
        private set

    /** One readable line per transaction — `"AUTHORIZE → SUCCESS"` — from the same response. */
    var transactionStatuses by mutableStateOf<List<String>>(emptyList())
        private set

    var loadingTransactions by mutableStateOf(false)
        private set

    /**
     * Prefilled from whichever coordinator reported a `payment_id` (all three now do) — the whole
     * point of surfacing it, since a capture is unusable without one.
     */
    var capturePaymentId by mutableStateOf("")

    /** Prefilled with the session's own amount, editable for a partial capture. */
    var captureAmount by mutableStateOf("")

    var captureResult by mutableStateOf<String?>(null)
        private set
    var capturing by mutableStateOf(false)
        private set

    // --- shared -----------------------------------------------------------------------------------

    /** Every coordinator callback, in order — shown behind the "Event log" expander. */
    val events = mutableStateListOf<String>()

    /** Set to show a snackbar; [DemoApp] clears it once shown. */
    var snackbar by mutableStateOf<String?>(null)

    // Listeners are declared before the coordinators that take them — Kotlin initializes properties
    // in declaration order, so the other way round would hand each coordinator a null listener.

    private val googlePayListener = object : GooglePayCoordinator.Listener {
        override fun onReady(ready: Boolean) {
            googlePayReady = ready
        }

        override fun onResult(summary: String, globalStatus: String?) {
            googlePayBusy = false
            succeed(GOOGLE_PAY, summary, globalStatus)
        }

        override fun onRequiresAction() = announceAction(GOOGLE_PAY)
        override fun onPaymentId(paymentId: String) = notePaymentId(GOOGLE_PAY, paymentId)

        override fun onCanceled() {
            googlePayBusy = false
            cancel(GOOGLE_PAY)
        }

        override fun onError(message: String?) {
            val wasInFlight = googlePayBusy
            googlePayBusy = false
            fail(GOOGLE_PAY, message, wasInFlight)
        }
    }

    /**
     * PayPal's callbacks. Like Google Pay it's an xpay with its own branded button, so it tracks its
     * own busy flag rather than [primaryReady]. `onRequiresAction` fires twice in the normal flow —
     * once for PayPal's own approval, once more only if `/validate` chains a 3-D Secure step.
     */
    private val paypalListener = object : PaypalCoordinator.Listener {
        override fun onReady(ready: Boolean) {
            paypalReady = ready
        }

        override fun onResult(summary: String, globalStatus: String?) {
            paypalBusy = false
            succeed(PAYPAL, summary, globalStatus)
        }

        override fun onRequiresAction() = announceAction(PAYPAL)
        override fun onPaymentId(paymentId: String) = notePaymentId(PAYPAL, paymentId)

        override fun onCanceled() {
            paypalBusy = false
            cancel(PAYPAL)
        }

        override fun onError(message: String?) {
            val wasInFlight = paypalBusy
            paypalBusy = false
            fail(PAYPAL, message, wasInFlight)
        }
    }

    private val klarnaListener = object : KlarnaCoordinator.Listener {
        override fun onReady(ready: Boolean) {
            primaryReady = ready
        }

        override fun onResult(summary: String, globalStatus: String?) =
            succeed(klarnaLabel, summary, globalStatus)

        override fun onRequiresAction() = announceAction(klarnaLabel)
        override fun onPaymentId(paymentId: String) = notePaymentId(klarnaLabel, paymentId)
        override fun onCanceled() = cancel(klarnaLabel)
        override fun onError(message: String?) = fail(klarnaLabel, message, submitting)
    }

    /**
     * The card is Compose-first, so unlike the two above its coordinator is created inside the
     * composition ([CardPaymentForm]) — its callbacks still land here, next to theirs. `onReady`
     * carries the SecureFields form's validity, which is exactly this method's "ready to submit".
     *
     * For the card that's belt *and* braces: the coordinator also reports the same validity into the
     * SDK, so `isPaymentFulfilled` — and therefore [submitEnabled] — is already `false` while the form
     * is incomplete. Klarna has no such gate, which is why [primaryReady] still exists.
     */
    val cardListener = object : CardCoordinator.Listener {
        override fun onReady(ready: Boolean) {
            primaryReady = ready
        }

        override fun onResult(summary: String, globalStatus: String?) =
            succeed(cardLabel, summary, globalStatus)

        override fun onRequiresAction() = announceAction(cardLabel)
        override fun onPaymentId(paymentId: String) = notePaymentId(cardLabel, paymentId)
        override fun onCanceled() = cancel(cardLabel)
        override fun onError(message: String?) = fail(cardLabel, message, submitting)
    }

    /**
     * The generic redirection template's callbacks. `onReady` fires as soon as its `/init` returns —
     * there is no form and no widget to wait for, which is the whole character of this template.
     */
    private val redirectionListener = object : RedirectionCoordinator.Listener {
        override fun onReady(ready: Boolean) {
            primaryReady = ready
            redirectionPrepared = ready
        }

        override fun onResult(summary: String, globalStatus: String?) =
            succeed(redirectionLabel, summary, globalStatus)

        override fun onRequiresAction() = announceAction(redirectionLabel)
        override fun onPaymentId(paymentId: String) = notePaymentId(redirectionLabel, paymentId)
        override fun onCanceled() = cancel(redirectionLabel)
        override fun onError(message: String?) = fail(redirectionLabel, message, submitting)
    }

    /**
     * Klarna's rendering container. The coordinator captures this exact instance at construction time
     * (its own "construct in onCreate" contract), so unlike the Google Pay button or the card's
     * Compose form — both created per mount — this one is built once here and hosted by whichever row
     * is currently selected, via `AndroidView`.
     */
    val klarnaContainer = FrameLayout(activity)

    private val googlePay = GooglePayCoordinator(activity, googlePayListener)
    private val paypal = PaypalCoordinator(activity, paypalListener)
    private val klarna = KlarnaCoordinator(activity, klarnaContainer, klarnaListener)
    private val redirection = RedirectionCoordinator(activity, redirectionListener)

    // --- 1 · session ------------------------------------------------------------------------------

    /**
     * One tap for the whole of screen 1: mint a session through alfred **and** hand its `widget.data`
     * straight to [PurseHeadlessCheckout.init]. Splitting those was the old demo's own step, and it
     * bought nothing — a session that can't be initialized is just an error either way.
     */
    fun createSession() {
        creatingSession = true
        sessionError = null
        scope.launch {
            try {
                val session = alfred.createSession()
                lastOrchestrationSessionId = session.orchestrationSessionId
                widgetData = session.widgetData
                initCheckout()
            } catch (e: Exception) {
                sessionError = activity.getString(R.string.session_error, e.message)
            } finally {
                creatingSession = false
            }
        }
    }

    /** Initialize the SDK off whatever [widgetData] currently holds (pasted by hand, or just minted). */
    fun initCheckout() {
        val data = widgetData.trim()
        if (data.isEmpty()) return
        resetPayment()
        checkout = null
        sessionDetails = ""
        val co = try {
            PurseHeadlessCheckout.init(data)
        } catch (e: Exception) {
            sessionError = activity.getString(R.string.init_error, e.message)
            return
        }
        sessionError = null
        checkout = co
        sessionDetails = summarize(co)
        alfredSessionId = resolveAlfredSessionId(data)
        captureAmount = co.session.amount.toPlainString()
        // Google Pay is prepared immediately — it's an xpay, never gated behind selecting a row.
        googlePayItem = co.primaryMethods.firstOrNull { it.method == Method.GooglePay }
        googlePayItem?.let { googlePay.prepare(co.googlePayHost(it)) }
        // PayPal is an xpay too. Its prepare does no network work at all — the /init that creates the
        // PayPal order waits for the tap, so merely showing the button never leaves an orphan order.
        // Excluded when the session routes it to the redirection template, which is a row, not an xpay.
        paypalItem = co.primaryMethods.firstOrNull { it.method == Method.Wallet && !isRedirection(it) }
        paypalItem?.let { paypal.prepare(co.paypalHost(it)) }
    }

    fun goToPay() {
        if (checkout != null) step = DemoStep.Pay
    }

    // --- 2 · pay ----------------------------------------------------------------------------------

    /**
     * The primaries this demo can actually drive as a selectable row: Klarna, a Purse-vaulted card,
     * and anything the session routes to the generic redirection template. Google Pay is excluded on
     * purpose — it has its own button (see the class doc).
     *
     * Note the asymmetry, which is the point of a *generic*: Klarna and the card are matched by what
     * they **are** (`Method.Installments`, a `creditcard` with a Purse vault), while a redirection
     * method is matched by what the **session says about it** — see [isRedirection]. This demo names
     * no redirection partner anywhere, so whichever ones the merchant's Purse configuration enables
     * simply show up.
     */
    val payableMethods: List<PrimaryMethod>
        get() = checkout?.primaryMethods?.filter {
            it.method == Method.Installments ||
                (it.method == Method.CreditCard && it.configuration.vault?.vendor == VaultVendor.PURSE) ||
                isRedirection(it)
        }.orEmpty()

    /**
     * Whether [method] resolves to the generic redirection plugin — asked of [PluginRegistry] itself
     * rather than by re-reading `configurations.template` here, so this can't drift from the SDK's own
     * resolution: the registry weighs a vault vendor above the template and honours
     * `merchant_specifics.integration_mode = redirection` as a second route in, and a demo that
     * checked only `template == "redirection"` would disagree with it in both cases.
     */
    fun isRedirection(method: PrimaryMethod): Boolean {
        val session = checkout?.session?.source ?: return false
        return PluginRegistry.getPlugin(method.partner, method.method, session) is RedirectionPlugin
    }

    /** The secondaries with a registered config — the rest can't be tokenized, so no form is offered. */
    val giftCardMethods: List<SecondaryMethod>
        get() = checkout?.let { co -> co.secondaryMethods.filter { co.secondaryConfig(it) != null } }.orEmpty()

    fun giftCardState(method: SecondaryMethod): GiftCardFormState =
        giftCards.getOrPut(method.id) { GiftCardFormState() }

    /**
     * Select [method] (or `null` to collapse everything). This is the one place `setPrimary` is called
     * for a row: choosing a method and committing it as the primary are the same action, and only the
     * selected row mounts its actual payment control — an unselected Klarna/card row does no `/init`
     * or SecureFields work at all.
     */
    fun selectPrimary(method: PrimaryMethod?) {
        val co = checkout ?: return
        selectedPrimary = method
        selectedToken = null
        primaryReady = false
        redirectionPrepared = false
        googlePayBusy = false
        paypalBusy = false
        cardSubmit = null
        co.setPrimary(method)
    }

    /**
     * Select a saved card (or `null` to collapse it) — `setPrimaryToken` is the token analog of
     * `setPrimary`, and the SDK arms the CVV gate itself when the PSP wants one: the shared "Pay"
     * button stays off until the row's CVV field validates, exactly like the full card form.
     */
    fun selectToken(token: PaymentToken?) {
        val co = checkout ?: return
        selectedPrimary = null
        selectedToken = token
        primaryReady = false
        redirectionPrepared = false
        googlePayBusy = false
        paypalBusy = false
        cardSubmit = null
        if (token != null) co.setPrimaryToken(token) else co.clearPrimarySplit()
    }

    /** What a saved-card row shows: the shopper's label, or the SDK's display token. */
    fun label(token: PaymentToken): String = token.name.ifBlank { token.label }

    /** Redirection is checked first, matching [PluginRegistry]'s own priority — a method configured to
     * redirect is driven by the template even when its `method` has a dedicated plugin. */
    fun label(method: PrimaryMethod): String = when {
        isRedirection(method) -> "$redirectionLabel — ${method.partner.wireValue}"
        method.method == Method.Installments -> klarnaLabel
        method.method == Method.CreditCard -> cardLabel
        else -> method.id
    }

    /** Whether this gift card's own config asks for a CVC alongside the PAN. */
    fun requiresCvv(method: SecondaryMethod): Boolean =
        checkout?.secondaryConfig(method)?.requiresCvv("") ?: false

    /** Prepares Klarna's widget for [method] — called when its row is selected, not before. */
    fun prepareKlarna(method: PrimaryMethod) {
        val co = checkout ?: return
        klarna.prepare(co.klarnaHost(method, "${activity.packageName}.klarna://return"))
    }

    /**
     * `/init`s the selected redirection [method] — same "on selection, not before" rule as Klarna, and
     * it matters more here: a redirection `/init` consumes a step against the session, so firing one
     * for every offered row on entering the pay screen would burn several.
     */
    fun prepareRedirection(method: PrimaryMethod) {
        val co = checkout ?: return
        redirection.prepare(co.redirectionHost(method))
    }

    /** The card form's live `submit`, once its coordinator exists (see [CardPaymentForm]). */
    fun onCardSubmitReady(submit: () -> Unit) {
        cardSubmit = submit
    }

    fun onGooglePayClicked() {
        if (checkout == null) return
        // Collapse any open Klarna/card row — Google Pay is taking over as the primary, and leaving a
        // row visually selected would misrepresent what's about to be submitted. Committing Google
        // Pay as the primary is the SDK's job now: the coordinator does it on sheet **approval**
        // (web's `validationRequested`), so a cancelled sheet leaves the split untouched.
        if (selectedPrimary != null) selectPrimary(null)
        googlePayBusy = true
        googlePay.launchSheet()
    }

    fun onPaypalClicked() {
        if (checkout == null) return
        // Same shape as [onGooglePayClicked]: collapse any open row, then hand off. `submit` runs
        // the /init itself, and the SDK commits PayPal as the primary on the buyer's approval.
        if (selectedPrimary != null) selectPrimary(null)
        paypalBusy = true
        paypal.submit()
    }

    /**
     * The shared "Pay" button's enabled state: the split must be covered and — unless no primary is
     * needed at all (secondaries alone) — the selected one must report itself ready. Google Pay is
     * excluded outright while its own flow runs, see [googlePayBusy].
     */
    fun submitEnabled(fulfilled: Boolean): Boolean =
        fulfilled && ((selectedPrimary == null && selectedToken == null) || primaryReady) &&
            !googlePayBusy && !paypalBusy && !submitting

    /** Dispatches to whichever coordinator matches the selection; `null` (secondaries alone) submits
     * through the SDK directly, since no plugin coordinator is involved. */
    fun submit() {
        val co = checkout ?: return
        if (submitting) return
        val selected = selectedPrimary
        submitting = true
        when {
            // A saved card goes through the card coordinator too (CVV-only or no form) — it owns
            // the /validate call and the 3-D Secure follow-up either way.
            selectedToken != null -> cardSubmit?.let { it() } ?: run { submitting = false }
            selected == null -> submitSecondariesOnly(co)
            // Same priority as [label] and [PluginRegistry] — before any `method` check.
            isRedirection(selected) -> redirection.submit()
            selected.method == Method.Installments -> klarna.submit()
            selected.method == Method.CreditCard -> cardSubmit?.let { it() } ?: run { submitting = false }
            // Google Pay never becomes selectedPrimary — see the class doc.
            else -> submitting = false
        }
    }

    private fun submitSecondariesOnly(co: PurseHeadlessCheckout) {
        val label = activity.getString(R.string.section_secondary)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { co.submitPayment() }
                // No partner item to key on here, so only a global `redirection.url` can carry the id.
                RedirectionResolver.extractWidgetConfirmationPaymentId(result.raw)
                    ?.let { notePaymentId(label, it) }
                succeed(label, "global_status=${result.globalStatus}", result.globalStatus)
            } catch (e: Exception) {
                fail(label, e.message, inFlight = true)
            }
        }
    }

    /**
     * Tokenize (`/init`) the gift card entered in [state] and show its balance — WITHOUT touching the
     * split. The token is cached so [applyGiftCard] can apply it as a separate, deliberate step.
     */
    fun checkBalance(method: SecondaryMethod, state: GiftCardFormState) {
        val co = checkout ?: return
        val pan = state.pan.trim()
        if (pan.isEmpty()) {
            state.error = activity.getString(R.string.error_required)
            return
        }
        val requiresCvv = co.secondaryConfig(method)?.requiresCvv("") ?: false
        val cvv = if (requiresCvv) state.cvv.trim().takeIf { it.isNotEmpty() } else null
        state.error = null
        state.checking = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { co.getSecondaryToken(method, pan, cvv) } }
                .onSuccess { token ->
                    state.token = token
                    state.balanceLabel = balanceLabel(token)
                    // The "keep this card" box starts where the method's save_token_mode puts it.
                    state.register = co.registrationOf(token).registered
                }
                .onFailure { e ->
                    state.token = null
                    state.balanceLabel = null
                    state.error = activity.getString(R.string.giftcard_error, e.message)
                }
            state.checking = false
        }
    }

    private fun balanceLabel(token: SecondaryToken): String {
        val name = token.name.ifBlank { "•••• ${token.pan.takeLast(4)}" }
        return if (token.totalBalance.compareTo(token.balance) != 0) {
            activity.getString(R.string.giftcard_balance_partial, name, token.balance.toString(), token.totalBalance.toString())
        } else {
            activity.getString(R.string.giftcard_balance, name, token.balance.toString())
        }
    }

    /**
     * Apply the balance-checked token to the split. A blank amount takes as much as the order still
     * needs; a smaller one contributes part of it (web's `'none'` take policy). Non-splittable tokens
     * ignore the amount.
     */
    fun applyGiftCard(state: GiftCardFormState) {
        val co = checkout ?: return
        val token = state.token ?: return
        val requested: BigDecimal? = state.amount.trim().takeIf { it.isNotEmpty() }?.let {
            it.toBigDecimalOrNull() ?: run {
                state.error = activity.getString(R.string.giftcard_amount_invalid)
                return
            }
        }
        state.error = null
        // The save decision rides the token's own /validate item (registration + token_label) —
        // recorded before applying, and only when the method lets the shopper choose.
        if (token.canBeRegistered) {
            try {
                co.registerSecondary(token, state.register, state.label.trim().ifEmpty { null })
            } catch (e: Exception) {
                state.error = e.message
                return
            }
        }
        co.applySecondary(token, requested)
        val applied = co.amountSplit.value.firstOrNull { it.source.id == token.id }?.amount ?: BigDecimal.ZERO
        state.appliedLabel = activity.getString(R.string.giftcard_applied, applied.toString())
        sessionDetails = summarize(co)
    }

    // --- saved gift cards ---------------------------------------------------------------------------

    /** One line per saved gift card: its name plus balance, "checking…", or why it's unavailable. */
    fun savedGiftCardLabel(token: SavedSecondaryToken): String {
        val name = token.name.ifBlank { "•••• ${token.pan.takeLast(4)}" }
        return when {
            token.disabled != null -> activity.getString(R.string.giftcard_unavailable, name, token.disabled!!.code.name)
            !token.balanceResolved -> activity.getString(R.string.giftcard_balance_pending, name)
            token.totalBalance.compareTo(token.balance) != 0 ->
                activity.getString(R.string.giftcard_balance_partial, name, token.balance.toString(), token.totalBalance.toString())
            else -> activity.getString(R.string.giftcard_balance, name, token.balance.toString())
        }
    }

    /** Whether [token] is currently drawn into the split. */
    fun isSavedGiftCardApplied(token: SavedSecondaryToken): Boolean =
        checkout?.amountSplit?.value?.any { it.source.id == token.id } == true

    /** Draw a saved gift card in for as much as the order still needs (no amount control — kept small). */
    fun applySavedGiftCard(token: SavedSecondaryToken) {
        val co = checkout ?: return
        try {
            co.applySecondary(token)
            sessionDetails = summarize(co)
        } catch (e: Exception) {
            snackbar = activity.getString(R.string.giftcard_error, e.message)
        }
    }

    fun removeSavedGiftCard(token: SavedSecondaryToken) {
        val co = checkout ?: return
        co.removeSecondary(token)
        sessionDetails = summarize(co)
    }

    // --- 3 · result -------------------------------------------------------------------------------

    /**
     * `GET /payment_session/{id}` through alfred — the independent status check the redirect flows
     * point at, rather than trusting a locally-held result. Its answer is allowed to *settle* the
     * screen: a payment the coordinators could only report as [PaymentOutcome.Unconfirmed] becomes
     * [PaymentOutcome.Authorized] once the session's own transactions say a successful authorization
     * exists. That's the one legitimate way this demo can turn "they came back from a redirect" into
     * a real outcome.
     */
    fun getTransactions() {
        val sessionId = alfredSessionId.trim()
        if (sessionId.isEmpty()) {
            snackbar = activity.getString(R.string.hint_session_id)
            return
        }
        loadingTransactions = true
        scope.launch {
            try {
                val response = alfred.paymentSession(sessionId)
                // One line per entry. This endpoint lists **operations, not payments**, so a row
                // count is not a payment count: one auto-captured payment legitimately shows
                // `AUTHORIZE` + `CAPTURE`. `parent` (the capture's `parent_transaction_id`) and
                // `ref` (`plugin_result.partner_reference`) are what tell them apart — same ref, or
                // a parent pointing at the other row's id, means one payment in two steps. Two rows
                // with the same action and unrelated refs would be a genuine duplicate.
                Log.d(TAG, "GET transactions ($sessionId): ${response.length()} operation(s)")
                for (index in 0 until response.length()) {
                    val item = response.optJSONObject(index) ?: continue
                    val status = item.optJSONObject("status")
                    Log.d(
                        TAG,
                        "  [$index] ${item.optString("partner")}/${item.optString("method")} " +
                            "action=${status?.optString("action")} state=${status?.optString("state")} " +
                            "amount=${item.optJSONObject("plugin_result")?.opt("amount")} " +
                            "id=${item.optString("id")} " +
                            "parent=${item.opt("parent_transaction_id")} " +
                            "ref=${item.optJSONObject("plugin_result")?.opt("partner_reference")}",
                    )
                }
                transactions = response.toString(2)
                transactionStatuses = statusLines(response)
                confirmOutcomeFrom(response)
            } catch (e: Exception) {
                transactions = activity.getString(R.string.alfred_error, e.message)
                transactionStatuses = emptyList()
            }
            loadingTransactions = false
        }
    }

    /** `status: {action, state}` per transaction, newest first — e.g. `CAPTURE → SUCCESS`. */
    private fun statusLines(transactions: JSONArray): List<String> =
        (0 until transactions.length()).mapNotNull { index ->
            val status = transactions.optJSONObject(index)?.optJSONObject("status") ?: return@mapNotNull null
            "${status.optString("action")} → ${status.optString("state")}"
        }

    /** Promotes an [PaymentOutcome.Unconfirmed] to authorized when the transactions prove it — and
     * only then; a lookup that shows nothing successful leaves the screen as it was. */
    private fun confirmOutcomeFrom(transactions: JSONArray) {
        val current = outcome
        if (current !is PaymentOutcome.Unconfirmed) return
        val authorized = (0 until transactions.length()).any { index ->
            val status = transactions.optJSONObject(index)?.optJSONObject("status")
            val action = status?.optString("action")
            (action == "AUTHORIZE" || action == "CAPTURE") && status.optString("state") == "SUCCESS"
        }
        if (authorized) {
            outcome = PaymentOutcome.Authorized(
                method = current.method,
                detail = activity.getString(R.string.result_confirmed_by_transactions),
            )
        }
    }

    /** Capture through alfred. [captureAmount] is major units (4.80) — the API wants minor (480). */
    fun capture() {
        val paymentId = capturePaymentId.trim()
        if (paymentId.isEmpty()) return
        val minorUnits = captureAmount.trim().toBigDecimalOrNull()
            ?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.longValueExact()
        if (minorUnits == null) {
            captureResult = activity.getString(R.string.capture_amount_invalid)
            return
        }
        capturing = true
        scope.launch {
            captureResult = try {
                alfred.capture(paymentId, minorUnits)
            } catch (e: Exception) {
                activity.getString(R.string.alfred_error, e.message)
            }
            capturing = false
        }
    }

    /** Back to screen 1, keeping nothing but the alfred URL — a fresh session needs a fresh split. */
    fun startOver() {
        resetPayment()
        checkout = null
        widgetData = ""
        sessionDetails = ""
        sessionError = null
        step = DemoStep.Session
    }

    /** The system/on-screen back gesture: one step back, never out of a payment in flight. */
    fun back() {
        if (submitting || googlePayBusy || paypalBusy) return
        step = when (step) {
            DemoStep.Session -> DemoStep.Session
            DemoStep.Pay -> DemoStep.Session
            DemoStep.Result -> DemoStep.Pay
        }
    }

    // --- outcome plumbing -------------------------------------------------------------------------

    /** A coordinator reported a terminal, non-error outcome — which may still be a declined payment
     * or an unconfirmed one, hence [PaymentOutcome.of] rather than an assumed success. */
    private fun succeed(method: String, summary: String, globalStatus: String?) {
        log("$method → $summary")
        finish(PaymentOutcome.of(method, summary, globalStatus))
    }

    private fun finish(outcome: PaymentOutcome) {
        submitting = false
        this.outcome = outcome
        step = DemoStep.Result
    }

    /**
     * An error is only a *payment* failure if a payment was actually in flight ([inFlight]) — a Klarna
     * widget that fails to load, or a `prepare()` that throws, must leave the shopper on the pay
     * screen able to pick something else instead of being thrown onto a "payment failed" screen.
     */
    private fun fail(method: String, message: String?, inFlight: Boolean) {
        log("$method → error: $message")
        submitting = false
        if (inFlight) {
            finish(PaymentOutcome.Failed(method, message.orEmpty().ifBlank { "unknown error" }))
        } else {
            snackbar = "$method — $message"
        }
    }

    private fun cancel(method: String) {
        log("$method → canceled")
        submitting = false
        snackbar = activity.getString(R.string.pay_canceled)
    }

    private fun announceAction(method: String) {
        log("$method → redirect required…")
        snackbar = activity.getString(R.string.requires_action)
    }

    private fun notePaymentId(method: String, paymentId: String) {
        log("$method → payment_id=$paymentId")
        capturePaymentId = paymentId
    }

    private fun log(line: String) {
        events += line
    }

    private fun resetPayment() {
        selectedPrimary = null
        selectedToken = null
        primaryReady = false
        redirectionPrepared = false
        submitting = false
        googlePayReady = false
        googlePayBusy = false
        googlePayItem = null
        paypalReady = false
        paypalBusy = false
        paypalItem = null
        cardSubmit = null
        outcome = null
        transactions = null
        transactionStatuses = emptyList()
        captureResult = null
        capturePaymentId = ""
        giftCards.clear()
        events.clear()
    }

    // --- session introspection --------------------------------------------------------------------

    /**
     * Mirrors `resolveDefaultSessionId` in `usp-widget`'s own demo confirmation page: prefer a
     * `legacy_payment_session_id` on the decoded `widget.data` (a field the SDK's session model
     * doesn't carry — it isn't in the published web types either) and fall back to the orchestration
     * session's own id. Empty if neither is available; the field stays editable either way.
     */
    private fun resolveAlfredSessionId(widgetData: String): String {
        val legacyId = try {
            val decoded = String(Base64.getDecoder().decode(widgetData), Charsets.UTF_8)
            JSONObject(decoded).let {
                if (it.has(LEGACY_ID) && !it.isNull(LEGACY_ID)) it.getString(LEGACY_ID) else null
            }
        } catch (e: Exception) {
            null
        }
        return legacyId ?: lastOrchestrationSessionId.orEmpty()
    }

    /** The SDK's current reactive state, for the "Session details" expander. */
    private fun summarize(co: PurseHeadlessCheckout): String = buildString {
        appendLine("session id:        ${co.currentSessionId}")
        appendLine("currency:          ${co.currency}")
        appendLine("amount:            ${co.session.amount}")
        appendLine("informationReq:    ${co.isInformationRequest}")
        appendLine("state:             ${co.sessionState.value}")
        appendLine("remainingToPay:    ${co.remainingAmountToPay.value}")
        appendLine("fulfilled:         ${co.isPaymentFulfilled.value}")
        appendLine("vault tenant:      ${co.session.vaultsConfiguration?.purse?.tenantId ?: "—"}")
        appendLine()
        appendLine("payment methods (${co.paymentMethods.value.size}):")
        co.paymentMethods.value.forEach { appendLine("  ${describe(it)}") }
        appendLine()
        appendLine("saved cards (${co.paymentTokens.value.size}):")
        co.paymentTokens.value.forEach { token ->
            val cvv = if (token.requiresCvv) "cvv required" else "no cvv"
            appendLine("  ${token.id}  ${label(token)} ${token.maskedPan} ($cvv)${if (token.favorite) " ★" else ""}")
        }
        appendLine()
        appendLine("applied secondaries (${co.appliedSecondaries.size}):")
        co.appliedSecondaries.forEach { token ->
            val applied = co.amountSplit.value.firstOrNull { it.source.id == token.id }?.amount ?: BigDecimal.ZERO
            appendLine("  ${token.id}  applied $applied / balance ${token.balance}")
        }
        appendLine()
        appendLine("split (${co.amountSplit.value.size}):")
        co.amountSplit.value.forEach { appendLine("  ${it.source.id} → ${it.amount}") }
    }

    /**
     * `template=` and `plugin=` are here for exactly the generic-template question: the template is
     * what the session *declares*, the plugin is what [PluginRegistry] actually *resolved* — and the
     * two legitimately differ (a vault vendor outranks the template; an `integration_mode` of
     * `redirection` routes to the redirection generic with no template at all).
     */
    private fun describe(item: PaymentItem): String {
        val kind = if (item.isSecondary) "secondary" else "primary"
        val disabled = item.disabled?.let { " [disabled: ${it.code}]" } ?: ""
        val integration = (item as? PrimaryMethod)?.integrationType?.let { " integration=${it.wireValue}" } ?: ""
        val template = item.configuration.template?.let { " template=$it" } ?: ""
        val plugin = checkout?.session?.source
            ?.let { PluginRegistry.getPlugin(item.partner, item.method, it) }
            ?.let { " plugin=${it::class.simpleName}" } ?: ""
        val brands = (item as? PrimaryMethod)?.supportedBrands
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",") { it.wireValue }
            ?.let { " brands=$it" } ?: ""
        return "${item.id} ($kind)$template$integration$plugin$brands$disabled"
    }

    private val klarnaLabel: String get() = activity.getString(R.string.method_klarna)
    private val cardLabel: String get() = activity.getString(R.string.method_card)
    private val redirectionLabel: String get() = activity.getString(R.string.method_redirection)

    private companion object {
        const val TAG = "DemoController"
        const val GOOGLE_PAY = "Google Pay"
        const val PAYPAL = "PayPal"
        const val LEGACY_ID = "legacy_payment_session_id"
    }
}

/** Per-gift-card form state, held here (not in composition) so a collapsed row keeps its checked
 * balance and token. */
class GiftCardFormState {
    var pan by mutableStateOf("")
    var cvv by mutableStateOf("")
    var amount by mutableStateOf("")
    /** "Keep this card for next time" — only shown when the tokenized card `canBeRegistered`. */
    var register by mutableStateOf(false)
    var label by mutableStateOf("")
    var token by mutableStateOf<SecondaryToken?>(null)
    var balanceLabel by mutableStateOf<String?>(null)
    var appliedLabel by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var checking by mutableStateOf(false)
}
