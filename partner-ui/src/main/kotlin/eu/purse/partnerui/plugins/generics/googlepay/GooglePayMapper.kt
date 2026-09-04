// Web: packages/partner-ui/src/plugins/generics/googlepay/index.ts + partners_specifics.ts
// Same: /init body, per-gateway config dispatch, isReadyToPay / PaymentDataRequest / validate JSON.
// Differs: nothing.
package eu.purse.partnerui.plugins.generics.googlepay

import eu.purse.core.api.PaymentApiException
import eu.purse.partnerui.plugins.generics.googlepay.partners.Adyen
import eu.purse.partnerui.plugins.generics.googlepay.partners.Default
import eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico.Ingenico
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the `/init` request body for a Google Pay (`googlepay`) payment, dispatches the gateway
 * config parsing to the right `partners/` builder ([Ingenico] / [Adyen] / [Default]), and builds the
 * Google Pay request/validate JSON — ports web's generic `plugins/generics/googlepay` plugin (the
 * same protocol reused across any partner offering `googlepay`, unlike `plugins/partners` which is
 * one dedicated plugin per partner).
 *
 * Pure JSON — no Play-Services types here. The gateway config (`gateway`, `gatewayMerchantId`,
 * allowed networks, merchant info) comes from the **backend's `/init` response**, not a session field.
 */
object GooglePayMapper {

    const val METHOD = "googlepay"

    private val ALLOWED_AUTH_METHODS = listOf("PAN_ONLY", "CRYPTOGRAM_3DS")
    private val DEFAULT_CARD_NETWORKS = listOf("VISA", "MASTERCARD", "AMEX")

    /**
     * Used when neither the caller nor `/init` supplies a country — matches the `'FR'` web's
     * `default.ts` hardcodes. Ingenico's `/init` does supply one, see [GooglePayConfig.countryCode].
     */
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
            Ingenico.WIRE_VALUE -> Ingenico.parseConfig(logs)
            Adyen.WIRE_VALUE -> Adyen.parseConfig(logs)
            else -> Default.parseConfig(logs)
        }
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

    /**
     * `PaymentDataRequest.fromJson` input — shows the Google Pay sheet.
     *
     * `transactionInfo.countryCode` resolves as **[countryCode] argument → [GooglePayConfig.countryCode]
     * (Ingenico's `/init`) → [DEFAULT_COUNTRY_CODE]**, so a caller can override, an Ingenico session
     * gets its own country, and everyone else keeps web's `'FR'`.
     */
    fun buildPaymentDataRequestJson(
        config: GooglePayConfig,
        currencyCode: String,
        amount: BigDecimal,
        countryCode: String? = null,
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
                    .put(
                        "countryCode",
                        countryCode?.takeIf { it.isNotBlank() }
                            ?: config.countryCode?.takeIf { it.isNotBlank() }
                            ?: DEFAULT_COUNTRY_CODE,
                    )
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
}
