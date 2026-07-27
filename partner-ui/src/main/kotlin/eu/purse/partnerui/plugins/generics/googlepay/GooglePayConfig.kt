package eu.purse.partnerui.plugins.generics.googlepay

/**
 * The Google Pay `PaymentDataRequest` ingredients for a `googlepay` method, returned by its `/init`
 * call — mirrors web's per-partner `generics/googlepay/partners` builders. Unlike PayPal's
 * `client_id` (a session `merchant_specifics` field), this comes back from the **backend's `/init`
 * response**.
 *
 * [allowedCardNetworks] arrives already in Google Pay's own vocabulary (`"VISA"`, `"MASTERCARD"`, …)
 * — pre-translated by the backend, no card-brand mapping needed. [merchantId]/[merchantName] feed
 * `merchantInfo`; both optional (Google Pay only requires `merchantId` for a production request).
 */
data class GooglePayConfig(
    val gateway: String,
    val gatewayMerchantId: String?,
    val merchantId: String?,
    val merchantName: String?,
    val allowedCardNetworks: List<String>,
)
