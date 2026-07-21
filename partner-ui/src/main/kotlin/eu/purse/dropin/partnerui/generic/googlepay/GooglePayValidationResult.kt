package eu.purse.dropin.partnerui.generic.googlepay

/**
 * The result of a Google Pay `/validate` (capture) call — the same shape as
 * [eu.purse.dropin.partnerui.partner.paypal.wallet.WalletValidationResult].
 *
 * [isSuccess] is derived from the response's `global_status`. [redirectionUrl] surfaces a chained
 * 3-D Secure redirect from `redirection.url` when present. Google Pay's `/validate` step is a
 * **confirmed**, not just hypothetical, redirect trigger (see
 * docs/partners/adyen.md#redirection-study) — but as with PayPal, this POC surfaces it without
 * auto-following it: no generic redirect handler is built yet (see
 * docs/architecture/redirect-handling.md). A native caller would open it (Custom Tab) and
 * re-validate once that lands.
 */
data class GooglePayValidationResult(
    val isSuccess: Boolean,
    val globalStatus: String,
    val redirectionUrl: String?,
)
