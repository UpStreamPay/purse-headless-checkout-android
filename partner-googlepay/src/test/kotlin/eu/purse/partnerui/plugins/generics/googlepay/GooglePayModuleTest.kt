package eu.purse.partnerui.plugins.generics.googlepay

import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.session.MethodEntry
import eu.purse.partnerui.PluginRegistry
import eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico.Ingenico
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this artifact adds to the registry once it is on the classpath — the Google Pay cases of
 * web's `getPlugin` suite (`packages/partner-ui/src/plugins/plugins.spec.ts`). Discovery itself is
 * exercised by being here: nothing installs [GooglePayModule] but `META-INF/services`.
 */
class GooglePayModuleTest {

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

    private val aci = Partner.Other("aci")

    @Test
    fun `the module is discovered from the classpath`() {
        assertTrue(PluginRegistry.modules.count { it is GooglePayModule } == 1)
    }

    @Test
    fun `a plain googlepay method with no template, vault or integration mode falls back per method`() {
        val plugin = PluginRegistry.getPlugin(aci, Method.GooglePay, sessionWith(aci, Method.GooglePay))
        assertTrue(plugin is GooglePayPlugin)
        assertTrue(PluginRegistry.moduleFor(plugin!!) is GooglePayModule)
    }

    @Test
    fun `ingenico googlepay resolves through the exact pair`() {
        val ingenico = Partner.Other(Ingenico.WIRE_VALUE)
        assertTrue(PluginRegistry.getPlugin(ingenico, Method.GooglePay, sessionWith(ingenico, Method.GooglePay)) is GooglePayPlugin)
    }

    @Test
    fun `integration_mode redirection outranks Google Pay`() {
        val session = sessionWith(aci, Method.GooglePay, integrationMode = IntegrationMode.REDIRECTION)
        assertTrue(PluginRegistry.getPlugin(aci, Method.GooglePay, session) is RedirectionPlugin)
    }
}
