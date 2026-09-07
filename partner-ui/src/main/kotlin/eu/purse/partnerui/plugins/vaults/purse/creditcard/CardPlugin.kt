// Web: packages/partner-ui/src/plugins/vaults/purse/creditcard + generics/hostedfields
// Same: token from the vault into /validate; saved token + CVV-only token side by side.
// Differs: /init runs inside `preValidation`, not at `onSessionSet`.
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin

/**
 * The `purse`-vendor vault-backed card [PartnerUiPlugin] — the protocol half (pure, no
 * SecureFields types). Unlike Google Pay/Klarna, `/init` gates nothing about rendering (SecureFields
 * tokenizes independently of it), so [preValidation] does both steps itself: `POST /init` for a
 * `step_id`, then builds the `/validate` payload from the vault token [CardCoordinator] already
 * collected via `PartnerUiContext.collected`.
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

    override suspend fun preValidation(context: PartnerUiContext): ValidateItemPayload {
        val stepId = CardMapper.parseInit(
            context.api.init(CardMapper.buildInitPayload(context.amount, partner.wireValue)),
        )

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
                stepId = stepId,
                paymentData = CardMapper.buildTokenPaymentData(token, cvvVaultToken),
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
            stepId = stepId,
            paymentData = CardMapper.buildValidatePaymentData(vaultVendorTokenId, bin, selectedNetwork),
        )
    }

    companion object {
        /** Keys [CardCoordinator] uses in `PurseHeadlessCheckout.setCollected`. */
        const val COLLECTED_TOKEN = "cardVaultToken"
        const val COLLECTED_BIN = "cardBin"
        const val COLLECTED_NETWORK = "selected_network"
    }
}
