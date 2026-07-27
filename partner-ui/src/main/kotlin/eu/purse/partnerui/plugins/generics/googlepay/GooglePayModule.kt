package eu.purse.partnerui.plugins.generics.googlepay

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico.Ingenico

/**
 * Entry point for the Google Pay plugin. Call [register] once at app startup to wire the plugin
 * into [PluginRegistry] — the contract stays generic (no compile-time reference to
 * [GooglePayPlugin]); this is how a concrete plugin registers itself.
 */
object GooglePayModule {

    /**
     * Registers the Google Pay plugin for Ingenico (Worldline) and as the fallback for any other
     * partner's `googlepay` (Adyen / the generic gateway shape) — mirroring web's one generic
     * plugin dispatching per partner.
     */
    fun register() {
        PluginRegistry.register(Partner.Other(Ingenico.WIRE_VALUE), Method.GooglePay) { data -> GooglePayPlugin(data.partner) }
        PluginRegistry.registerFallback(Method.GooglePay) { data -> GooglePayPlugin(data.partner) }
    }
}
