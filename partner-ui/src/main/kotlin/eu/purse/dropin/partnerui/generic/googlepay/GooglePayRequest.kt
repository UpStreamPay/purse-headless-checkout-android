package eu.purse.dropin.partnerui.generic.googlepay

import org.json.JSONObject

/**
 * Everything `:app` needs to run the Google Pay sheet for one `googlepay` method, built from its
 * `/init` [GooglePayConfig] plus the session's amount/currency (see
 * [GooglePayMapper.buildIsReadyToPayJson] / [GooglePayMapper.buildPaymentDataRequestJson]).
 *
 * [isReadyToPayJson] / [paymentDataRequestJson] are ready to hand to
 * `IsReadyToPayRequest.fromJson` / `PaymentDataRequest.fromJson`. [partnerWireValue] is carried
 * through so the later `/validate` call can be attributed to the right partner, the same way
 * callers of `DropinAndroid.validatePaypal` don't need to re-derive `"paypal"`.
 */
data class GooglePayRequest(
    val partnerWireValue: String,
    val isReadyToPayJson: JSONObject,
    val paymentDataRequestJson: JSONObject,
)
