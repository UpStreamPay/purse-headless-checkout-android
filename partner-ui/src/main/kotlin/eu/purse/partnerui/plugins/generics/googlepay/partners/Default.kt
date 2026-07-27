package eu.purse.partnerui.plugins.generics.googlepay.partners

import eu.purse.core.api.PaymentApiException
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayConfig
import eu.purse.partnerui.plugins.generics.googlepay.optStringList
import eu.purse.partnerui.plugins.generics.googlepay.optStringOrNull
import org.json.JSONException
import org.json.JSONObject

/**
 * The generic gateway shape any partner beyond Ingenico/Adyen uses — ports web's
 * `plugins/generics/googlepay/partners/default.ts`. `logs.payment_method_configuration` is a
 * JSON-string object.
 */
internal object Default {

    fun parseConfig(logs: JSONObject): GooglePayConfig {
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
}
