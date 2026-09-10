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
import eu.purse.checkout.payment.item.DisabledState
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PaymentToken
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SavedSecondaryToken
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.checkout.state.SessionState
import eu.purse.core.error.PurseException
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerUiException
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PaymentOutcome
import eu.purse.partnerui.PluginOptions
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.PurseTheme
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayPlugin
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import eu.purse.partnerui.plugins.partners.paypal.wallet.PaypalPlugin
import eu.purse.partnerui.redirect.RedirectionResolver
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The three screens this demo walks through, in order: Purse's setup page, then the store's checkout and result. */
enum class DemoStep { Setup, Checkout, Result }

/** The presets on the setup page — which of the session's methods the store shows the shopper. */
enum class MethodPreset { Everything, CardOnly, Wallets, NoExpress }

/** One entry of the setup page's method picker: a method the session offers, or the saved-instruments group. */
data class PickerEntry(val id: String, val name: String, val iconUrl: String?, val hint: String)

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

    var step by mutableStateOf(DemoStep.Setup)
        private set

    /** The `</>` panel on the store screens: session, split, events, transactions, capture. */
    var developerPanel by mutableStateOf(false)

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

    /** The basket the checkout shows, read off the order alfred minted the session from. */
    var order by mutableStateOf<OrderSummary?>(null)
        private set

    /**
     * Picker entries the store has switched **off** — the complement is what the checkout renders.
     * Kept as the hidden set so a method that appears with a new session is shown by default.
     */
    var hiddenIds by mutableStateOf<Set<String>>(emptySet())
        private set

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

    var outcome by mutableStateOf<DemoResult?>(null)
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

    /** The result screen is polling the session's transactions to settle an unconfirmed outcome. */
    var confirming by mutableStateOf(false)
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
                order = OrderSummary.from(session.order)
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
            PurseHeadlessCheckout.init(data, options = PluginOptions(theme = MerchantCardTheme))
        } catch (e: Exception) {
            sessionError = activity.getString(R.string.init_error, e.message)
            return
        }
        sessionError = null
        checkout = co
        sessionDetails = summarize(co)
        alfredSessionId = resolveAlfredSessionId(data)
        captureAmount = co.session.amount.toPlainString()
        // A pasted widget.data has no order behind it: one line for the session's own amount.
        if (order == null || order?.total?.compareTo(co.session.amount) != 0) {
            order = order?.takeIf { it.total.compareTo(co.session.amount) == 0 }
                ?: OrderSummary.placeholder(co.session.amount, co.currency)
        }
    }

    fun openCheckout() {
        if (checkout != null) step = DemoStep.Checkout
    }

    // --- 1 · method picker ------------------------------------------------------------------------

    /** Everything the store can switch on or off: each usable method, gift cards, and saved instruments. */
    val pickerEntries: List<PickerEntry>
        get() {
            val co = checkout ?: return emptyList()
            val primaries = co.primaryMethods.filter { pluginOf(it) != null }.sortedBy(::displayRank).map { method ->
                val hint = disabledReason(activity, method, co.currency)?.let { "$it · ${pickerHint(method)}" } ?: pickerHint(method)
                PickerEntry(method.id, displayName(method), co.iconUrl(method), hint)
            }
            val secondaries = co.secondaryMethods.filter { co.secondaryConfig(it) != null }.map { method ->
                PickerEntry(method.id, displayName(method), co.iconUrl(method), activity.getString(R.string.picker_hint_giftcard))
            }
            val saved = if (co.paymentTokens.value.isNotEmpty() || co.savedSecondaryTokens.value.isNotEmpty()) {
                val name = activity.getString(R.string.picker_saved)
                listOf(PickerEntry(SAVED_GROUP, name, null, activity.getString(R.string.picker_hint_saved)))
            } else {
                emptyList()
            }
            return primaries + secondaries + saved
        }

    fun isShown(id: String): Boolean = id !in hiddenIds

    fun toggleShown(id: String) {
        hiddenIds = if (id in hiddenIds) hiddenIds - id else hiddenIds + id
    }

    /** Which preset the current hidden set amounts to, for the chip row; `null` when it's a custom mix. */
    val activePreset: MethodPreset?
        get() = MethodPreset.entries.firstOrNull { hiddenFor(it) == hiddenIds }

    fun applyPreset(preset: MethodPreset) {
        hiddenIds = hiddenFor(preset)
    }

    private fun hiddenFor(preset: MethodPreset): Set<String> {
        val all = pickerEntries.map { it.id }.toSet()
        val cards = checkout?.primaryMethods?.filter { it.method == Method.CreditCard }?.map { it.id }.orEmpty().toSet()
        val express = expressIds()
        return when (preset) {
            MethodPreset.Everything -> emptySet()
            MethodPreset.CardOnly -> all - cards
            MethodPreset.Wallets -> all - express
            MethodPreset.NoExpress -> express
        }
    }

    private fun expressIds(): Set<String> = checkout?.primaryMethods?.filter { isExpress(it) }?.map { it.id }.orEmpty().toSet()

    /** Whether saved cards and saved gift cards are shown — one switch for the group. */
    val showSaved: Boolean get() = isShown(SAVED_GROUP)

    private fun pickerHint(method: PrimaryMethod): String = when {
        isExpress(method) -> activity.getString(R.string.picker_hint_express)
        isRedirection(method) -> activity.getString(R.string.picker_hint_redirection)
        method.method == Method.Installments -> activity.getString(R.string.picker_hint_installments)
        method.method == Method.CreditCard -> activity.getString(R.string.picker_hint_card, cardProviderName(activity, method))
        else -> method.id
    }

    /** More than one card method on the checkout: each row names its provider so they can be told apart. */
    val severalCardMethods: Boolean
        get() = payableMethods.count { it.method == Method.CreditCard } > 1

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
                order = OrderSummary.from(session.order)
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
    private fun isExpress(method: PrimaryMethod): Boolean =
        pluginOf(method).let { it is GooglePayPlugin || it is PaypalPlugin }

    /** Whether [method] resolves to the generic redirection plugin — asked of the registry, not re-derived. */
    fun isRedirection(method: PrimaryMethod): Boolean = pluginOf(method) is RedirectionPlugin

    private fun pluginOf(method: PrimaryMethod) =
        checkout?.session?.source?.let { PluginRegistry.getPlugin(method.partner, method.method, it) }

    /** The express methods the store shows (see [hiddenIds]). */
    val expressMethods: List<PrimaryMethod>
        get() = checkout?.primaryMethods?.filter { isExpress(it) && isShown(it.id) }.orEmpty()

    /** Every other shown primary with a plugin: a row the shopper selects, submitted by the shared button. */
    val payableMethods: List<PrimaryMethod>
        get() = checkout?.primaryMethods?.filter { !isExpress(it) && pluginOf(it) != null && isShown(it.id) }.orEmpty()
            .sortedBy(::displayRank)

    /**
     * Card first, then the wallets, then instalments, then everything else as the session lists it —
     * the order a shopper expects on a checkout, independent of the session's own ordering.
     */
    private fun displayRank(method: PrimaryMethod): Int = when {
        method.method == Method.CreditCard -> 0
        method.method == Method.GooglePay -> 1
        method.partner == Partner.Paypal -> 2
        method.method == Method.Installments -> 3
        else -> 4
    }

    /** The shown secondaries with a registered config — the rest can't be tokenized. */
    val giftCardMethods: List<SecondaryMethod>
        get() = checkout?.let { co -> co.secondaryMethods.filter { co.secondaryConfig(it) != null && isShown(it.id) } }.orEmpty()

    fun giftCardState(method: SecondaryMethod): GiftCardFormState =
        giftCards.getOrPut(method.id) { GiftCardFormState() }

    /**
     * Select a row (or `null` to collapse everything). Choosing an item and committing it as the
     * primary are one action; only the selected row composes its element, so nothing is `/init`'d
     * for a row the shopper hasn't picked.
     */
    fun select(item: PaymentItem?) {
        val co = checkout ?: return
        val live = item?.let { liveItem(it) }
        val disabled = live?.disabled
        if (disabled != null && disabled !is DisabledState.Submitting) {
            // The SDK refuses a disabled primary (`MethodDisabled`); the row already says why.
            snackbar = disabledReason(activity, live, co.currency)
            return
        }
        selectedItem = live
        selectedElement = null
        try {
            when (live) {
                is PrimaryMethod -> co.setPrimary(live)
                is PaymentToken -> co.setPrimaryToken(live)
                else -> co.clearPrimarySplit()
            }
        } catch (e: PurseException) {
            selectedItem = null
            snackbar = e.message
        }
    }

    /** The current copy of [item] — `disabled` moves with the split, so never trust a stale reference. */
    private fun liveItem(item: PaymentItem): PaymentItem {
        val co = checkout ?: return item
        return co.paymentMethods.value.firstOrNull { it.id == item.id }
            ?: co.paymentTokens.value.firstOrNull { it.id == item.id }
            ?: item
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
            override fun onOutcome(outcome: PaymentOutcome) = when (outcome) {
                PaymentOutcome.Canceled -> cancel(label)
                is PaymentOutcome.Failed -> fail(label, outcome, inFlight = isPaying(item))
                else -> finishWith(label, outcome)
            }
            override fun onRequiresAction() = announceAction(label)
            override fun onPaymentId(paymentId: String) = notePaymentId(label, paymentId)
        }
    }

    /** What the shopper reads on a method row: "Card", "Google Pay", "Klarna", "iDEAL", a saved card's name. */
    fun displayName(item: PaymentItem): String = when (item) {
        is PaymentToken -> item.name.ifBlank { if (item.isVaultedCard) "${item.brand} ${item.maskedPan}" else partnerLabel(item) }
        is PrimaryMethod -> when {
            item.method == Method.GooglePay -> GOOGLE_PAY
            item.partner == Partner.Paypal -> PAYPAL
            item.method == Method.Installments -> klarnaLabel
            item.method == Method.CreditCard -> cardLabel
            isRedirection(item) -> {
                val wire = item.method.wireValue.lowercase()
                if (wire in SCHEME_METHODS) prettyWire(wire) else prettyWire(item.partner.wireValue)
            }
            else -> prettyWire(item.method.wireValue)
        }
        is SecondaryMethod -> if (item.partner == Partner.Illicado) "Illicado" else activity.getString(R.string.section_secondary)
        else -> item.id
    }

    /** The label the event log and the result screen use — more precise than [displayName]. */
    private fun label(item: PaymentItem): String = when (item) {
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

    /**
     * Whether a `Failed` from [item]'s element is *this* payment failing, or just that element
     * complaining on the side (an express button's `/init` on composition, a widget that failed to
     * load). Only the selected element is paying through the shared button; an express element is
     * paying only while the SDK has a submit in flight.
     */
    private fun isPaying(item: PaymentItem): Boolean {
        val co = checkout ?: return false
        return when {
            item is PrimaryMethod && isExpress(item) -> co.sessionState.value == SessionState.SUBMITTING
            else -> submitting && selectedItem?.id == item.id
        }
    }

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
                finishWith(label, PaymentOutcome.Completed(result))
            } catch (e: Exception) {
                val error = e as? PurseException ?: PartnerUiException(e.message ?: "unknown error", e)
                fail(label, PaymentOutcome.Failed(error), inFlight = true)
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
     * points at. A successful AUTHORIZE/CAPTURE settles an [DemoResult.Unconfirmed] screen.
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
        if (current !is DemoResult.Unconfirmed) return
        val authorized = (0 until transactions.length()).any { index ->
            val status = transactions.optJSONObject(index)?.optJSONObject("status")
            val action = status?.optString("action")
            (action == "AUTHORIZE" || action == "CAPTURE") && status.optString("state") == "SUCCESS"
        }
        if (authorized) {
            outcome = DemoResult.Authorized(
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

    /** Back to the setup page with a fresh session — the store is ready for the next shopper. */
    fun startOver() {
        resetPayment()
        checkout?.teardown()
        checkout = null
        widgetData = ""
        sessionDetails = ""
        sessionError = null
        order = null
        developerPanel = false
        step = DemoStep.Setup
        createSession()
    }

    /** From a declined payment, back to the checkout to try another method — same session. */
    fun tryAgain() {
        if (submitting) return
        outcome = null
        step = DemoStep.Checkout
    }

    /** The back gesture: one step back, never out of a payment in flight. */
    fun back() {
        if (submitting) return
        step = when (step) {
            DemoStep.Setup -> DemoStep.Setup
            DemoStep.Checkout -> DemoStep.Setup
            DemoStep.Result -> DemoStep.Checkout
        }
    }

    // --- outcome plumbing -------------------------------------------------------------------------

    private fun finishWith(method: String, outcome: PaymentOutcome) {
        log("$method → $outcome")
        finish(DemoResult.of(method, outcome))
    }

    private fun finish(outcome: DemoResult) {
        submitting = false
        this.outcome = outcome
        step = DemoStep.Result
        if (outcome is DemoResult.Unconfirmed) confirmInBackground()
    }

    /**
     * A redirect return is not a verdict, so the result screen settles it itself: poll the session's
     * transactions a few times, the way a merchant backend (or `awaitPaymentOutcome`) would. The
     * "Get transactions" button in the developer panel stays for a manual retry.
     */
    private fun confirmInBackground() {
        if (alfredSessionId.isBlank()) return
        confirming = true
        scope.launch {
            repeat(CONFIRM_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) CONFIRM_FIRST_DELAY_MS else CONFIRM_RETRY_DELAY_MS)
                if (outcome !is DemoResult.Unconfirmed) return@launch
                getTransactions()
                while (loadingTransactions) delay(100)
                if (outcome !is DemoResult.Unconfirmed) {
                    confirming = false
                    return@launch
                }
            }
            confirming = false
        }
    }

    /**
     * An error is only a *payment* failure when a payment was in flight; a widget that fails to load
     * or a `/init` that throws leaves the shopper on the pay screen with a snackbar.
     */
    private fun fail(method: String, outcome: PaymentOutcome.Failed, inFlight: Boolean) {
        log("$method → error: ${outcome.error.code} ${outcome.error.message}")
        submitting = false
        if (inFlight) {
            finish(DemoResult.of(method, outcome))
        } else {
            // Before any submit: the method could not be set up (its item is disabled accordingly).
            snackbar = "$method — ${outcome.error.message}"
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
        const val SAVED_GROUP = "saved"
        const val CONFIRM_ATTEMPTS = 4
        const val CONFIRM_FIRST_DELAY_MS = 1_500L
        const val CONFIRM_RETRY_DELAY_MS = 3_000L

        /** Redirection methods a shopper knows by the scheme, not the PSP behind it. */
        val SCHEME_METHODS =
            setOf("ideal", "bancontact", "mbway", "multibanco", "sepa", "bizum", "sofort", "giropay", "eps", "blik", "twint")
    }
}

/**
 * The store's styling for the secure card form — what a merchant passes so Purse's fields sit in
 * its own checkout without looking bolted on. Same scopes as the web SDK's theme (`global`, `input`,
 * `label`, `helperText`, with `:focus` / `:invalid` variants); values are CSS-like strings.
 */
private val MerchantCardTheme = PurseTheme(
    global = PurseTheme.Text(color = "#16181F", fontFamily = "sans-serif", fontSize = "16px"),
    globalInvalid = PurseTheme.Text(color = "#B3261E"),
    input = PurseTheme.FieldStyle(backgroundColor = "#F4F5F8", placeholderColor = "#858A98", color = "#16181F"),
    inputFocus = PurseTheme.FieldStyle(backgroundColor = "#FFFFFF"),
    label = PurseTheme.Text(color = "#223B7A", fontSize = "13px", fontWeight = "600"),
    labelInvalid = PurseTheme.Text(color = "#B3261E"),
    helperText = PurseTheme.Text(color = "#858A98", fontSize = "12px"),
    helperTextInvalid = PurseTheme.Text(color = "#B3261E"),
    gap = "12px",
)

/** "ideal" → "iDEAL", "bancontact" → "Bancontact", "sepa_debit" → "Sepa debit". */
private fun prettyWire(wire: String): String = when (wire.lowercase()) {
    "ideal" -> "iDEAL"
    "mbway" -> "MB WAY"
    "sepa" -> "SEPA"
    else -> wire.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** A shopper-facing line for a disabled item: amounts in the order's currency, no codes. `null` when usable. */
fun disabledReason(context: android.content.Context, item: PaymentItem, currency: String): String? =
    when (val state = item.disabled) {
        null -> null
        is DisabledState.MaxAmountExceeded -> context.getString(R.string.disabled_max_amount, moneyText(state.maxAmount, currency))
        is DisabledState.MinAmountNotMet -> context.getString(R.string.disabled_min_amount, moneyText(state.minAmount, currency))
        is DisabledState.AmountConditionUnreachable -> context.getString(R.string.disabled_amount_unreachable)
        DisabledState.PaymentFulfilled -> context.getString(R.string.disabled_fulfilled)
        // A payment in flight locks every method, but that is the busy overlay's job to show; treating
        // it as "disabled" here would collapse the selected element and cancel its coordinator mid-submit.
        DisabledState.Submitting -> null
        else -> context.getString(R.string.disabled_unavailable)
    }

/** "Purse vault", "Adyen", "ACI" — the PSP behind a card method, for telling two card rows apart. */
fun cardProviderName(context: android.content.Context, method: PrimaryMethod): String = when (method.partner) {
    Partner.Purse -> context.getString(R.string.card_provider_purse)
    Partner.Aci -> "ACI"
    else -> prettyWire(method.partner.wireValue)
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
