package eu.purse.dropin.payment.method.secondary

import eu.purse.dropin.core.api.PaymentApiException
import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.partner.Partner
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the `/init` request body for a secondary card entry and parses the response into a
 * [SecondaryToken]. Ports usp-widget headless-checkout's `secondaries/init-mappers.ts` (base
 * payload) and `token-mappers.ts` (Illicado formatter). The POC targets Illicado gift cards; the
 * generic base payload covers them (Illicado has no custom `mapPaymentData` in web).
 */
object SecondaryTokenMapper {

    /**
     * @param amount the amount the primary would still cover, in major currency units — web passes
     * `paymentSplitter.primaryReach` here, which for a fresh session is the full session amount.
     */
    fun buildInitPayload(
        method: DropinPaymentMethod,
        pan: String,
        cvv: String?,
        amount: BigDecimal,
    ): JSONObject {
        val card = JSONObject().put("number", pan)
        if (!cvv.isNullOrEmpty()) card.put("csc", cvv)
        val paymentData = JSONObject()
            .put("card", card)
            .put("vault_vendor", "none") // web's normalizePaymentData default when unset
        return JSONObject()
            .put("order", JSONObject())
            .put("partner_data", JSONObject())
            .put("payment_preferences", JSONObject())
            .put("payment_data", paymentData)
            .put("amount", amount)
            .put("method", method.method.wireValue)
            .put("partner", method.partner.wireValue)
    }

    /**
     * @throws PaymentApiException if the init wasn't a success or the card carries no balance —
     * matching web, which surfaces those as user-facing errors rather than a usable token.
     */
    fun parse(initData: JSONObject): SecondaryToken {
        val status = initData.optJSONObject("status")
        val state = status?.optString("state").orEmpty()
        if (state != "SUCCESS") {
            val description = status?.optString("description").orEmpty()
            throw PaymentApiException("secondary init not successful (state=$state) $description".trim())
        }

        val pluginResult = initData.optJSONObject("plugin_result") ?: JSONObject()
        val card = pluginResult.optJSONObject("payment_data")
            ?.optJSONArray("cards")
            ?.optJSONObject(0)
        val balance = card?.let { bigDecimalOrNull(it, "balance") } ?: BigDecimal.ZERO
        if (balance.signum() <= 0) {
            throw PaymentApiException("gift card has no balance")
        }

        return SecondaryToken(
            partner = Partner.fromWire(initData.optString("partner")),
            method = Method.fromWire(initData.optString("method")),
            name = pluginResult.optJSONObject("logs")?.optString("masked_card").orEmpty(),
            balance = balance,
            expirationDate = card?.optString("expiry_date").orEmpty(),
        )
    }

    private fun bigDecimalOrNull(obj: JSONObject, key: String): BigDecimal? =
        if (obj.has(key) && !obj.isNull(key)) {
            runCatching { BigDecimal(obj.get(key).toString()) }.getOrNull()
        } else {
            null
        }
}
