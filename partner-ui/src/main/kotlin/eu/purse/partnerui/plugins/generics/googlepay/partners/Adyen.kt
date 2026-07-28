package eu.purse.partnerui.plugins.generics.googlepay.partners

import eu.purse.core.api.PaymentApiException
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayConfig
import eu.purse.partnerui.plugins.generics.googlepay.optStringList
import eu.purse.partnerui.plugins.generics.googlepay.optStringOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Adyen's Google Pay gateway config — ports web's `plugins/generics/googlepay/partners/adyen.ts`.
 * `logs.payment_methods` is a JSON-string array; its first entry carries the gateway config.
 */
internal object Adyen {

    const val WIRE_VALUE = "adyen"

    fun parseConfig(logs: JSONObject): GooglePayConfig {
        val raw = logs.optStringOrNull("payment_methods")
            ?: throw PaymentApiException("googlepay (adyen) init returned no logs.payment_methods")
        val paymentMethods = try {
            JSONArray(raw).optJSONObject(0)
        } catch (e: JSONException) {
            throw PaymentApiException("googlepay (adyen) logs.payment_methods is not a JSON array: ${e.message}", e)
        } ?: throw PaymentApiException("googlepay (adyen) logs.payment_methods is an empty array")

        val configuration = paymentMethods.optJSONObject("configuration")
        return GooglePayConfig(
            gateway = WIRE_VALUE,
            gatewayMerchantId = configuration?.optStringOrNull("gatewayMerchantId"),
            merchantId = paymentMethods.optStringOrNull("merchantId"),
            merchantName = paymentMethods.optStringOrNull("gatewayMerchantId"),
            allowedCardNetworks = paymentMethods.optStringList("brands"),
        )
    }
}
