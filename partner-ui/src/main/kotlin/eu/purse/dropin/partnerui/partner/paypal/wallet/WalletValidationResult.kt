package eu.purse.dropin.partnerui.partner.paypal.wallet

import eu.purse.dropin.partnerui.redirect.RedirectAction

/**
 * The result of a PayPal wallet `/validate` (the capture step after the buyer approves).
 *
 * [isSuccess] is derived from the response's `global_status`. [redirectAction], resolved by
 * [eu.purse.dropin.partnerui.redirect.RedirectionResolver], carries a chained redirect (e.g. 3-D
 * Secure) when present — `null` for a plain PayPal capture, in which case the caller simply shows
 * success. See docs/architecture/redirect-handling.md.
 *
 * [rawResponse] is the full `/validate` response, pretty-printed — surfaced so an integrator (or
 * this POC's own demo app) can inspect the real `results`/`redirection`/`global_status` shape
 * during testing, rather than only the fields this mapper currently parses out. See
 * docs/decisions/pain-points-recap.md on why observing a real response matters here.
 */
data class WalletValidationResult(
    val isSuccess: Boolean,
    val globalStatus: String,
    val redirectAction: RedirectAction?,
    val rawResponse: String,
)
