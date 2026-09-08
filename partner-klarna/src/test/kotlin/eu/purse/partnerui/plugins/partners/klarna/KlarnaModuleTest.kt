package eu.purse.partnerui.plugins.partners.klarna

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
 * What this artifact adds to the registry once it is on the classpath — the Klarna cases of web's
 * `getPlugin` suite, including the negative one web pins (`integration_mode: popup` does not route
 * to the redirection generic). Discovery itself is exercised by being here.
 */
class KlarnaModuleTest {

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
        assertTrue(PluginRegistry.modules.count { it is KlarnaModule } == 1)
    }

    @Test
    fun `integration_mode popup does not route to the redirection generic`() {
        val session = sessionWith(Partner.Klarna, Method.Installments, integrationMode = IntegrationMode.POPUP)
        val plugin = PluginRegistry.getPlugin(Partner.Klarna, Method.Installments, session)
        assertTrue(plugin is KlarnaPlugin)
        assertTrue(PluginRegistry.moduleFor(plugin!!) is KlarnaModule)
    }

    @Test
    fun `integration_mode redirection outranks the dedicated Klarna plugin`() {
        // Web's getPartnerPlugin checks integration_mode BEFORE its own partner_plugins table.
        val session = sessionWith(Partner.Klarna, Method.Installments, integrationMode = IntegrationMode.REDIRECTION)
        assertTrue(PluginRegistry.getPlugin(Partner.Klarna, Method.Installments, session) is RedirectionPlugin)
    }
}
