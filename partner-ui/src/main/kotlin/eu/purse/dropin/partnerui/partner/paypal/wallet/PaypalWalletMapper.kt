package eu.purse.dropin.partnerui.partner.paypal.wallet

import eu.purse.dropin.core.api.PaymentApiException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the `/init` and `/validate` request bodies for a PayPal **wallet** (pay-now) payment and
 * parses their responses. Ports usp-widget `partner-ui`'s `partners/paypal/wallet` plugin: `/init`
 * creates the PayPal order (returning `order_id`), and `/validate` — carrying the init's `step_id`
 * — captures it once the buyer has approved.
 *
 * Pay-now only: registration / vaulting (`createBillingAgreement` / `createVaultSetupToken`) is out
 * of scope, so `payment_preferences.registration` is always `false`.
 */
object PaypalWalletMapper {

    const val PARTNER = "paypal"
    const val METHOD = "wallet"

    /**
     * `/init` body — mirrors web's `ItemPayload` for paypal wallet: empty `partner_data` /
     * `payment_data` (normalized with `vault_vendor: "none"`, as web's `FetchPaymentAPI` does) and
     * `payment_preferences.registration = false`.
     */
    fun buildInitPayload(amount: BigDecimal): JSONObject =
        JSONObject()
            .put("partner", PARTNER)
            .put("method", METHOD)
            .put("amount", amount)
            .put("partner_data", JSONObject())
            .put("payment_data", JSONObject().put("vault_vendor", "none"))
            .put("payment_preferences", JSONObject().put("registration", false))

    /**
     * Parses the `/init` response into a [WalletOrder] (`plugin_result.logs.order_id` + top-level
     * `step_id`).
     *
     * @throws PaymentApiException if the init wasn't a success or carries no `order_id` — matching
     * the secondary flow, which surfaces those as user-facing errors rather than a usable order.
     */
    fun parseInit(initData: JSONObject): WalletOrder {
        val status = initData.optJSONObject("status")
        val state = status?.optString("state").orEmpty()
        if (state.isNotEmpty() && state != "SUCCESS") {
            val description = status?.optString("description").orEmpty()
            throw PaymentApiException("paypal init not successful (state=$state) $description".trim())
        }

        val pluginResult = initData.optJSONObject("plugin_result")
        val orderId = pluginResult?.optJSONObject("logs")?.optStringOrNull("order_id")
            ?: pluginResult?.optStringOrNull("order_id")
            ?: initData.optStringOrNull("order_id")
            ?: throw PaymentApiException(
                "paypal init returned no order_id; response=${initData.toString().take(800)}",
            )

        return WalletOrder(orderId = orderId, stepId = initData.optNumberOrStringOrNull("step_id"))
    }

    /**
     * `/validate` body — a single-item JSON *array* (validate takes an array of items on the wire),
     * carrying the [stepId] from [parseInit] so the backend links the capture to that init.
     */
    fun buildValidatePayload(amount: BigDecimal, stepId: String?): JSONArray {
        val item = JSONObject()
            .put("partner", PARTNER)
            .put("method", METHOD)
            .put("amount", amount)
            .put("order", JSONObject())
            .put("partner_data", JSONObject())
            .put("payment_data", JSONObject())
            .put("payment_preferences", JSONObject())
        // step_id is a number on the wire; send it as one when it parses, else as-is, else null.
        when {
            stepId == null -> item.put("step_id", JSONObject.NULL)
            stepId.toLongOrNull() != null -> item.put("step_id", stepId.toLong())
            else -> item.put("step_id", stepId)
        }
        return JSONArray().put(item)
    }

    /**
     * Parses the `/validate` response `{results, redirection, global_status}` into a
     * [WalletValidationResult]. Success is `global_status == "SUCCESS"` (case-insensitive); a
     * chained-redirect `redirection.url`, when present, is surfaced for the caller to follow.
     */
    fun parseValidation(data: JSONObject): WalletValidationResult {
        val globalStatus = data.optString("global_status")
        val redirectionUrl = data.optJSONObject("redirection")?.optStringOrNull("url")
        return WalletValidationResult(
            isSuccess = globalStatus.equals("SUCCESS", ignoreCase = true),
            globalStatus = globalStatus,
            redirectionUrl = redirectionUrl,
        )
    }

    /** Local null-safe string read (the `:dropin` JsonExtensions aren't visible from here). */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    /** Reads a field that may be a JSON string OR number (e.g. `step_id`) as its string form. */
    private fun JSONObject.optNumberOrStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) get(name).toString() else null
}
