package eu.purse.dropin.partnerui.vault.purse.creditcard

import eu.purse.dropin.core.api.PaymentApiException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the `/init` and `/validate` request bodies for a `purse`-vendor vault-backed card
 * (`creditcard`) payment and parses their responses. Ports usp-widget `partner-ui`'s
 * `plugins/vaults/purse/creditcard` bridge (`bridge.ts`'s `submitForResult`/`postValidate`) and the
 * generic `plugins/generics/hostedfields` plugin it plugs into (`index.ts`'s
 * `onSessionSet`/`preValidation`) — see docs/integration/payment-methods.md#cb-full.
 *
 * Two differences from the web reference, both simplifications for this POC, not contract changes:
 *  1. Web calls `/init` once, early, at `onSessionSet` — before the shopper even sees the card
 *     form — purely to obtain a `step_id` to carry into `/validate` later. This port calls it right
 *     after SecureFields tokenizes, immediately before `/validate` — same two calls in the same
 *     order, just later in the UI lifecycle.
 *  2. The `external_three_ds_enabled` / `init3DS` pre-validate branch (`handleExternal3ds` in web's
 *     `utils.ts`, gated on partner-specific `vaultExtras`) isn't ported — no partner in this POC's
 *     scope configures it, per docs/partners/purse.md.
 */
object CardMapper {

    const val METHOD = "creditcard"

    /**
     * `/init` body — mirrors web's `onSessionSet` call: no card data yet (that only exists after
     * tokenization), just enough to register the method and get back a `step_id`.
     * `payment_data.vault_vendor` mirrors `purseCreditCardBridge`'s `additionalInitData`.
     */
    fun buildInitPayload(amount: BigDecimal, partnerWireValue: String): JSONObject =
        JSONObject()
            .put("partner", partnerWireValue)
            .put("method", METHOD)
            .put("amount", amount)
            .put("partner_data", JSONObject())
            .put("payment_data", JSONObject().put("vault_vendor", "purse"))
            .put("payment_preferences", JSONObject())

    /**
     * Parses `/init`'s `step_id` — see [buildValidatePayload].
     *
     * @throws PaymentApiException if the init wasn't a success.
     */
    fun parseInit(initData: JSONObject): CardInit {
        val status = initData.optJSONObject("status")
        val state = status?.optString("state").orEmpty()
        if (state.isNotEmpty() && state != "SUCCESS") {
            val description = status?.optString("description").orEmpty()
            throw PaymentApiException("creditcard init not successful (state=$state) $description".trim())
        }
        return CardInit(stepId = initData.optNumberOrStringOrNull("step_id"))
    }

    /**
     * `/validate` body — a single-item JSON array (validate takes an array of items, one per
     * method contributing to the payment — see docs/architecture/session-and-protocols.md#init-and-validate).
     *
     * `payment_data.vault_vendor_token_id` is a **top-level field under `payment_data`, not nested
     * under `card`** — carries the SecureFields vault token (`SubmitResult.Success.vaultFormToken`).
     * `payment_data.card.bin`/`selected_network` are the non-sensitive card metadata SecureFields
     * also returns (`SubmitResult.Success.card`). This exact shape ports
     * `purseCreditCardBridge.submitForResult` + `handleExternal3ds` in
     * `usp-widget/packages/partner-ui/src/plugins/vaults/purse/creditcard/{bridge,utils}.ts` — it
     * has no precedent anywhere else in this POC's docs, which previously assumed the token was
     * nested under `card` before this was checked against web.
     */
    fun buildValidatePayload(
        amount: BigDecimal,
        partnerWireValue: String,
        vaultVendorTokenId: String,
        bin: String?,
        selectedNetwork: String?,
        stepId: String?,
    ): JSONArray {
        val card = JSONObject()
        if (!bin.isNullOrBlank()) card.put("bin", bin)
        if (!selectedNetwork.isNullOrBlank()) card.put("selected_network", selectedNetwork)

        val item = JSONObject()
            .put("partner", partnerWireValue)
            .put("method", METHOD)
            .put("amount", amount)
            .put("partner_data", JSONObject())
            .put(
                "payment_data",
                JSONObject()
                    .put("card", card)
                    .put("vault_vendor_token_id", vaultVendorTokenId)
                    .put("vault_vendor", "purse"),
            )
            .put("payment_preferences", JSONObject().put("registration", false))
        // step_id is a number on the wire; send it as one when it parses, else as-is, else null —
        // same convention as PaypalWalletMapper.buildValidatePayload.
        when {
            stepId == null -> item.put("step_id", JSONObject.NULL)
            stepId.toLongOrNull() != null -> item.put("step_id", stepId.toLong())
            else -> item.put("step_id", stepId)
        }
        return JSONArray().put(item)
    }

    /**
     * Parses the `/validate` response `{results, redirection, global_status}` into a
     * [CardValidationResult] — identical logic to
     * [eu.purse.dropin.partnerui.partner.paypal.wallet.PaypalWalletMapper.parseValidation].
     */
    fun parseValidation(data: JSONObject): CardValidationResult {
        val globalStatus = data.optString("global_status")
        val redirectionUrl = data.optJSONObject("redirection")?.optStringOrNull("url")
        return CardValidationResult(
            isSuccess = globalStatus.equals("SUCCESS", ignoreCase = true),
            globalStatus = globalStatus,
            redirectionUrl = redirectionUrl,
            rawResponse = data.toString(2),
        )
    }

    /** Local null-safe string read (the `:dropin` JsonExtensions aren't visible from here). */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    /** Reads a field that may be a JSON string OR number (e.g. `step_id`) as its string form. */
    private fun JSONObject.optNumberOrStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) get(name).toString() else null
}
