package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.core.api.PaymentApiException
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the `/init` and `/validate` request bodies for a `purse`-vendor vault-backed card
 * (`creditcard`) payment and parses `/init`'s response. Ports usp-widget `partner-ui`'s
 * `plugins/vaults/purse/creditcard` bridge (`bridge.ts`'s `submitForResult`/`postValidate`) and the
 * generic `plugins/generics/hostedfields` plugin it plugs into (`index.ts`'s
 * `onSessionSet`/`preValidation`).
 *
 * Web calls `/init` once, early, at `onSessionSet` — before the shopper even sees the card form —
 * purely to obtain a `step_id` to carry into `/validate` later. [CardPlugin.preValidation] calls it
 * right after SecureFields tokenizes instead, immediately before `/validate` — same two calls in
 * the same order, just later in the lifecycle (no rendering step here depends on `/init`'s
 * response, unlike Google Pay's gateway config or Klarna's client token).
 */
object CardMapper {

    const val METHOD = "creditcard"
    const val VAULT_VENDOR = "purse"

    /**
     * `/init` body — mirrors web's `onSessionSet` call: no card data yet, just enough to register
     * the method and get back a `step_id`. `payment_data.vault_vendor` mirrors
     * `purseCreditCardBridge`'s `additionalInitData`.
     */
    fun buildInitPayload(amount: BigDecimal, partnerWireValue: String): JSONObject =
        JSONObject()
            .put("partner", partnerWireValue)
            .put("method", METHOD)
            .put("amount", amount)
            .put("partner_data", JSONObject())
            .put("payment_data", JSONObject().put("vault_vendor", VAULT_VENDOR))
            .put("payment_preferences", JSONObject())

    /**
     * Parses `/init`'s `step_id` — see [buildValidatePaymentData].
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
        val stepId = if (initData.has("step_id") && !initData.isNull("step_id")) initData.optInt("step_id") else null
        return CardInit(stepId)
    }

    /**
     * `/validate` `payment_data` body. [vaultVendorTokenId] is a **top-level field, not nested
     * under `card`** — carries the SecureFields vault token (`SubmitResult.Success.vaultFormToken`).
     * `card.bin`/`selected_network` are the non-sensitive card metadata SecureFields also returns
     * (`SubmitResult.Success.card`). This exact shape ports `purseCreditCardBridge.submitForResult`
     * in `usp-widget/packages/partner-ui/src/plugins/vaults/purse/creditcard/{bridge,utils}.ts`.
     */
    fun buildValidatePaymentData(vaultVendorTokenId: String, bin: String?, selectedNetwork: String?): JSONObject {
        val card = JSONObject()
        if (!bin.isNullOrBlank()) card.put("bin", bin)
        if (!selectedNetwork.isNullOrBlank()) card.put("selected_network", selectedNetwork)
        return JSONObject()
            .put("card", card)
            .put("vault_vendor_token_id", vaultVendorTokenId)
            .put("vault_vendor", VAULT_VENDOR)
    }

    /**
     * `/validate` `payment_data` for a **saved token** (card-on-file) — web's
     * `patchItemPayloadWithToken` plus the vault bridge's unconditional `vault_vendor`: the opaque
     * token value at top level, the card metadata under `card`, the holder alongside.
     *
     * [vaultVendorTokenId] is the SecureFields **CVV-only** tokenization result, present only when
     * the token still needs its CVV ([eu.purse.partnerui.PayToken.requiresCvv]) — web's
     * `submitForResult` sets `payment_data.vault_vendor_token_id` from the hosted fields'
     * `vault_form_token` on top of the token payload, and the Vault pairs the two server-side.
     */
    fun buildTokenPaymentData(token: eu.purse.partnerui.PayToken, vaultVendorTokenId: String? = null): JSONObject {
        val paymentData = JSONObject()
            .put("token", token.value)
            .put("vault_vendor", VAULT_VENDOR)
        vaultVendorTokenId?.let { paymentData.put("vault_vendor_token_id", it) }
        token.holderName?.let { paymentData.put("holder", it) }
        val card = JSONObject()
        token.wireExpiryDate()?.let { card.put("expiry_date", it) }
        token.selectedNetwork?.let { card.put("selected_network", it) }
        if (card.length() > 0) paymentData.put("card", card)
        return paymentData
    }
}
