package eu.purse.partnerui.plugins.generics.googlepay

import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico.Ingenico
import eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico.WorldlineProductClient
import org.json.JSONObject

/**
 * The Google Pay [PartnerUiPlugin] — the protocol half (pure, no Play-Services). It builds the
 * Google Pay request from `/init` ([buildRequest], driven by a coordinator before the sheet) and,
 * after the sheet, maps the collected token into the `/validate` payload ([preValidation]).
 *
 * The interactive half (the `PaymentsClient` sheet, 3-D Secure) lives in [GooglePayCoordinator].
 * The token + selected network reach [preValidation] through `PartnerUiContext.collected`, populated
 * by the SDK from what the coordinator handed back via `PurseHeadlessCheckout.setCollected`.
 */
class GooglePayPlugin(override val partner: Partner) : PartnerUiPlugin {

    override val method: Method = Method.GooglePay

    /**
     * `POST /init` → gateway config → (Ingenico) Worldline product-320 refine → the Google Pay
     * request JSON to run the sheet. Suspend/blocking — call off the main thread.
     */
    suspend fun buildRequest(context: PartnerUiContext): GooglePayRequest {
        val initResponse = context.api.init(GooglePayMapper.buildInitPayload(context.amount, partner.wireValue))
        var config = GooglePayMapper.parseInitConfig(initResponse, partner.wireValue)

        if (partner.wireValue == Ingenico.WIRE_VALUE) {
            initResponse.optJSONObject("plugin_result")?.optJSONObject("logs")?.let { logs ->
                config = WorldlineProductClient.refine(config, logs)
            }
        }

        return GooglePayRequest(
            partnerWireValue = partner.wireValue,
            isReadyToPayJson = GooglePayMapper.buildIsReadyToPayJson(config),
            paymentDataRequestJson = GooglePayMapper.buildPaymentDataRequestJson(
                config = config,
                currencyCode = context.session.currencyCode,
                amount = context.amount,
            ),
        )
    }

    override suspend fun preValidation(context: PartnerUiContext): ValidateItemPayload {
        val token = context.collected[COLLECTED_TOKEN] as? String
            ?: error("Google Pay token missing — run the payment sheet before submitting")
        val network = context.collected[COLLECTED_NETWORK] as? String

        return ItemPayload(
            partner = partner,
            method = method,
            amount = context.amount,
            paymentData = GooglePayMapper.buildValidatePaymentData(network),
            partnerData = GooglePayMapper.buildValidatePartnerData(token),
        )
    }

    companion object {
        /** Keys the coordinator uses in `PurseHeadlessCheckout.setCollected`. */
        const val COLLECTED_TOKEN = "googlePayToken"
        const val COLLECTED_NETWORK = "selected_network"
    }
}

/**
 * The `PaymentDataRequest` ingredients `/init` returns for a `googlepay` method — web's per-gateway
 * `generics/googlepay/partners` builders. [allowedCardNetworks] is already in Google Pay vocabulary.
 * [countryCode] is only set by Ingenico; the mapper falls back to `"FR"` like web's `default.ts`.
 */
data class GooglePayConfig(
    val gateway: String,
    val gatewayMerchantId: String?,
    val merchantId: String?,
    val merchantName: String?,
    val allowedCardNetworks: List<String>,
    val countryCode: String? = null,
)

/** Ready-to-use `IsReadyToPayRequest` / `PaymentDataRequest` JSON for one `googlepay` item. */
data class GooglePayRequest(
    val partnerWireValue: String,
    val isReadyToPayJson: JSONObject,
    val paymentDataRequestJson: JSONObject,
)
