package eu.purse.googlepay

import eu.purse.core.api.PaymentApiException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the `/init` request body for a Google Pay (`googlepay`) payment, parses the gateway config
 * out of its response, and builds the Google Pay request/validate JSON. Ports web's generic
 * `plugins/generics/googlepay` plugin (reused across any partner offering `googlepay`), with a
 * dedicated **ingenico** (Worldline) branch alongside `adyen` and the `default` fallback.
 *
 * Pure JSON — no Play-Services types here. The gateway config (`gateway`, `gatewayMerchantId`,
 * allowed networks, merchant info) comes from the **backend's `/init` response**, not a session field.
 */
object GooglePayMapper {

    const val METHOD = "googlepay"

    private const val PARTNER_ADYEN = "adyen"
    private const val PARTNER_INGENICO = "ingenico"

    /** Web's ingenico default gateway; refined by the Worldline product-320 lookup when reachable. */
    const val INGENICO_DEFAULT_GATEWAY = "worldlineingenicoogone"

    /** Web's ingenico gatewayMerchantId fallback when `/init` doesn't carry one. */
    private const val INGENICO_DEFAULT_MERCHANT_ID = "UPSTREAMPAY"

    private val ALLOWED_AUTH_METHODS = listOf("PAN_ONLY", "CRYPTOGRAM_3DS")
    private val DEFAULT_CARD_NETWORKS = listOf("VISA", "MASTERCARD", "AMEX")

    /** Not modeled in the session/`MethodConfiguration`; matches web's hardcoded `'FR'`. */
    private const val DEFAULT_COUNTRY_CODE = "FR"

    /** `/init` body — mirrors web's `getGooglePayConfiguration`: empty data, pay-now only. */
    fun buildInitPayload(amount: BigDecimal, partnerWireValue: String): JSONObject =
        JSONObject()
            .put("partner", partnerWireValue)
            .put("method", METHOD)
            .put("amount", amount)
            .put("partner_data", JSONObject())
            .put("payment_data", JSONObject())
            .put("payment_preferences", JSONObject())

    /**
     * Parse the `/init` response into a [GooglePayConfig], branching on the partner's log shape:
     * `ingenico` reads fields straight off `logs`; `adyen` reads a JSON-string `logs.payment_methods`
     * array; any other partner uses the generic `logs.payment_method_configuration` string.
     *
     * @throws PaymentApiException on an unsuccessful init or a missing/malformed gateway config.
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

        return when (partnerWireValue) {
            PARTNER_INGENICO -> parseIngenicoConfig(logs)
            PARTNER_ADYEN -> parseAdyenConfig(logs)
            else -> parseDefaultConfig(logs)
        }
    }

    /**
     * Ingenico (Worldline): the gateway config is spread directly across `logs` (no nested JSON
     * string). Networks aren't provided here — they default to `[MASTERCARD, VISA]` and are refined
     * by [WorldlineProductClient]. Gateway defaults to [INGENICO_DEFAULT_GATEWAY].
     */
    private fun parseIngenicoConfig(logs: JSONObject): GooglePayConfig =
        GooglePayConfig(
            gateway = INGENICO_DEFAULT_GATEWAY,
            gatewayMerchantId = logs.optStringOrNull("gatewayMerchantId") ?: INGENICO_DEFAULT_MERCHANT_ID,
            merchantId = logs.optStringOrNull("merchantId"),
            merchantName = logs.optStringOrNull("merchantName"),
            allowedCardNetworks = listOf("MASTERCARD", "VISA"),
        )

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

    /** The `allowedPaymentMethods` JSON for `PayButton.initialize` — chrome only (no tokenization). */
    fun buttonAllowedPaymentMethodsJson(): JSONArray =
        JSONArray().put(
            cardPaymentMethodJson(
                GooglePayConfig("", null, null, null, emptyList()),
                tokenized = false,
            ),
        )

    /** `PaymentDataRequest.fromJson` input — shows the Google Pay sheet. */
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
     * `partner_data` for the `/validate` item — the Google Pay token wrapped as a JSON string under
     * `session` (ports web's `getIngenicoSession` / `getAdyenSession`).
     */
    fun buildValidatePartnerData(googlePayToken: String): JSONObject {
        val session = JSONObject()
            .put("paymentMethod", JSONObject().put("type", METHOD).put("googlePayToken", googlePayToken))
        return JSONObject().put("session", session.toString())
    }

    /** `payment_data` for the `/validate` item — the card network the shopper picked in the sheet. */
    fun buildValidatePaymentData(selectedNetwork: String?): JSONObject {
        val card = JSONObject()
        if (!selectedNetwork.isNullOrBlank()) card.put("selected_network", selectedNetwork)
        return JSONObject().put("card", card)
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONObject.optStringList(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it, null) }
    }
}
