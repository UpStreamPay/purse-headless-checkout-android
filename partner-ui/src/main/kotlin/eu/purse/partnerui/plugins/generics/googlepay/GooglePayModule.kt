package eu.purse.partnerui.plugins.generics.googlepay

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico.Ingenico

/**
 * Entry point for the Google Pay plugin. [PluginRegistry] calls [register] automatically on first
 * use — an app does **not** need to call this itself. Calling it again is harmless (idempotent;
 * last registration wins) — it stays public mainly for tests that want to register in isolation.
 * To opt this method out for a specific app build, use [PluginRegistry.disable], not the absence
 * of a [register] call — see the class doc on [PluginRegistry].
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
