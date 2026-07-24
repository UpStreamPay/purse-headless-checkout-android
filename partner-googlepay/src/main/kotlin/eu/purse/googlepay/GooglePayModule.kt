package eu.purse.googlepay

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PluginRegistry

/**
 * Entry point for the Google Pay plugin. Call [register] once at app startup to wire the plugin
 * into `:partner-ui`'s [PluginRegistry] — this is how the plugin lives in its own module without
 * `:partner-ui` depending on it.
 */
object GooglePayModule {

    /**
     * Registers the Google Pay plugin for Ingenico (Worldline) and as the fallback for any other
     * partner's `googlepay` (Adyen / the generic gateway shape) — mirroring web's one generic
     * plugin dispatching per partner.
     */
    fun register() {
        PluginRegistry.register(Partner.Other("ingenico"), Method.GooglePay) { data -> GooglePayPlugin(data.partner) }
        PluginRegistry.registerFallback(Method.GooglePay) { data -> GooglePayPlugin(data.partner) }
    }
}
