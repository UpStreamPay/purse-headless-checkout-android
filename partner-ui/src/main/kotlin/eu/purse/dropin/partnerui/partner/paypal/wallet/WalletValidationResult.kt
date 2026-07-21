package eu.purse.dropin.partnerui.partner.paypal.wallet

/**
 * The result of a PayPal wallet `/validate` (the capture step after the buyer approves).
 *
 * [isSuccess] is derived from the response's `global_status`. [redirectionUrl] carries a chained
 * redirect (e.g. 3-D Secure) from the response's `redirection.url` when present — on web this is
 * handled by `manageRedirection`; a native caller would open it (Custom Tab) and re-validate. For a
 * plain PayPal capture it is `null` and the caller simply shows success.
 *
 * [rawResponse] is the full `/validate` response, pretty-printed — surfaced so an integrator (or
 * this POC's own demo app) can inspect the real `results`/`redirection`/`global_status` shape
 * during testing, rather than only the fields this mapper currently parses out. See
 * docs/decisions/pain-points-recap.md on why observing a real response matters here.
 */
data class WalletValidationResult(
    val isSuccess: Boolean,
    val globalStatus: String,
    val redirectionUrl: String?,
    val rawResponse: String,
)
