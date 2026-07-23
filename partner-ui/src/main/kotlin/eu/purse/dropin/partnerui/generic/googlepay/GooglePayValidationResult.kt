package eu.purse.dropin.partnerui.generic.googlepay

import eu.purse.dropin.partnerui.redirect.RedirectAction

/**
 * The result of a Google Pay `/validate` (capture) call — the same shape as
 * [eu.purse.dropin.partnerui.partner.paypal.wallet.WalletValidationResult].
 *
 * [isSuccess] is derived from the response's `global_status`. [redirectAction], resolved by
 * [eu.purse.dropin.partnerui.redirect.RedirectionResolver], carries a chained 3-D Secure redirect
 * when present. Google Pay's `/validate` step is a **confirmed**, not just hypothetical, redirect
 * trigger (see docs/partners/adyen.md#redirection-study) — see
 * `eu.purse.dropin.partnerui.generic.redirection.RedirectionCheckoutContract` for how the caller
 * follows it and re-validates.
 *
 * [rawResponse] is the full `/validate` response, pretty-printed — surfaced so an integrator (or
 * this POC's own demo app) can inspect the real `results`/`redirection`/`global_status` shape
 * during testing, rather than only the fields this mapper currently parses out.
 */
data class GooglePayValidationResult(
    val isSuccess: Boolean,
    val globalStatus: String,
    val redirectAction: RedirectAction?,
    val rawResponse: String,
)
