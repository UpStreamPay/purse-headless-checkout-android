// Web: packages/partner-ui/src/plugins/partners/klarna-installments (`getKlarnaConfigFromInitData`)
// Same: /init body, client_token + step_id parsing.
// Differs: generic `klarna` payment-method category lets the token decide.
package eu.purse.partnerui.plugins.partners.klarna

import eu.purse.core.api.PaymentApiException
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the `/init` request body for a Klarna (`installments`) payment and parses its response —
 * ports web's dedicated `plugins/partners/klarna-installments` plugin (one plugin for the `klarna`
 * partner, unlike Google Pay's `plugins/generics/googlepay` which is reused across gateways).
 *
 * Pure JSON — no Klarna SDK types here. See
 * https://docs.klarna.com/acquirer/klarna/mobile-payments/integrate-with-mobile-sdk/android/native-view/
 * for the native SDK contract this feeds.
 */
object KlarnaMapper {

    const val METHOD = "installments"

    /** `KlarnaPaymentView`'s `category` — the only category this plugin renders (web's `'klarna'`). */
    const val PAYMENT_METHOD_CATEGORY = "klarna"

    /**
     * `/init` body — mirrors web's `initKlarnaSDK`, minus the browser fingerprint web collects for
     * its own fraud signals (`partner_data.browser`), which has no native equivalent here.
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
     * Parse the `/init` response into a [KlarnaConfig] — just the `klarna_client_token` off
     * `plugin_result.logs`.
     *
     * @throws PaymentApiException on an unsuccessful init or a missing client token.
     */
    fun parseInitConfig(initData: JSONObject): KlarnaConfig {
        val status = initData.optJSONObject("status")
        val state = status?.optString("state").orEmpty()
        if (state.isNotEmpty() && state != "SUCCESS") {
            val description = status?.optString("description").orEmpty()
            throw PaymentApiException("klarna init not successful (state=$state) $description".trim())
        }

        val logs = initData.optJSONObject("plugin_result")?.optJSONObject("logs")
            ?: throw PaymentApiException(
                "klarna init returned no plugin_result.logs; response=${initData.toString().take(800)}",
            )

        val clientToken = logs.optString("klarna_client_token").takeIf(String::isNotBlank)
            ?: throw PaymentApiException("klarna init returned no klarna_client_token in logs")

        return KlarnaConfig(clientToken = clientToken)
    }

    /**
     * The top-level `step_id` `/init` returns for this item (web's `initData.step_id`, stashed as
     * `context.dmz.initialStepId`) — threaded through to the `/validate` payload by [KlarnaPlugin].
     */
    fun extractStepId(initData: JSONObject): Int? =
        if (initData.has("step_id") && !initData.isNull("step_id")) initData.optInt("step_id") else null
}
