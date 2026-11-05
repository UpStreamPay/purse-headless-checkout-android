// Web: packages/partner-ui/src/plugins/vaults/purse/creditcard/bridge.ts
// Same: `vault_vendor_token_id` + bin + selected_network in `payment_data`; no /init (`skipInit`).
// Differs: nothing.
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import org.json.JSONObject

/**
 * Builds the `/validate` `payment_data` for a `purse`-vendor vault-backed card (`creditcard`)
 * payment. Ports usp-widget `partner-ui`'s `plugins/vaults/purse/creditcard` bridge (`bridge.ts`'s
 * `submitForResult`) and the generic `plugins/generics/hostedfields` plugin it plugs into.
 *
 * There is no `/init` body: the purse bridge sets `skipInit`, so web never calls `/init` for a vault
 * card and its `/validate` item carries `step_id: null`. [CardPlugin]'s item has no step either
 * (`ItemPayload` then leaves `step_id` out, which the Payment API reads the same way).
 */
object CardMapper {

    const val VAULT_VENDOR = "purse"

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
        val paymentData = token.patchPaymentData().put("vault_vendor", VAULT_VENDOR)
        vaultVendorTokenId?.let { paymentData.put("vault_vendor_token_id", it) }
        return paymentData
    }
}
