package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.vault.VaultVendor
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
    override val vault: VaultVendor = VaultVendor.PURSE

    /**
     * The headless half of web's `validateUi`: there, the plugin submits its form and reports the
     * per-field errors that block `preValidation`. Here the form lives in [CardCoordinator] (which
     * refuses to tokenize an invalid one and shows SecureFields' own field errors), so all this hook
     * can check is the outcome — a vault token means the form was submitted successfully. A saved
     * token ([PartnerUiContext.token]) needs no form at all.
     */
    override suspend fun validateUi(context: PartnerUiContext): Boolean =
        context.token != null || context.collected[COLLECTED_TOKEN] is String

    override suspend fun preValidation(context: PartnerUiContext): ValidateItemPayload {
        val stepId = CardMapper.parseInit(
            context.api.init(CardMapper.buildInitPayload(context.amount, partner.wireValue)),
        ).stepId

        // Saved token (card-on-file): no SecureFields tokenization — the opaque token value plus
        // the card metadata travels straight into payment_data (web's patchItemPayloadWithToken).
        context.token?.let { token ->
            return ItemPayload(
                partner = partner,
                method = method,
                amount = context.amount,
                stepId = stepId,
                paymentData = CardMapper.buildTokenPaymentData(token),
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
