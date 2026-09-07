// Web: packages/headless-checkout `buildSecondaryPaymentToken` + secondaries/init-mappers.ts
// Same: POST /init, parse, optional poll, classify a refusal.
// Differs: nothing.
package eu.purse.checkout.payment.secondary

import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.payment.item.SecondaryMethod
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payload.ItemPayload
import eu.purse.core.payment.resolvedSaveTokenMode
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
        ).toJson()
        val initResponse = api.init(payload)
        // Two-phase partners (web's `pollForToken`): a SUCCESS that only says PROCESSING is polled
        // through the config until the real token lands.
        val finalResponse = if (config.shouldPoll(initResponse)) config.pollForToken(api, initResponse, payload) else initResponse
        if (!isSuccessfulInit(finalResponse)) {
            throw config.classifyInitError(finalResponse) ?: PurseCheckoutException.SecondaryTokenizationFailed(
                "secondary init not successful (state=${initState(finalResponse)})",
            )
        }
        // Temporary tokens carry their method's save-token policy so the shopper can ask to keep the
        // card (web's `buildTemporarySecondaryPaymentToken`: `saveTokenMode` / `canBeRegistered`).
        return config.parseToken(method, finalResponse, pan, cvv)
            .copy(saveTokenMode = method.configuration.settings.resolvedSaveTokenMode())
    }

    private fun initState(response: org.json.JSONObject): String =
        response.optJSONObject("status")?.optString("state").orEmpty()

    private fun isSuccessfulInit(response: org.json.JSONObject): Boolean = initState(response) == "SUCCESS"
}
