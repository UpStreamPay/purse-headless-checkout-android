package eu.purse.dropin.partnerui.generic.googlepay

import eu.purse.dropin.core.api.PaymentApiException
import eu.purse.dropin.partnerui.redirect.RedirectionResolver
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the `/init` and `/validate` request bodies for a Google Pay (`googlepay`) payment and
 * parses their responses. Ports usp-widget `partner-ui`'s **generic** `plugins/generics/googlepay`
 * plugin (reused across any partner that offers `googlepay` — Adyen today, per
 * `partners/adyen.ts`, or another gateway via the `partners/default.ts` fallback) — there is no
 * `adyen-googlepay`-specific plugin on web either, see docs/partners/adyen.md.
 *
 * The flow, mirroring [eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalWalletMapper]'s
 * shape:
 *  1. [buildInitPayload] / [parseInitConfig] — `/init` returns the gateway config
 *     (`gatewayMerchantId`, allowed card networks, …) needed to build Google's
 *     `PaymentDataRequest` — the Google Pay analogue of PayPal's `order_id`: this comes from the
 *     **backend**, not a session field.
 *  2. [buildIsReadyToPayJson] / [buildPaymentDataRequestJson] — the JSON handed to
 *     `IsReadyToPayRequest.fromJson` / `PaymentDataRequest.fromJson` to run Google's own
 *     `PaymentsClient` (no Purse-specific SDK here).
 *  3. [buildValidatePayload] / [parseValidation] — `/validate`, carrying the Google Pay token
 *     (`PaymentData.tokenizationData.token`) and selected card network.
 */
object GooglePayMapper {

    const val METHOD = "googlepay"

    /** The only partner with a dedicated (non-`default`) config shape today — see class doc. */
    private const val PARTNER_ADYEN = "adyen"

    private val ALLOWED_AUTH_METHODS = listOf("PAN_ONLY", "CRYPTOGRAM_3DS")

    /** Not modeled anywhere in the session/`MethodConfiguration` — see [buildPaymentDataRequestJson]. */
    private const val DEFAULT_COUNTRY_CODE = "FR"

    /**
     * `/init` body — mirrors web's `getGooglePayConfiguration`: empty `partner_data`/`payment_data`
     * and no registration preference (Google Pay pay-now only, same as PayPal wallet).
     */
    fun buildInitPayload(amount: BigDecimal, partnerWireValue: String): JSONObject =
        JSONObject()
            .put("partner", partnerWireValue)
            .put("method", METHOD)
            .put("amount", amount)
            .put("partner_data", JSONObject())
            .put("payment_data", JSONObject())
            .put("payment_preferences", JSONObject())

    /**
     * Parses the `/init` response into a [GooglePayConfig]. For [PARTNER_ADYEN], reads the
     * Adyen-specific `plugin_result.logs.payment_methods` (itself a JSON-encoded string carrying
     * an array — ports `partners/adyen.ts`); any other partner falls back to the generic
     * `logs.payment_method_configuration` shape (ports `partners/default.ts`).
     *
     * @throws PaymentApiException if the init wasn't a success, or the expected gateway config is
     * missing/malformed — matching [eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalWalletMapper.parseInit].
     */
    fun parseInitConfig(initData: JSONObject, partnerWireValue: String): GooglePayConfig {
        val status = initData.optJSONObject("status")
        val state = status?.optString("state").orEmpty()
        if (state.isNotEmpty() && state != "SUCCESS") {
            val description = status?.optString("description").orEmpty()
            throw PaymentApiException("googlepay init not successful (state=$state) $description".trim())
        }

        val logs = initData.optJSONObject("plugin_result")?.optJSONObject("logs")
            ?: throw PaymentApiException(
                "googlepay init returned no plugin_result.logs; response=${initData.toString().take(800)}",
            )

        return if (partnerWireValue == PARTNER_ADYEN) parseAdyenConfig(logs) else parseDefaultConfig(logs)
    }

    private fun parseAdyenConfig(logs: JSONObject): GooglePayConfig {
        val raw = logs.optStringOrNull("payment_methods")
            ?: throw PaymentApiException("googlepay (adyen) init returned no logs.payment_methods")
        val paymentMethods = try {
            JSONArray(raw).optJSONObject(0)
        } catch (e: JSONException) {
            throw PaymentApiException("googlepay (adyen) logs.payment_methods is not a JSON array: ${e.message}", e)
        } ?: throw PaymentApiException("googlepay (adyen) logs.payment_methods is an empty array")

        val configuration = paymentMethods.optJSONObject("configuration")
        return GooglePayConfig(
            gateway = PARTNER_ADYEN,
            gatewayMerchantId = configuration?.optStringOrNull("gatewayMerchantId"),
            merchantId = paymentMethods.optStringOrNull("merchantId"),
            // Mirrors web's partners/adyen.ts merchantInfo.merchantName, which reads the
            // top-level gatewayMerchantId field (distinct from configuration.gatewayMerchantId
            // above) rather than a dedicated merchant-name field.
            merchantName = paymentMethods.optStringOrNull("gatewayMerchantId"),
            allowedCardNetworks = paymentMethods.optStringList("brands"),
        )
    }

    private fun parseDefaultConfig(logs: JSONObject): GooglePayConfig {
        val raw = logs.optStringOrNull("payment_method_configuration")
            ?: throw PaymentApiException("googlepay init returned no logs.payment_method_configuration")
        val payment = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw PaymentApiException("googlepay logs.payment_method_configuration is not valid JSON: ${e.message}", e)
        }

        val configuration = payment.optJSONObject("configuration")
        return GooglePayConfig(
            gateway = configuration?.optStringOrNull("gateway").orEmpty(),
            gatewayMerchantId = configuration?.optStringOrNull("gatewayMerchantId"),
            merchantId = configuration?.optStringOrNull("merchantId"),
            merchantName = configuration?.optStringOrNull("merchantName"),
            allowedCardNetworks = payment.optStringList("brands"),
        )
    }

    /** `IsReadyToPayRequest.fromJson` input — checked before showing the Google Pay button. */
    fun buildIsReadyToPayJson(config: GooglePayConfig): JSONObject =
        JSONObject()
            .put("apiVersion", 2)
            .put("apiVersionMinor", 0)
            .put("allowedPaymentMethods", JSONArray().put(cardPaymentMethodJson(config, tokenized = false)))

    /**
     * The `allowedPaymentMethods` JSON for `PayButton.initialize` — chrome only (no gateway/
     * tokenization data), since the button renders before any per-session `/init` has run. Uses
     * the same [DEFAULT_CARD_NETWORKS] fallback [buildPaymentDataRequestJson] falls back to.
     */
    fun buttonAllowedPaymentMethodsJson(): JSONArray =
        JSONArray().put(
            cardPaymentMethodJson(
                GooglePayConfig(
                    gateway = "",
                    gatewayMerchantId = null,
                    merchantId = null,
                    merchantName = null,
                    allowedCardNetworks = emptyList(),
                ),
                tokenized = false,
            ),
        )

    /**
     * `PaymentDataRequest.fromJson` input — shows the Google Pay sheet.
     *
     * @param countryCode not modeled anywhere in the session/`MethodConfiguration` today; defaults
     * to [DEFAULT_COUNTRY_CODE], matching web's `partners/default.ts` hardcoded `'FR'` (the Adyen
     * variant instead reuses the UI locale for this field, which this port doesn't replicate — see
     * class doc).
     */
    fun buildPaymentDataRequestJson(
        config: GooglePayConfig,
        currencyCode: String,
        amount: BigDecimal,
        countryCode: String = DEFAULT_COUNTRY_CODE,
    ): JSONObject {
        val merchantInfo = JSONObject()
        config.merchantId?.let { merchantInfo.put("merchantId", it) }
        config.merchantName?.let { merchantInfo.put("merchantName", it) }

        return JSONObject()
            .put("apiVersion", 2)
            .put("apiVersionMinor", 0)
            .put("allowedPaymentMethods", JSONArray().put(cardPaymentMethodJson(config, tokenized = true)))
            .put(
                "transactionInfo",
                JSONObject()
                    .put("countryCode", countryCode)
                    .put("currencyCode", currencyCode)
                    .put("totalPrice", amount.toPlainString())
                    .put("totalPriceStatus", "FINAL"),
            )
            .put("merchantInfo", merchantInfo)
    }

    private fun cardPaymentMethodJson(config: GooglePayConfig, tokenized: Boolean): JSONObject {
        val networks = config.allowedCardNetworks.ifEmpty { DEFAULT_CARD_NETWORKS }
        val parameters = JSONObject()
            .put("allowedAuthMethods", JSONArray(ALLOWED_AUTH_METHODS))
            .put("allowedCardNetworks", JSONArray(networks))
        val method = JSONObject().put("type", "CARD").put("parameters", parameters)
        if (tokenized) {
            method.put(
                "tokenizationSpecification",
                JSONObject()
                    .put("type", "PAYMENT_GATEWAY")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("gateway", config.gateway)
                            .put("gatewayMerchantId", config.gatewayMerchantId),
                    ),
            )
        }
        return method
    }

    /**
     * `/validate` body — a single-item JSON *array*, mirroring
     * [eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalWalletMapper.buildValidatePayload].
     * `partner_data.session` carries the Google Pay token, JSON-encoded as a string (ports web's
     * `getAdyenSession`/`getDefaultSession`); `payment_data.card.selected_network` carries the
     * card network the shopper picked in the sheet (`PaymentData.paymentMethodData.info.cardNetwork`).
     */
    fun buildValidatePayload(
        amount: BigDecimal,
        partnerWireValue: String,
        googlePayToken: String,
        selectedNetwork: String?,
    ): JSONArray {
        val session = JSONObject()
            .put("paymentMethod", JSONObject().put("type", METHOD).put("googlePayToken", googlePayToken))
        val card = JSONObject()
        if (!selectedNetwork.isNullOrBlank()) card.put("selected_network", selectedNetwork)

        val item = JSONObject()
            .put("partner", partnerWireValue)
            .put("method", METHOD)
            .put("amount", amount)
            .put("order", JSONObject())
            .put("partner_data", JSONObject().put("session", session.toString()))
            .put("payment_data", JSONObject().put("card", card))
            .put("payment_preferences", JSONObject())
            .put("step_id", JSONObject.NULL)
        return JSONArray().put(item)
    }

    /**
     * Parses the `/validate` response `{results, redirection, global_status}` into a
     * [GooglePayValidationResult]. [partnerWireValue] is needed to find this method's own entry in
     * `results` (see [eu.purse.dropin.partnerui.redirect.RedirectionResolver]) — Google Pay, unlike
     * PayPal/CB, doesn't have one fixed partner.
     */
    fun parseValidation(data: JSONObject, partnerWireValue: String): GooglePayValidationResult {
        val globalStatus = data.optString("global_status")
        return GooglePayValidationResult(
            isSuccess = globalStatus.equals("SUCCESS", ignoreCase = true),
            globalStatus = globalStatus,
            redirectAction = RedirectionResolver.resolve(data, partnerWireValue, METHOD, sessionFallbackUrl = null),
            rawResponse = data.toString(2),
        )
    }

    private val DEFAULT_CARD_NETWORKS = listOf("VISA", "MASTERCARD", "AMEX")

    /** Local null-safe string read (the `:dropin` JsonExtensions aren't visible from here). */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONObject.optStringList(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it, null) }
    }
}
