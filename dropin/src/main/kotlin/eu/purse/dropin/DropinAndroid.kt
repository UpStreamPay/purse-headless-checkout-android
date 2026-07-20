package eu.purse.dropin

import eu.purse.dropin.payment.method.secondary.SecondaryTokenMapper
import eu.purse.dropin.core.api.PaymentApiClient
import eu.purse.dropin.core.environment.PurseEnvironment
import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.session.PaymentSession
import eu.purse.dropin.session.PurseSessionParams
import eu.purse.dropin.payment.method.secondary.SecondaryToken
import eu.purse.dropin.session.PaymentSessionBuilder
import eu.purse.dropin.session.SessionCodec

/**
 * Public entry point — decodes a Purse session and exposes its available payment methods.
 * See docs/README.md for the overall architecture this implements.
 */
class DropinAndroid private constructor(
    val sessionParams: PurseSessionParams,
    val session: PaymentSession,
) {

    /** Secondaries (e.g. Illicado) first, then primaries (CB, Google Pay, PayPal) — see
     * docs/architecture/session-and-protocols.md#turning-protocols-into-a-method-list. */
    val paymentMethods: List<DropinPaymentMethod> get() = session.meansOfPayment

    val isInformationRequest: Boolean get() = session.isInformationRequest

    /**
     * Tenant id for `SecureFieldsAndroid.init()` / `rememberSecureFields()`, when this session
     * has a `purse`-vendor vault-backed card method. `null` if the session carries no Purse
     * vault config at all — see
     * docs/architecture/session-and-protocols.md#session-level-vault-config--where-tenantid-actually-comes-from.
     */
    val vaultTenantId: String? get() = session.vaultsConfiguration?.purse?.tenantId

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
        val client = PaymentApiClient(
            apiKey = sessionParams.apiKey,
            apiRoot = sessionParams.environment.paymentApiRoot,
            entityId = sessionParams.entityId,
            sessionId = session.id,
        )
        val payload = SecondaryTokenMapper.buildInitPayload(method, pan, cvv, amount = session.amount)
        return SecondaryTokenMapper.parse(client.init(payload))
    }

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
            val session = PaymentSessionBuilder.build(sessionParams.paymentSession)
            return DropinAndroid(sessionParams, session)
        }
    }
}
