package eu.purse.googlepay

import org.json.JSONObject

/**
 * Everything the coordinator/app needs to run the Google Pay sheet for one `googlepay` method,
 * built from its `/init` [GooglePayConfig] plus the session's amount/currency (see
 * [GooglePayMapper.buildIsReadyToPayJson] / [GooglePayMapper.buildPaymentDataRequestJson]).
 *
 * [isReadyToPayJson] / [paymentDataRequestJson] are ready to hand to `IsReadyToPayRequest.fromJson`
 * / `PaymentDataRequest.fromJson`. [partnerWireValue] is carried through so the later `/validate`
 * can be attributed to the right partner.
 */
data class GooglePayRequest(
    val partnerWireValue: String,
    val isReadyToPayJson: JSONObject,
    val paymentDataRequestJson: JSONObject,
)
