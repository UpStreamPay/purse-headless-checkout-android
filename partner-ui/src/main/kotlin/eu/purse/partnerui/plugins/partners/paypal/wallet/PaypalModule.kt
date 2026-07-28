package eu.purse.partnerui.plugins.partners.paypal.wallet

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PluginRegistry

/**
 * Entry point for the PayPal wallet plugin. [PluginRegistry] calls [register] automatically on
 * first use — an app does **not** need to call this itself. Calling it again is harmless
 * (idempotent; last registration wins) — it stays public mainly for tests that want to register in
 * isolation. To opt this method out for a specific app build, use [PluginRegistry.disable], not the
 * absence of a [register] call — see the class doc on [PluginRegistry].
 */
object PaypalModule {

    /**
     * Registers the PayPal plugin for its one dedicated `(paypal, wallet)` pair — like Klarna and
     * unlike Google Pay, PayPal isn't a template reused across gateways, so no fallback is needed.
     *
     * Note this does **not** claim a PayPal `wallet` the merchant configured with
     * `merchant_specifics.integration_mode = redirection`: that check sits *above* the exact-pair
     * lookup in [PluginRegistry.getPlugin], so such a session is served by the generic redirection
     * template instead. Deliberate, and mirrors web.
     */
    fun register() {
        PluginRegistry.register(Partner.Paypal, Method.Wallet) { data -> PaypalPlugin(data.partner) }
    }
}
