package eu.purse.partnerui

import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.payment.VaultInfo
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.core.session.MethodEntry
import eu.purse.core.vault.VaultVendor
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayPlugin
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import eu.purse.partnerui.plugins.partners.klarna.KlarnaPlugin
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardPlugin
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * The **vault → generic template → integration_mode → partner → method fallback** priority, ported
 * case-for-case from web's own `getPlugin` suites (`packages/partner-ui/src/plugins/plugins.spec.ts`
 * and `plugins/generics/getGenericPlugin.spec.ts`), including the two negative cases web pins
 * explicitly: a near-miss template name, and `integration_mode: popup`.
 */
class PluginRegistryTest {

    private fun sessionWith(
        partner: Partner,
        method: Method,
        template: String? = null,
        vaultVendor: VaultVendor? = null,
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
                        template = template,
                        labels = emptyList(),
                        position = 1,
                        vault = vaultVendor?.let {
                            VaultInfo(
                                supportedBrands = emptyList(),
                                vendor = it,
                                tokenScope = null,
                                externalThreeDsEnabled = false,
                            )
                        },
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
    private val fintecture = Partner.Other("fintecture")
    private val instantPayment = Method.Other("instantpayment")

    @Test
    fun `a redirection template resolves to the redirection generic`() {
        val session = sessionWith(fintecture, instantPayment, template = RedirectionPlugin.TEMPLATE)
        val plugin = PluginRegistry.getPlugin(fintecture, instantPayment, session)

        assertTrue(plugin is RedirectionPlugin)
        // The generic takes its identity from the session, not from a hardcoded pair.
        assertTrue(plugin!!.partner == fintecture && plugin.method == instantPayment)
    }

    @Test
    fun `a template name that merely contains 'redirection' resolves to nothing`() {
        // Web asserts exactly this, in both directions — the match is strict equality.
        listOf("redirection_suffix", "prefix_redirection").forEach { template ->
            val session = sessionWith(fintecture, instantPayment, template = template)
            assertNull(PluginRegistry.getPlugin(fintecture, instantPayment, session))
        }
    }

    @Test
    fun `a vault vendor outranks a redirection template`() {
        val session = sessionWith(
            aci,
            Method.CreditCard,
            template = RedirectionPlugin.TEMPLATE,
            vaultVendor = VaultVendor.PURSE,
        )
        assertTrue(PluginRegistry.getPlugin(aci, Method.CreditCard, session) is CardPlugin)
    }

    @Test
    fun `integration_mode redirection routes to the redirection generic with no template at all`() {
        val session = sessionWith(aci, instantPayment, integrationMode = IntegrationMode.REDIRECTION)
        assertTrue(PluginRegistry.getPlugin(aci, instantPayment, session) is RedirectionPlugin)
    }

    @Test
    fun `integration_mode redirection outranks a dedicated partner plugin`() {
        // Web's getPartnerPlugin checks integration_mode BEFORE its own partner_plugins table.
        val session = sessionWith(
            Partner.Klarna,
            Method.Installments,
            integrationMode = IntegrationMode.REDIRECTION,
        )
        assertTrue(PluginRegistry.getPlugin(Partner.Klarna, Method.Installments, session) is RedirectionPlugin)
    }

    @Test
    fun `integration_mode popup does not route to the redirection generic`() {
        val session = sessionWith(Partner.Klarna, Method.Installments, integrationMode = IntegrationMode.POPUP)
        assertTrue(PluginRegistry.getPlugin(Partner.Klarna, Method.Installments, session) is KlarnaPlugin)
    }

    @Test
    fun `a plain method with no template, vault or integration mode falls back per method`() {
        val session = sessionWith(aci, Method.GooglePay)
        assertTrue(PluginRegistry.getPlugin(aci, Method.GooglePay, session) is GooglePayPlugin)
    }

    @Test
    fun `a method the session doesn't offer resolves to nothing`() {
        val session = sessionWith(fintecture, instantPayment, template = RedirectionPlugin.TEMPLATE)
        assertNull(PluginRegistry.getPlugin(aci, instantPayment, session))
    }

    @Test
    fun `disable wins over any registration`() {
        val session = sessionWith(fintecture, instantPayment, template = RedirectionPlugin.TEMPLATE)
        PluginRegistry.disable(fintecture, instantPayment)
        try {
            assertNull(PluginRegistry.getPlugin(fintecture, instantPayment, session))
        } finally {
            // PluginRegistry is a singleton — leaving this set would leak into every later test.
            PluginRegistry.enable(fintecture, instantPayment)
        }
        assertTrue(PluginRegistry.getPlugin(fintecture, instantPayment, session) is RedirectionPlugin)
    }
}
