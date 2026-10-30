// Web: packages/partner-ui/src/plugins/vaults/purse/creditcard + generics/hostedfields
// Same: token from the vault into /validate with `partner_data.browser`; saved token + CVV-only token side by side;
// no /init (the bridge's `skipInit`), so /validate carries no step.
// Differs: no `vaultExtras.skipInit: false` opt-back-in (only Cybersource uses it on web).
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.plugins.generics.redirection.RedirectionMapper
import org.json.JSONObject

/**
 * The `purse`-vendor vault-backed card [PartnerUiPlugin] — the protocol half (pure, no
 * SecureFields types). There is no `/init`: SecureFields tokenizes without it and the Payment API
 * accepts a vault card's `/validate` without a step, so [preValidation] only builds the `/validate`
 * payload from the vault token [CardCoordinator] already collected via `PartnerUiContext.collected`.
 * This mirrors web's purse vault bridge `skipInit` (headless-checkout 3.73.0) — some PSPs refuse the
 * `/init` outright (Worldpay: "Session data doesn't meet the partner's requirements", SDK-12570).
 *
 * The interactive half (the SecureFields form, tokenization) lives in [CardCoordinator].
 */
class CardPlugin(override val partner: Partner) : PartnerUiPlugin {

    override val method: Method = Method.CreditCard

    /**
     * The headless half of web's `validateUi`: there, the plugin submits its form and reports the
     * per-field errors that block `preValidation`. Here the form lives in [CardCoordinator] (which
     * refuses to tokenize an invalid one and shows SecureFields' own field errors), so all this hook
     * can check is the outcome — a vault token means the form was submitted successfully. A saved
     * token ([PartnerUiContext.token]) that pays without a CVV needs no form at all; one that still
     * requires its CVV ([eu.purse.partnerui.PayToken.requiresCvv]) needs the CVV-only form's vault
     * token exactly like a fresh card needs the full form's.
     */
    override suspend fun validateUi(context: PartnerUiContext): Boolean =
        context.token?.requiresCvv == false || context.collected[COLLECTED_TOKEN] is String

    /**
     * `partner_data.browser` ([COLLECTED_BROWSER]) rides on `/validate`, fresh card and saved token
     * alike — web's hostedfields `getItemBasePayloadFromFields`. Without it dalenys
     * refuses a token (`Missing parameter`) and a 3-D Secure PSP never opens its challenge. A saved
     * token paid with no [CardCoordinator] collected nothing, so it gets
     * [RedirectionMapper.fallbackBrowserData] instead.
     */
    override suspend fun preValidation(context: PartnerUiContext): ValidateItemPayload {
        val browser = context.collected[COLLECTED_BROWSER] as? JSONObject
            ?: RedirectionMapper.fallbackBrowserData(context.options.locale)

        // Saved token (card-on-file): the opaque token value plus the card metadata travels straight
        // into payment_data (web's patchItemPayloadWithToken). When the token still needs its CVV,
        // the CVV-only SecureFields tokenization rides along as `vault_vendor_token_id` — the same
        // key a fresh card uses, but paired with `token` here (web's submitForResult over a token).
        context.token?.let { token ->
            val cvvVaultToken = if (token.requiresCvv) {
                context.collected[COLLECTED_TOKEN] as? String
                    ?: error("CVV vault token missing — collect the CVV with SecureFields before submitting")
            } else {
                null
            }
            return ItemPayload(
                partner = partner,
                method = method,
                amount = context.amount,
                paymentData = CardMapper.buildTokenPaymentData(token, cvvVaultToken),
                partnerData = RedirectionMapper.partnerDataFor(browser),
            )
        }

        val vaultVendorTokenId = context.collected[COLLECTED_TOKEN] as? String
            ?: error("card vault token missing — tokenize with SecureFields before submitting")
        val bin = context.collected[COLLECTED_BIN] as? String
        val selectedNetwork = context.collected[COLLECTED_NETWORK] as? String

        return ItemPayload(
            partner = partner,
            method = method,
            amount = context.amount,
            paymentData = CardMapper.buildValidatePaymentData(vaultVendorTokenId, bin, selectedNetwork),
            partnerData = RedirectionMapper.partnerDataFor(browser),
        )
    }

    companion object {
        /** Keys [CardCoordinator] uses in `PurseHeadlessCheckout.setCollected`. */
        const val COLLECTED_TOKEN = "cardVaultToken"
        const val COLLECTED_BIN = "cardBin"
        const val COLLECTED_NETWORK = "selected_network"
        const val COLLECTED_BROWSER = "cardBrowser"
    }
}
