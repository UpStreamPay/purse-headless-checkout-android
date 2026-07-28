package eu.purse.partnerui.plugins.partners.paypal.wallet

import eu.purse.core.api.PaymentApiException
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the `/init` request body for a PayPal **wallet** (pay-now) payment and parses its response
 * — ports web's `plugins/partners/paypal/wallet`. `/init` creates the PayPal order the buyer then
 * approves; the follow-up `/validate`, carrying the same `step_id`, captures it.
 *
 * Pure JSON + session data — no PayPal SDK types here (those live in [PaypalCoordinator] and
 * [PaypalCheckoutActivity]).
 *
 * Pay-now only: registration / vaulting (web's `createBillingAgreement` / `createVaultSetupToken`)
 * is out of scope, so `payment_preferences.registration` is always `false`.
 */
object PaypalMapper {

    const val METHOD = "wallet"

    /**
     * `/init` body — mirrors web's `ItemPayload` for paypal wallet: empty `partner_data`, a
     * `payment_data` normalized with `vault_vendor: "none"` (as web's `FetchPaymentAPI` does), and
     * `payment_preferences.registration = false`.
     */
    fun buildInitPayload(amount: BigDecimal, partnerWireValue: String): JSONObject =
        JSONObject()
            .put("partner", partnerWireValue)
            .put("method", METHOD)
            .put("amount", amount)
            .put("partner_data", JSONObject())
            .put("payment_data", JSONObject().put("vault_vendor", "none"))
            .put("payment_preferences", JSONObject().put("registration", false))

    /**
     * The PayPal order id off the `/init` response — `plugin_result.logs.order_id`, with the two
     * flatter shapes the same field has been seen in as fallbacks.
     *
     * @throws PaymentApiException if the init wasn't successful or carries no `order_id` — without
     * one there is no order for the buyer to approve, so this is a user-facing failure rather than
     * a recoverable empty result.
     */
    fun parseInit(initData: JSONObject): String {
        val status = initData.optJSONObject("status")
        val state = status?.optString("state").orEmpty()
        if (state.isNotEmpty() && state != "SUCCESS") {
            val description = status?.optString("description").orEmpty()
            throw PaymentApiException("paypal init not successful (state=$state) $description".trim())
        }

        val pluginResult = initData.optJSONObject("plugin_result")
        return pluginResult?.optJSONObject("logs")?.optStringOrNull("order_id")
            ?: pluginResult?.optStringOrNull("order_id")
            ?: initData.optStringOrNull("order_id")
            ?: throw PaymentApiException(
                "paypal init returned no order_id; response=${initData.toString().take(800)}",
            )
    }

    /**
     * The top-level `step_id` `/init` returns for this item (web's `initData.step_id`, stashed as
     * `context.dmz.initialStepId`) — threaded through to the `/validate` payload by [PaypalPlugin].
     */
    fun extractStepId(initData: JSONObject): Int? =
        if (initData.has("step_id") && !initData.isNull("step_id")) initData.optInt("step_id") else null

    /**
     * The merchant's PayPal REST client id, read off the session rather than `/init` — see
     * [PaypalConfig].
     *
     * @throws PaymentApiException when the session offers this method but carries no
     * `merchant_specifics.client_id`; the PayPal SDK cannot be configured without one, and failing
     * here names the missing field instead of surfacing an opaque SDK error at approval time.
     */
    fun parseSessionConfig(
        session: LegacyPaymentSession,
        partner: Partner,
        method: Method,
    ): PaypalConfig {
        val clientId = session.protocols[partner]
            ?.get(method)
            ?.configurations
            ?.settings
            ?.merchantClientId
            ?.takeIf(String::isNotBlank)
            ?: throw PaymentApiException(
                "paypal session carries no merchant_specifics.client_id for " +
                    "${partner.wireValue}/${method.wireValue} — cannot configure the PayPal SDK",
            )
        return PaypalConfig(clientId = clientId)
    }

    /**
     * Whether [url] is one of PayPal's own hosted approval pages — `checkoutnow` for a payment,
     * `agreements/approve` for a billing agreement. Matched on host suffix + path so it covers both
     * `www.paypal.com` and `www.sandbox.paypal.com`.
     *
     * This plugin runs PayPal's approval **natively**, through the SDK, so a `/validate` response
     * pointing back at one of these is never a new action to follow — it is the web flow's popup
     * URL, echoed back. Confirmed against a real response, which returned
     * `redirection.url = …/checkoutnow?token=<the order the buyer had just approved>` alongside
     * `global_status = SUCCESS`; following it re-opens PayPal on a completed order and books a
     * second payment step. See [PaypalCoordinator]'s `resolveOutcome`.
     */
    fun isApprovalUrl(url: String): Boolean =
        url.contains("paypal.com/checkoutnow") || url.contains("paypal.com/agreements/approve")

    /** Local null-safe string read (`:core`'s own JsonExtensions are internal to that module). */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
