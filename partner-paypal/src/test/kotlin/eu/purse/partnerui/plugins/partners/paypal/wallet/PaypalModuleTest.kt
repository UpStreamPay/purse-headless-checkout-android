package eu.purse.partnerui.plugins.partners.paypal.wallet

import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.session.MethodEntry
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this artifact adds to the registry once it is on the classpath — the PayPal cases of web's
 * `getPlugin` suite. Discovery itself is exercised by being here.
 */
class PaypalModuleTest {

    private fun sessionWith(
        partner: Partner,
        method: Method,
        integrationMode: IntegrationMode? = null,
    ) = LegacyPaymentSession(
        id = "s-1",
        amount = BigDecimal("4.80"),
        informationRequest = false,
        currencyCode = "EUR",
        expirationDate = Instant.parse("2999-01-01T00:00:00Z"),
        success = null,
        failure = null,
        redirection = null,
        protocols = mapOf(
            partner to mapOf(
                method to MethodEntry(
                    MethodConfiguration(
                        stable = true,
                        template = null,
                        labels = emptyList(),
                        position = 1,
                        vault = null,
                        settings = MethodSettings(
                            registrationMode = RegistrationMode.UNKNOWN,
                            saveToken = false,
                            saveTokenMode = null,
                            directDebit = false,
                            paymentMinAmount = null,
                            paymentMaxAmount = null,
                            usagePerSessionLimit = null,
                            integrationMode = integrationMode,
                        ),
                    ),
                ),
            ),
        ),
        vault = null,
    )

    @Test
    fun `the module is discovered from the classpath`() {
        assertTrue(PluginRegistry.modules.count { it is PaypalModule } == 1)
    }

    @Test
    fun `paypal wallet in popup mode resolves to the paypal plugin`() {
        // The mode real sessions carry for PayPal — this is what routes to the dedicated plugin.
        val session = sessionWith(Partner.Paypal, Method.Wallet, integrationMode = IntegrationMode.POPUP)
        val plugin = PluginRegistry.getPlugin(Partner.Paypal, Method.Wallet, session)
        assertTrue(plugin is PaypalPlugin)
        assertTrue(PluginRegistry.moduleFor(plugin!!) is PaypalModule)
    }

    @Test
    fun `paypal wallet in redirection mode stays on the redirection generic`() {
        // Deliberate: web's own redirection generic lists a redirection-mode PayPal wallet as one of
        // the methods it serves, and integration_mode outranks the exact pair.
        val session = sessionWith(Partner.Paypal, Method.Wallet, integrationMode = IntegrationMode.REDIRECTION)
        assertTrue(PluginRegistry.getPlugin(Partner.Paypal, Method.Wallet, session) is RedirectionPlugin)
    }
}
