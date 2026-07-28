package eu.purse.checkout

import eu.purse.checkout.payment.item.MethodAvailability
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.checkout.payment.secondary.SecondaryConfig
import eu.purse.checkout.payment.secondary.SecondaryConfigs
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.payment.secondary.SecondaryTokenizer
import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.checkout.payment.split.PursePaymentSplitter
import eu.purse.checkout.session.PaymentSession
import eu.purse.checkout.session.PaymentSessionBuilder
import eu.purse.checkout.state.SessionState
import eu.purse.checkout.validation.PaymentValidator
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.payload.ValidationResult
import eu.purse.core.session.PurseSessionParams
import eu.purse.core.session.SessionCodec
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayHost
import eu.purse.partnerui.plugins.partners.klarna.KlarnaHost
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.math.BigDecimal
import java.time.Instant

/**
 * The headless purse-payment SDK — the Kotlin analog of web's `PurseHeadlessCheckout`. It owns the
 * purse-payment concept and lets a merchant build their own checkout UI on top:
 *
 * - **session (V2)**: [init] decodes the base64 `widget.data` blob (via `:core`'s [SessionCodec])
 *   down to a [PaymentSession];
 * - **payment model**: [paymentMethods] — primaries + secondaries, with live disabled states;
 * - **splits**: pick a primary ([setPrimary]) and/or draw down secondaries ([takeSecondary]);
 *   [remainingAmountToPay], [amountSplit] and [isPaymentFulfilled] track the split reactively;
 * - **create payment**: [submitPayment] runs the plugin-driven `/validate` pipeline over the split;
 * - **session params / vault / merchant_specifics** are handed to `:partner-ui` plugins through the
 *   [PaymentValidator] → `PartnerUiContext`.
 *
 * The reactive surface is exposed as [StateFlow] (the analog of web's Svelte stores). Collect it
 * from a coroutine scope. The Payment API is blocking, so [submitPayment] is `suspend`.
 */
class PurseHeadlessCheckout private constructor(
    val params: PurseSessionParams,
    val session: PaymentSession,
    /** Resolved Payment API root; calls go to `{root}/{entityId}/sessions/{sessionId}/…`. */
    val paymentApiRoot: String,
) {
    private val splitter = PursePaymentSplitter(session.amount)

    /** Interactively-collected data per method id (e.g. a Google Pay token) — see [setCollected]. */
    private val collectedByMethod = mutableMapOf<String, Map<String, Any?>>()

    private val _sessionState = MutableStateFlow(
        if (session.expirationDate.isBefore(Instant.now())) SessionState.EXPIRED else SessionState.READY,
    )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _paymentMethods = MutableStateFlow(session.meansOfPayment)
    val paymentMethods: StateFlow<List<PaymentItem>> = _paymentMethods.asStateFlow()

    private val _remainingAmountToPay = MutableStateFlow(splitter.remaining)
    val remainingAmountToPay: StateFlow<BigDecimal> = _remainingAmountToPay.asStateFlow()

    private val _isPaymentFulfilled = MutableStateFlow(splitter.fulfilled)
    val isPaymentFulfilled: StateFlow<Boolean> = _isPaymentFulfilled.asStateFlow()

    private val _amountSplit = MutableStateFlow(splitter.splits)
    val amountSplit: StateFlow<List<PaymentSplit>> = _amountSplit.asStateFlow()

    val currency: String get() = session.currency
    val isInformationRequest: Boolean get() = session.isInformationRequest
    val currentSessionId: String get() = session.id

    val primaryMethods: List<PrimaryMethod> get() = session.meansOfPayment.filterIsInstance<PrimaryMethod>()
    val secondaryMethods: List<SecondaryMethod> get() = session.meansOfPayment.filterIsInstance<SecondaryMethod>()

    /** Applied secondary tokens, in the order they were added. */
    val appliedSecondaries: List<SecondaryToken> get() = splitter.appliedSecondaries

    /** Choose (or clear, with `null`) the primary method that covers the remaining amount. */
    fun setPrimary(method: PrimaryMethod?) {
        splitter.setPrimary(method)
        refresh()
    }

    /** The per-partner config for a secondary method (PAN/CVV rules, take policy) — `null` if none. */
    fun secondaryConfig(method: SecondaryMethod): SecondaryConfig? = SecondaryConfigs.forMethod(method)

    /**
     * Tokenize a secondary card — `POST /init` — against the amount a primary would still cover,
     * returning the [SecondaryToken] (balance/name/expiry) to hand to [applySecondary]. Validate
     * PAN/CVV with [secondaryConfig] first. Blocking API work happens via `suspend`; move it to
     * `Dispatchers.IO` at the call site.
     *
     * @throws PurseCheckoutException.NoSecondaryConfig / InvalidSecondaryCard / SecondaryTokenizationFailed
     * @throws eu.purse.core.api.PaymentApiException on network/API failure.
     */
    suspend fun getSecondaryToken(method: SecondaryMethod, pan: String, cvv: String? = null): SecondaryToken =
        SecondaryTokenizer(apiClient()).tokenize(method, pan, cvv, splitter.remaining)

    /**
     * Draw an applied [token] down against what's left. For a splittable (gift-card) token, [amount]
     * caps the draw (defaults to the most it can cover); non-splittable tokens ignore it. See
     * [PursePaymentSplitter].
     */
    fun applySecondary(token: SecondaryToken, amount: BigDecimal? = null) {
        splitter.takeSplit(token, amount)
        refresh()
    }

    fun removeSecondary(token: SecondaryToken) {
        splitter.clearSplit(token)
        refresh()
    }

    // --- interactive plugins (e.g. Google Pay) ---------------------------------------------------

    /**
     * Stash data an interactive plugin collected outside the headless flow — e.g. the Google Pay
     * token + selected network from the payment sheet — keyed to [item]. It's threaded into that
     * item's `preValidation` on the next [submitPayment] (via `PartnerUiContext.collected`).
     */
    fun setCollected(item: PaymentItem, data: Map<String, Any?>) {
        collectedByMethod[item.id] = data
    }

    /**
     * A [PartnerUiContext] for [item] against the current split — for a plugin coordinator (in an
     * interactive plugin module) that needs to drive plugin-specific steps (e.g. building the Google
     * Pay request) before submit. `amount` is what a primary would still cover.
     */
    fun pluginContext(item: PaymentItem): PartnerUiContext = PartnerUiContext(
        partner = item.partner,
        method = item.method,
        session = session.source,
        amount = splitter.remaining,
        api = apiClient(),
        collected = collectedByMethod[item.id] ?: emptyMap(),
    )

    /**
     * Re-query the session's transactions (`GET /{entityId}/sessions/{sessionId}`) — the source of
     * truth to check after a redirect/3-D Secure return rather than trusting a locally-held result.
     * Blocking; call off the main thread.
     */
    fun sessionTransactions(): JSONArray = apiClient().getSession()

    /**
     * The [GooglePayHost] for [item] — everything `:partner-ui`'s `GooglePayCoordinator` needs from
     * this checkout, without that module depending on `:headless-checkout` directly.
     */
    fun googlePayHost(item: PaymentItem): GooglePayHost = object : GooglePayHost {
        override val environment get() = params.environment
        override val redirectionReturnUrl get() = session.source.redirection
        override fun buildContext() = pluginContext(item)
        override fun setCollected(data: Map<String, Any?>) = setCollected(item, data)
        override suspend fun submitPayment() = this@PurseHeadlessCheckout.submitPayment()
    }

    /**
     * The [KlarnaHost] for [item] — everything `:partner-ui`'s `KlarnaCoordinator` needs from this
     * checkout, without that module depending on `:headless-checkout` directly. [returnUrl] falls back
     * to the session's own configured return URL (like Google Pay's redirect return) since Klarna's
     * `KlarnaPaymentView` needs one regardless of whether the session declared its own.
     */
    fun klarnaHost(item: PaymentItem, klarnaReturnUrl: String): KlarnaHost = object : KlarnaHost {
        override val returnUrl get() = klarnaReturnUrl
        override fun buildContext() = pluginContext(item)
        override fun setCollected(data: Map<String, Any?>) = setCollected(item, data)
        override suspend fun submitPayment() = this@PurseHeadlessCheckout.submitPayment()
    }

    /**
     * The [CardHost] for [item] — everything `:partner-ui`'s `CardCoordinator` needs from this
     * checkout, without that module depending on `:headless-checkout` directly. [item] (not the
     * generic [PaymentItem]) because [CardHost.supportedBrands] needs [PrimaryMethod.supportedBrands].
     */
    fun cardHost(item: PrimaryMethod): CardHost = object : CardHost {
        override val environment get() = params.environment
        override val vaultTenantId get() = session.vaultsConfiguration?.purse?.tenantId
        override val supportedBrands get() = item.supportedBrands
        override val redirectionReturnUrl get() = session.source.redirection
        override fun buildContext() = pluginContext(item)
        override fun setCollected(data: Map<String, Any?>) = setCollected(item, data)
        override suspend fun submitPayment() = this@PurseHeadlessCheckout.submitPayment()
    }

    /**
     * Create the payment — run the plugin-driven `/validate` pipeline over the current split. Must
     * be called once [isPaymentFulfilled] is `true`. Blocking network work happens off the caller's
     * dispatcher via `suspend`; move it to `Dispatchers.IO` at the call site.
     *
     * @throws PurseCheckoutException.PaymentNotFulfilled if the split doesn't cover the amount.
     * @throws PurseCheckoutException.NoPluginRegistered while no `:partner-ui` plugins exist yet.
     */
    suspend fun submitPayment(): ValidationResult {
        if (!splitter.fulfilled) throw PurseCheckoutException.PaymentNotFulfilled()
        _sessionState.value = SessionState.SUBMITTING
        return try {
            val result = PaymentValidator(apiClient()).execute(splitter.splits, session.source, collectedByMethod)
            _sessionState.value = if (result.isSuccess) SessionState.SUBMITTED else SessionState.ERROR
            result
        } catch (e: Throwable) {
            _sessionState.value = SessionState.ERROR
            throw e
        }
    }

    private fun refresh() {
        _remainingAmountToPay.value = splitter.remaining
        _isPaymentFulfilled.value = splitter.fulfilled
        _amountSplit.value = splitter.splits
        _paymentMethods.value =
            MethodAvailability.recompute(session.meansOfPayment, session.isInformationRequest, splitter.remaining)
    }

    private fun apiClient(): PaymentApiClient =
        PaymentApiClient(params.apiKey, paymentApiRoot, params.entityId, session.id)

    companion object {
        /**
         * @param widgetData the base64-encoded `widget.data` string from the merchant backend's
         * Client Session response (the "V2" params).
         * @param lockedEnvironment when non-null, overrides whatever [widgetData] declares — a
         * release build should always pass [PurseEnvironment.PRODUCTION] rather than trusting a
         * session-declared environment.
         * @throws eu.purse.core.session.PurseSessionException.SessionMalformed if [widgetData]
         * can't be decoded, or the session is missing `id` / `expiration_date`.
         */
        fun init(widgetData: String, lockedEnvironment: PurseEnvironment? = null): PurseHeadlessCheckout {
            val decoded = SessionCodec.decode(widgetData)
            val params = if (lockedEnvironment != null && lockedEnvironment != decoded.environment) {
                decoded.copy(environment = lockedEnvironment)
            } else {
                decoded
            }
            // A build-locked (release) environment trusts its own known root and ignores the
            // session-declared `api_url`; otherwise use `api_url`, falling back to the env root.
            val apiRoot = if (lockedEnvironment != null) {
                params.environment.paymentApiRoot
            } else {
                params.apiUrl.ifBlank { params.environment.paymentApiRoot }
            }
            val session = PaymentSessionBuilder.build(params.paymentSession)
            return PurseHeadlessCheckout(params, session, apiRoot)
        }
    }
}
