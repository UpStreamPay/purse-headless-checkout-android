package eu.purse.dropin.partnerui.vault.purse.creditcard

import eu.purse.dropin.partnerui.redirect.RedirectAction

/**
 * The result of a `purse`-vendor vault-backed card `/validate` — the same shape as
 * [eu.purse.dropin.partnerui.partner.paypal.wallet.WalletValidationResult] /
 * [eu.purse.dropin.partnerui.generic.googlepay.GooglePayValidationResult].
 *
 * [isSuccess] is derived from the response's `global_status`. [redirectAction], resolved by
 * [eu.purse.dropin.partnerui.redirect.RedirectionResolver], carries a chained 3-D Secure redirect
 * when present — mirrors `purseCreditCardBridge.postValidate` in
 * `usp-widget/packages/partner-ui/src/plugins/vaults/purse/creditcard/bridge.ts`, which resolves
 * this exact field through the generic `manageRedirection` contract (see
 * docs/architecture/redirect-handling.md). Not yet auto-followed here — same starter-depth
 * limitation as PayPal/Google Pay.
 */
data class CardValidationResult(
    val isSuccess: Boolean,
    val globalStatus: String,
    val redirectAction: RedirectAction?,
    val rawResponse: String,
)
