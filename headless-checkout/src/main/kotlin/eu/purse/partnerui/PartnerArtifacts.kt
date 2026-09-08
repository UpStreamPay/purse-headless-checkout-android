// Web: none — the web bundle ships every plugin, so a method the session offers always has one.
// Same: n/a.
// Differs: Google Pay, Klarna and PayPal are optional Maven artifacts; this table turns "no plugin
//          registered" into "add this dependency" for the integrator.
package eu.purse.partnerui

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner

/** The optional Maven artifact that carries the plugin for a `(partner, method)`, when there is one. */
object PartnerArtifacts {

    private const val GROUP = "eu.purse"

    /**
     * `"eu.purse:headless-checkout-klarna"` for `(klarna, installments)`, and so on — `null` when no
     * Purse artifact serves the pair (an unknown partner, or a method the engine ships itself).
     */
    fun forMethod(partner: Partner, method: Method): String? = when {
        method == Method.GooglePay -> "$GROUP:headless-checkout-googlepay"
        partner == Partner.Klarna && method == Method.Installments -> "$GROUP:headless-checkout-klarna"
        partner == Partner.Paypal && method == Method.Wallet -> "$GROUP:headless-checkout-paypal"
        else -> null
    }

    /** The message worth showing a developer when [partner]/[method] resolved to no plugin. */
    fun missingPluginMessage(partner: Partner, method: Method): String {
        val id = "${partner.wireValue}/${method.wireValue}"
        val artifact = forMethod(partner, method)
        return if (artifact == null) {
            "no plugin registered for $id"
        } else {
            "no plugin registered for $id — add the `$artifact` dependency to offer this method"
        }
    }
}
