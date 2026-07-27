package eu.purse.partnerui.plugins.partners.klarna

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PluginRegistry

/**
 * Entry point for the Klarna plugin. Call [register] once at app startup to wire the plugin into
 * [PluginRegistry] — the contract stays generic (no compile-time reference to [KlarnaPlugin]); this is
 * how a concrete plugin registers itself.
 */
object KlarnaModule {

    /**
     * Registers the Klarna plugin for its one dedicated `(klarna, installments)` pair — unlike Google
     * Pay, Klarna isn't a template reused across gateways, so no fallback is needed.
     */
    fun register() {
        PluginRegistry.register(Partner.Klarna, Method.Installments) { data -> KlarnaPlugin(data.partner) }
    }
}
