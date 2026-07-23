package eu.purse.checkout

import eu.purse.checkout.payment.item.MethodAvailability
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.item.SecondaryMethod
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Choose (or clear, with `null`) the primary method that covers the remaining amount. */
    fun setPrimary(method: PrimaryMethod?) {
        splitter.setPrimary(method)
        refresh()
    }

    /** Draw a secondary instrument's [balance] down against what's left (see [PursePaymentSplitter]). */
    fun takeSecondary(method: SecondaryMethod, balance: BigDecimal) {
        splitter.takeSplit(method, balance)
        refresh()
    }

    fun clearSecondary(method: SecondaryMethod) {
        splitter.clearSplit(method)
        refresh()
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
            val result = PaymentValidator(apiClient()).execute(splitter.splits, session.source)
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
