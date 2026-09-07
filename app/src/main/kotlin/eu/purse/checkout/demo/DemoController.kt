// Web: usp-widget's playground / alfred demo pages.
// Same: mint a session through alfred, drive the headless checkout, check the outcome independently.
// Differs: one Compose controller; every payment method renders through `rememberPaymentElement`.
package eu.purse.checkout.demo

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.element.PaymentElement
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SavedSecondaryToken
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayPlugin
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalPlugin
import eu.purse.partnerui.redirect.RedirectionResolver
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The three screens this demo walks through, in order. */
enum class DemoStep { Session, Pay, Result }

/**
 * All of the demo's state and logic, so the screens ([SessionScreen], [PayScreen], [ResultScreen])
 * stay pure UI. Everything mutable is Compose state.
 *
 * Payment methods are rendered by the SDK's own `rememberPaymentElement`: the controller only
 * tracks which item is selected, holds that item's live [PaymentElement] for the shared "Pay"
 * button, and turns element callbacks into an outcome. Express methods (Google Pay, PayPal) render
 * their own button and submit themselves; the rest are rows the shopper selects.
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

    var refreshingSession by mutableStateOf(false)
        private set

    /** Shown as an error card on screen 1; `null` when there's nothing wrong. */
    var sessionError by mutableStateOf<String?>(null)
        private set

    var checkout by mutableStateOf<PurseHeadlessCheckout?>(null)
        private set

    /** The SDK's decoded state, dumped for the "Session details" expander. */
    var sessionDetails by mutableStateOf("")
        private set

    /** The orchestration session id alfred returned — fallback default for the transactions lookup. */
    private var lastOrchestrationSessionId: String? = null

    // --- 2 · pay ----------------------------------------------------------------------------------

    /** The selected row: a [PrimaryMethod] or a saved [PaymentToken]; `null` = none (secondaries alone). */
    var selectedItem by mutableStateOf<PaymentItem?>(null)
        private set

    /** The live element behind [selectedItem], registered by its row once composed. */
    var selectedElement by mutableStateOf<PaymentElement?>(null)
        private set

    /** A submit is in flight through the shared "Pay" button — drives the blocking overlay. */
    var submitting by mutableStateOf(false)
        private set

    private val giftCards = mutableMapOf<String, GiftCardFormState>()

    // --- 3 · result -------------------------------------------------------------------------------

    var outcome by mutableStateOf<PaymentOutcome?>(null)
        private set

    /** Prefilled by [initCheckout], editable — the id the transactions lookup uses. */
    var alfredSessionId by mutableStateOf("")

    var transactions by mutableStateOf<String?>(null)
        private set

    /** One readable line per transaction — `"AUTHORIZE → SUCCESS"`. */
    var transactionStatuses by mutableStateOf<List<String>>(emptyList())
        private set

    var loadingTransactions by mutableStateOf(false)
        private set

    /** Prefilled from whichever element reported a `payment_id`. */
    var capturePaymentId by mutableStateOf("")

    /** Prefilled with the session's own amount, editable for a partial capture. */
    var captureAmount by mutableStateOf("")

    var captureResult by mutableStateOf<String?>(null)
        private set
    var capturing by mutableStateOf(false)
        private set

    // --- shared -----------------------------------------------------------------------------------

    /** Every element callback, in order — shown behind the "Event log" expander. */
    val events = mutableStateListOf<String>()

    /** Set to show a snackbar; [DemoApp] clears it once shown. */
    var snackbar by mutableStateOf<String?>(null)

    // --- 1 · session ------------------------------------------------------------------------------

    /** Mint a session through alfred and hand its `widget.data` straight to the SDK. */
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

    /** Initialize the SDK off whatever [widgetData] currently holds. */
    fun initCheckout() {
        val data = widgetData.trim()
        if (data.isEmpty()) return
        resetPayment()
        checkout?.teardown()
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
    }

    fun goToPay() {
        if (checkout != null) step = DemoStep.Pay
    }

    /**
     * Mint a **new** session and hand it to the live checkout — `setSession`, the "cart changed /
     * session expired" path. What survives (an unchanged method keeps its element and selection) is
     * the SDK's doing; elements rebuild themselves off `itemGenerations`.
     */
    fun refreshSession() {
        val co = checkout ?: return
        refreshingSession = true
        scope.launch {
            try {
                val session = alfred.createSession()
                co.setSession(session.widgetData)
                widgetData = session.widgetData
                lastOrchestrationSessionId = session.orchestrationSessionId
                alfredSessionId = resolveAlfredSessionId(session.widgetData)
                sessionDetails = summarize(co)
                captureAmount = co.session.amount.toPlainString()
                log("session refreshed → ${co.currentSessionId} (${co.session.amount} ${co.currency})")
            } catch (e: Exception) {
                log("session refresh failed: ${e.message}")
            } finally {
                refreshingSession = false
            }
        }
    }

    // --- 2 · pay ----------------------------------------------------------------------------------

    /** Express methods render their own button and submit themselves: Google Pay, PayPal pay-now. */
    fun isExpress(method: PrimaryMethod): Boolean =
        pluginOf(method).let { it is GooglePayPlugin || it is PaypalPlugin }

    /** Whether [method] resolves to the generic redirection plugin — asked of the registry, not re-derived. */
    fun isRedirection(method: PrimaryMethod): Boolean = pluginOf(method) is RedirectionPlugin

    private fun pluginOf(method: PrimaryMethod) =
        checkout?.session?.source?.let { PluginRegistry.getPlugin(method.partner, method.method, it) }

    val expressMethods: List<PrimaryMethod>
        get() = checkout?.primaryMethods?.filter { isExpress(it) }.orEmpty()

    /** Every other primary with a plugin: a row the shopper selects, submitted by the shared button. */
    val payableMethods: List<PrimaryMethod>
        get() = checkout?.primaryMethods?.filter { !isExpress(it) && pluginOf(it) != null }.orEmpty()

    /** The secondaries with a registered config — the rest can't be tokenized. */
    val giftCardMethods: List<SecondaryMethod>
        get() = checkout?.let { co -> co.secondaryMethods.filter { co.secondaryConfig(it) != null } }.orEmpty()

    fun giftCardState(method: SecondaryMethod): GiftCardFormState =
        giftCards.getOrPut(method.id) { GiftCardFormState() }

    /**
     * Select a row (or `null` to collapse everything). Choosing an item and committing it as the
     * primary are one action; only the selected row composes its element, so nothing is `/init`'d
     * for a row the shopper hasn't picked.
     */
    fun select(item: PaymentItem?) {
        val co = checkout ?: return
        selectedItem = item
        selectedElement = null
        when (item) {
            is PrimaryMethod -> co.setPrimary(item)
            is PaymentToken -> co.setPrimaryToken(item)
            else -> co.clearPrimarySplit()
        }
    }

    /** The selected row's element, once composed — what [submit] drives. */
    fun attachElement(element: PaymentElement) {
        if (element.item.id == selectedItem?.id) selectedElement = element
    }

    /**
     * An approved express sheet makes its method the primary inside the SDK (web's
     * `validationRequested`); collapse whatever row was open so the screen shows what is submitted.
     */
    fun syncSelection(split: List<PaymentSplit>) {
        val primaryId = split.map { it.source }.firstOrNull { it is PrimaryMethod || it is PaymentToken }?.id
        if (primaryId != null && primaryId != selectedItem?.id) {
            selectedItem = null
            selectedElement = null
        }
    }

    /** One listener per item, labelled for the event log and the result screen. */
    fun listenerFor(item: PaymentItem): PaymentElementListener {
        val label = label(item)
        return object : PaymentElementListener {
            override fun onResult(summary: String, globalStatus: String?) = succeed(label, summary, globalStatus)
            override fun onRequiresAction() = announceAction(label)
            override fun onPaymentId(paymentId: String) = notePaymentId(label, paymentId)
            override fun onCanceled() = cancel(label)
            override fun onError(message: String?) = fail(label, message, inFlight = submitting)
        }
    }

    fun label(item: PaymentItem): String = when (item) {
        is PaymentToken -> "${partnerLabel(item)} · ${item.name.ifBlank { item.label }}"
        is PrimaryMethod -> when {
            isRedirection(item) -> "$redirectionLabel — ${item.partner.wireValue}"
            item.method == Method.GooglePay -> GOOGLE_PAY
            item.partner == Partner.Paypal -> PAYPAL
            item.method == Method.Installments -> klarnaLabel
            item.method == Method.CreditCard -> cardLabel
            else -> item.id
        }
        else -> item.id
    }

    /** The partner behind a saved token, as a display name. */
    fun partnerLabel(token: PaymentToken): String =
        if (token.partner == Partner.Paypal) PAYPAL else token.partner.wireValue.replaceFirstChar { it.uppercase() }

    /** Whether this gift card's config asks for a CVC alongside the PAN. */
    fun requiresCvv(method: SecondaryMethod): Boolean =
        checkout?.secondaryConfig(method)?.requiresCvv("") ?: false

    /** The shared "Pay" button: split covered, and the selected element (if any) ready. */
    fun submitEnabled(fulfilled: Boolean): Boolean =
        fulfilled && (selectedItem == null || selectedElement?.ready == true) && !submitting

    /** Submit the selected element; with no selection (secondaries alone) go straight to the SDK. */
    fun submit() {
        val co = checkout ?: return
        if (submitting) return
        submitting = true
        val element = selectedElement
        when {
            selectedItem == null -> submitSecondariesOnly(co)
            element != null -> element.submit()
            else -> submitting = false
        }
    }

    private fun submitSecondariesOnly(co: PurseHeadlessCheckout) {
        val label = activity.getString(R.string.section_secondary)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { co.submitPayment() }
                RedirectionResolver.extractWidgetConfirmationPaymentId(result.raw)?.let { notePaymentId(label, it) }
                succeed(label, "global_status=${result.globalStatus}", result.globalStatus)
            } catch (e: Exception) {
                fail(label, e.message, inFlight = true)
            }
        }
    }

    /** Tokenize (`/init`) the gift card in [state] and show its balance, without touching the split. */
    fun checkBalance(method: SecondaryMethod, state: GiftCardFormState) {
        val co = checkout ?: return
        val pan = state.pan.trim()
        if (pan.isEmpty()) {
            state.error = activity.getString(R.string.error_required)
            return
        }
        val cvv = if (requiresCvv(method)) state.cvv.trim().takeIf { it.isNotEmpty() } else null
        state.error = null
        state.checking = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { co.getSecondaryToken(method, pan, cvv) } }
                .onSuccess { token ->
                    state.token = token
                    state.balanceLabel = balanceLabel(token)
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

    /** Apply the balance-checked token to the split; a blank amount takes what the order still needs. */
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

    fun isSavedGiftCardApplied(token: SavedSecondaryToken): Boolean =
        checkout?.amountSplit?.value?.any { it.source.id == token.id } == true

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
     * `GET /payment_session/{id}` through alfred — the independent status check a redirect return
     * points at. A successful AUTHORIZE/CAPTURE settles an [PaymentOutcome.Unconfirmed] screen.
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
                Log.d(TAG, "GET transactions ($sessionId): ${response.length()} operation(s)")
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

    private fun statusLines(transactions: JSONArray): List<String> =
        (0 until transactions.length()).mapNotNull { index ->
            val status = transactions.optJSONObject(index)?.optJSONObject("status") ?: return@mapNotNull null
            "${status.optString("action")} → ${status.optString("state")}"
        }

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

    /** Back to screen 1, keeping nothing but the alfred URL. */
    fun startOver() {
        resetPayment()
        checkout?.teardown()
        checkout = null
        widgetData = ""
        sessionDetails = ""
        sessionError = null
        step = DemoStep.Session
    }

    /** The back gesture: one step back, never out of a payment in flight. */
    fun back() {
        if (submitting) return
        step = when (step) {
            DemoStep.Session -> DemoStep.Session
            DemoStep.Pay -> DemoStep.Session
            DemoStep.Result -> DemoStep.Pay
        }
    }

    // --- outcome plumbing -------------------------------------------------------------------------

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
     * An error is only a *payment* failure when a payment was in flight; a widget that fails to load
     * or a `/init` that throws leaves the shopper on the pay screen with a snackbar.
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
        selectedItem = null
        selectedElement = null
        submitting = false
        outcome = null
        transactions = null
        transactionStatuses = emptyList()
        captureResult = null
        capturePaymentId = ""
        giftCards.clear()
        events.clear()
    }

    // --- session introspection --------------------------------------------------------------------

    /** Web demo's `resolveDefaultSessionId`: `legacy_payment_session_id` off the widget data, else the orchestration id. */
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
        appendLine("saved tokens (${co.paymentTokens.value.size}):")
        co.paymentTokens.value.forEach { token ->
            val cvv = when {
                !token.isVaultedCard -> "${partnerLabel(token)} account, no cvv"
                token.requiresCvv -> "cvv required"
                else -> "no cvv"
            }
            appendLine("  ${token.id}  ${token.name.ifBlank { token.label }} ${token.maskedPan} ($cvv)${if (token.favorite) " ★" else ""}")
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

    /** `template=` is what the session declares, `plugin=` what the registry resolved; they can differ. */
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

/** Per-gift-card form state, held outside composition so a collapsed row keeps its checked balance. */
class GiftCardFormState {
    var pan by mutableStateOf("")
    var cvv by mutableStateOf("")
    var amount by mutableStateOf("")
    var register by mutableStateOf(false)
    var label by mutableStateOf("")
    var token by mutableStateOf<SecondaryToken?>(null)
    var balanceLabel by mutableStateOf<String?>(null)
    var appliedLabel by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var checking by mutableStateOf(false)
}
