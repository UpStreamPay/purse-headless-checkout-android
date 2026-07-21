package eu.purse.dropin

import eu.purse.dropin.payment.method.secondary.SecondaryTokenMapper
import eu.purse.dropin.core.api.PaymentApiClient
import eu.purse.dropin.core.environment.PurseEnvironment
import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.method.MethodAvailability
import java.math.BigDecimal
import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.session.PaymentSession
import eu.purse.dropin.session.PurseSessionParams
import eu.purse.dropin.payment.method.secondary.SecondaryToken
import eu.purse.dropin.session.PaymentSessionBuilder
import eu.purse.dropin.session.SessionCodec
import eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalWalletMapper
import eu.purse.dropin.partnerui.partner.paypal.wallet.WalletOrder
import eu.purse.dropin.partnerui.partner.paypal.wallet.WalletValidationResult

/**
 * Public entry point — decodes a Purse session and exposes its available payment methods.
 * See docs/README.md for the overall architecture this implements.
 */
class DropinAndroid private constructor(
    val sessionParams: PurseSessionParams,
    val session: PaymentSession,
    /** Resolved Payment API root (session `api_url`, or the locked environment's known root).
     * Calls go to `{paymentApiRoot}/{entityId}/sessions/{sessionId}/init|validate`. */
    val paymentApiRoot: String,
) {

    /** Secondaries (e.g. Illicado) first, then primaries (CB, Google Pay, PayPal) — see
     * docs/architecture/session-and-protocols.md#turning-protocols-into-a-method-list. */
    val paymentMethods: List<DropinPaymentMethod> get() = session.meansOfPayment

    val isInformationRequest: Boolean get() = session.isInformationRequest

    /**
     * [paymentMethods] with their disabled states recomputed against [primaryReach] — the amount a
     * primary would still have to pay once applied secondaries are subtracted (pass
     * [eu.purse.dropin.payment.split.PaymentSplitter.remaining]). Mirrors web re-running
     * `updateMopDisabledState` whenever the splitter's `primaryReach` changes: the Drop-in UI calls
     * this after every applied secondary to re-evaluate which methods are still selectable (a
     * primary below its `payment_min_amount`, or every method once the amount is fully covered).
     *
     * Amounts are in major currency units, the same convention as the session amounts.
     */
    fun methodsForPrimaryReach(primaryReach: BigDecimal): List<DropinPaymentMethod> =
        MethodAvailability.recompute(session.meansOfPayment, session.isInformationRequest, primaryReach)

    /**
     * Tenant id for `SecureFieldsAndroid.init()` / `rememberSecureFields()`, when this session
     * has a `purse`-vendor vault-backed card method. `null` if the session carries no Purse
     * vault config at all — see
     * docs/architecture/session-and-protocols.md#session-level-vault-config--where-tenantid-actually-comes-from.
     */
    val vaultTenantId: String? get() = session.vaultsConfiguration?.purse?.tenantId

    /**
     * The PayPal REST client id for this session's `paypal`/`wallet` method
     * (`merchant_specifics.client_id`), needed to launch the PayPal SDK. `null` when the session
     * carries no PayPal wallet method or no client id.
     */
    val paypalClientId: String?
        get() = session.meansOfPayment
            .firstOrNull { it.partner == Partner.Paypal && it.method == Method.Wallet }
            ?.configuration?.settings?.merchantClientId

    /**
     * Tokenize a secondary (co-payment) card — e.g. an Illicado gift card — by calling the Payment
     * API `/init`, and return the resulting [SecondaryToken] (balance/name/expiry). Mirrors web's
     * `method.getSecondaryToken(pan, cvv)`.
     *
     * Validate [pan]/[cvv] with [eu.purse.dropin.payment.method.secondary.SecondaryConfig] before calling. This is a
     * blocking network call — invoke it off the main thread.
     *
     * @param cvv required by some partners (see `SecondaryConfig.requiresCvv`); pass `null` when not.
     * @throws eu.purse.dropin.core.api.PaymentApiException on network/API failure, an unsuccessful init,
     * or a card with no balance.
     */
    fun getSecondaryToken(method: DropinPaymentMethod, pan: String, cvv: String?): SecondaryToken {
        val payload = SecondaryTokenMapper.buildInitPayload(method, pan, cvv, amount = session.amount)
        return SecondaryTokenMapper.parse(paymentApiClient().init(payload))
    }

    /**
     * Create the PayPal order for this session — `POST /init` for `paypal`/`wallet` — returning the
     * [WalletOrder] (order id + step id) the shopper must approve. Mirrors web's paypal wallet
     * `createOrder`. Blocking network call — invoke off the main thread.
     *
     * @throws eu.purse.dropin.core.api.PaymentApiException on network/API failure or an init with no order.
     */
    fun createPaypalOrder(): WalletOrder =
        PaypalWalletMapper.parseInit(paymentApiClient().init(PaypalWalletMapper.buildInitPayload(session.amount)))

    /**
     * Capture the approved PayPal order — `POST /validate` for `paypal`/`wallet`, carrying the
     * [stepId] from [createPaypalOrder]. Returns the [WalletValidationResult] (success + any chained
     * redirect). Blocking — invoke off the main thread.
     *
     * @throws eu.purse.dropin.core.api.PaymentApiException on network/API failure.
     */
    fun validatePaypal(stepId: String?): WalletValidationResult =
        PaypalWalletMapper.parseValidation(
            paymentApiClient().validate(PaypalWalletMapper.buildValidatePayload(session.amount, stepId)),
        )

    /** A Payment API client scoped to this session (shared by the secondary + PayPal flows). */
    private fun paymentApiClient(): PaymentApiClient = PaymentApiClient(
        apiKey = sessionParams.apiKey,
        apiRoot = paymentApiRoot,
        entityId = sessionParams.entityId,
        sessionId = session.id,
    )

    companion object {
        /**
         * @param widgetData The base64-encoded `widget.data` string from the merchant backend's
         * Client Session response.
         * @param lockedEnvironment When non-null, overrides whatever environment [widgetData]
         * itself declares — mirrors web's build-locked environment behavior. A release build
         * should always pass [PurseEnvironment.PRODUCTION] here rather than trusting a
         * session-declared environment; see
         * docs/architecture/session-and-protocols.md#validation-rules.
         * @throws eu.purse.dropin.session.DropinSessionException.SessionMalformed if [widgetData]
         * can't be decoded, or the session is missing `id`/`expiration_date`.
         */
        fun init(widgetData: String, lockedEnvironment: PurseEnvironment? = null): DropinAndroid {
            val decoded = SessionCodec.decode(widgetData)
            val sessionParams = if (lockedEnvironment != null && lockedEnvironment != decoded.environment) {
                decoded.copy(environment = lockedEnvironment)
            } else {
                decoded
            }
            // Payment API root: a build-locked (release) environment trusts its own known root and
            // ignores the session-declared `api_url` — mirroring web's `checkAndEnforceEnvironment`.
            // Otherwise use the session-provided `api_url` (what the merchant backend actually
            // minted the session against), falling back to the environment's known root if absent.
            val apiRoot = if (lockedEnvironment != null) {
                sessionParams.environment.paymentApiRoot
            } else {
                sessionParams.apiUrl.ifBlank { sessionParams.environment.paymentApiRoot }
            }
            val session = PaymentSessionBuilder.build(sessionParams.paymentSession)
            return DropinAndroid(sessionParams, session, apiRoot)
        }
    }
}
