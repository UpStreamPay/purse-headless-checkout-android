// Web: packages/model `ItemPayload` / `ValidateItemPayload`
// Same: same wire shape (`partner_data`, `payment_data`, `payment_preferences`, `step_id`).
// Differs: nothing.
package eu.purse.core.payload

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import java.math.BigDecimal
import org.json.JSONObject

/**
 * A single item in a Payment API `/init` or `/validate` request — the typed Kotlin analog of web's
 * `ItemPayload` (`packages/sdk/src/API/types.ts`). The stable envelope (`partner`, `method`,
 * `amount`, `step_id`) is modelled; the partner-specific bodies (`payment_data`, `partner_data`,
 * `order`, `payment_preferences`) stay as free-form [JSONObject] since only a plugin knows their
 * shape — JSON stays "at the edges". A [PartnerUiPlugin]'s `preValidation` builds one of these.
 */
data class ItemPayload(
    val partner: Partner,
    val method: Method,
    val amount: BigDecimal,
    val stepId: Int? = null,
    val paymentData: JSONObject = JSONObject(),
    val partnerData: JSONObject = JSONObject(),
    val order: JSONObject = JSONObject(),
    val paymentPreferences: JSONObject = JSONObject(),
) {
    /**
     * Serializes to the wire shape. `payment_data.vault_vendor` defaults to `"none"` when the
     * plugin didn't set it — mirroring web's `normalizePaymentData`.
     */
    fun toJson(): JSONObject {
        val normalizedPaymentData = paymentData
        if (!normalizedPaymentData.has("vault_vendor")) {
            normalizedPaymentData.put("vault_vendor", "none")
        }
        return JSONObject()
            .put("partner", partner.wireValue)
            .put("method", method.wireValue)
            .put("amount", amount)
            .also { if (stepId != null) it.put("step_id", stepId) }
            .put("payment_data", normalizedPaymentData)
            .put("partner_data", partnerData)
            .put("order", order)
            .put("payment_preferences", paymentPreferences)
    }
}

/** The `/validate` body is a JSON array of these — same shape as [ItemPayload], as in web. */
typealias ValidateItemPayload = ItemPayload
