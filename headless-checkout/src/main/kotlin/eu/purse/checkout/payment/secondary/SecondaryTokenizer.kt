package eu.purse.checkout.payment.secondary

import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payload.ItemPayload
import java.math.BigDecimal

/**
 * Tokenizes a secondary card against the Payment API `/init` — the Kotlin analog of web's
 * `buildSecondaryPaymentToken`. Validates PAN/CVV client-side (via the method's [SecondaryConfig]),
 * builds the init payload, calls `/init`, and parses the response into a [SecondaryToken] carrying
 * the balance.
 *
 * Blocking (the API client is) — call it off the main thread; [eu.purse.checkout.PurseHeadlessCheckout]
 * exposes it through a `suspend` function.
 */
class SecondaryTokenizer(private val api: PaymentApiClient) {

    /**
     * @param amount the amount a primary would still cover, in major currency units — web passes the
     * splitter's `primaryReach` here.
     * @throws PurseCheckoutException.NoSecondaryConfig if the method has no registered config.
     * @throws PurseCheckoutException.InvalidSecondaryCard on a PAN/CVV that fails client validation.
     * @throws PurseCheckoutException.SecondaryTokenizationFailed on an unsuccessful init / no balance.
     * @throws eu.purse.core.api.PaymentApiException on network/API failure.
     */
    fun tokenize(method: SecondaryMethod, pan: String, cvv: String?, amount: BigDecimal): SecondaryToken {
        val config = SecondaryConfigs.forMethod(method)
            ?: throw PurseCheckoutException.NoSecondaryConfig("no secondary config for ${method.id}")

        if (!config.validatePan(pan)) {
            throw PurseCheckoutException.InvalidSecondaryCard("invalid card number for ${method.id}")
        }
        if (config.requiresCvv(pan) && cvv.isNullOrBlank()) {
            throw PurseCheckoutException.InvalidSecondaryCard(
                "a security code is required for ${method.id}",
                PurseErrorCode.SECONDARY_METHOD_REQUIRED_CVV,
            )
        }
        if (config.requiresCvv(pan) && !config.validateCvv(cvv!!)) {
            throw PurseCheckoutException.InvalidSecondaryCard(
                "invalid security code for ${method.id}",
                PurseErrorCode.SECONDARY_METHOD_WRONG_CVV_FORMAT,
            )
        }

        val payload = ItemPayload(
            partner = method.partner,
            method = method.method,
            amount = amount,
            paymentData = config.buildInitPaymentData(pan, cvv),
        )
        return config.parseToken(method, api.init(payload.toJson()), pan, cvv)
    }
}
