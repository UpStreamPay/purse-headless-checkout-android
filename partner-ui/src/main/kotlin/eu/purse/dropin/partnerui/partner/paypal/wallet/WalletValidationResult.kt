package eu.purse.dropin.partnerui.partner.paypal.wallet

/**
 * The result of a PayPal wallet `/validate` (the capture step after the buyer approves).
 *
 * [isSuccess] is derived from the response's `global_status`. [redirectionUrl] carries a chained
 * redirect (e.g. 3-D Secure) from the response's `redirection.url` when present — on web this is
 * handled by `manageRedirection`; a native caller would open it (Custom Tab) and re-validate. For a
 * plain PayPal capture it is `null` and the caller simply shows success.
 */
data class WalletValidationResult(
    val isSuccess: Boolean,
    val globalStatus: String,
    val redirectionUrl: String?,
)
