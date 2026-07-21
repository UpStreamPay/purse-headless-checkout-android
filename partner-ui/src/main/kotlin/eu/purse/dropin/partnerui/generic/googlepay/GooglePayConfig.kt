package eu.purse.dropin.partnerui.generic.googlepay

/**
 * The Google Pay `PaymentDataRequest` ingredients for a `googlepay` method, returned by its
 * `/init` call. Unlike PayPal's `client_id` (a session-level `merchant_specifics` field), this
 * comes back from the **backend's `/init` response itself** — mirrors web's per-partner
 * `partners/adyen.ts` / `partners/default.ts`
 * (`usp-widget/packages/partner-ui/src/plugins/generics/googlepay`).
 *
 * [allowedCardNetworks] arrives already shaped as Google Pay's own network vocabulary (`"VISA"`,
 * `"MASTERCARD"`, …) — no [eu.purse.dropin.vault.CardBrand] mapping needed, the backend returns
 * it pre-translated for Google Pay.
 *
 * [merchantId]/[merchantName] feed `PaymentDataRequest.merchantInfo`; both are optional — Google
 * Pay only requires `merchantId` for a production (non-`ENVIRONMENT_TEST`) request.
 */
data class GooglePayConfig(
    val gateway: String,
    val gatewayMerchantId: String?,
    val merchantId: String?,
    val merchantName: String?,
    val allowedCardNetworks: List<String>,
)
