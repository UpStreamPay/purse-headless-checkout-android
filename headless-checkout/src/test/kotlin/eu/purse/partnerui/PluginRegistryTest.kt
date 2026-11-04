package eu.purse.partnerui

import androidx.compose.runtime.Composable
import eu.purse.core.payload.ValidateItemPayload
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
import eu.purse.partnerui.plugins.generics.redirection.RedirectionModule
import eu.purse.partnerui.plugins.generics.redirection.RedirectionPlugin
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardModule
import eu.purse.partnerui.plugins.vaults.purse.creditcard.CardPlugin
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **vault → generic template → integration_mode → partner → method fallback** priority, ported
 * case-for-case from web's own `getPlugin` suites (`packages/partner-ui/src/plugins/plugins.spec.ts`
 * and `plugins/generics/getGenericPlugin.spec.ts`), including the two negative cases web pins
 * explicitly: a near-miss template name, and `integration_mode: popup`. The partner-specific cases
 * (Klarna, PayPal, Google Pay) live with their optional artifacts — here a fake [PartnerModule] stands
 * in for them, proving the exact-pair and method-fallback slots and the module seam itself.
 */
class PluginRegistryTest {

    /** A partner artifact in miniature: one exact pair, one method fallback. */
    private class AcmePlugin(override val partner: Partner, override val method: Method) : PartnerUiPlugin {
        override suspend fun preValidation(context: PartnerUiContext) = ValidateItemPayload(partner, method, amount = context.amount)
    }

    private object AcmeModule : PartnerModule {
        val acme = Partner.Other("acme")
        val wallet = Method.Other("acmewallet")
        val anyPartnerMethod = Method.Other("acmething")

        override fun register(registry: PluginRegistry) {
            registry.register(acme, wallet) { AcmePlugin(it.partner, it.method) }
            registry.registerFallback(anyPartnerMethod) { AcmePlugin(it.partner, it.method) }
        }

        override fun renders(plugin: PartnerUiPlugin) = plugin is AcmePlugin

        @Composable
        override fun rememberSurface(plugin: PartnerUiPlugin, request: SurfaceRequest) = PaymentSurface.headless { }
    }

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

    // SDK-12575: a template nothing registers does not stop the walk, as on web — `getGenericPlugin`
    // answers null and `getPartnerPlugin` carries on (web pins `'stripe_googlepay'` reaching Stripe's
    // partner plugin). An item left with nothing is the checkout's to retire at engagement.
    private val axepta = Partner.Other("axepta")
    private val wero = Method.Other("wero")

    @Test
    fun `an unknown template outside redirection mode resolves to nothing`() {
        // QA's axepta wero: no generic, no pair, no fallback — the checkout retires it.
        val session = sessionWith(axepta, wero, template = "axepta_wero")
        assertNull(PluginRegistry.getPlugin(axepta, wero, session))
    }

    @Test
    fun `an unknown template in redirection mode still reaches the redirection generic`() {
        // Web's getPartnerPlugin checks integration_mode after an unmatched template, so the merchant's
        // redirection configuration is served, not refused.
        val session = sessionWith(axepta, wero, template = "axepta_wero", integrationMode = IntegrationMode.REDIRECTION)
        assertTrue(PluginRegistry.getPlugin(axepta, wero, session) is RedirectionPlugin)
    }

    @Test
    fun `an unknown template does not hide an exact pair or a method fallback`() {
        // Web's `'stripe_googlepay'` case: a template only the partner plugin understands. On Android
        // Google Pay sits in the method fallback, whatever template the session names.
        PluginRegistry.install(AcmeModule)

        val exactSession = sessionWith(AcmeModule.acme, AcmeModule.wallet, template = "acme_wallet")
        assertTrue(PluginRegistry.getPlugin(AcmeModule.acme, AcmeModule.wallet, exactSession) is AcmePlugin)
        val fallbackSession = sessionWith(fintecture, AcmeModule.anyPartnerMethod, template = "googlepay")
        assertTrue(PluginRegistry.getPlugin(fintecture, AcmeModule.anyPartnerMethod, fallbackSession) is AcmePlugin)
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
    fun `paypal wallet in redirection mode stays on the redirection generic`() {
        // Deliberate: web's own redirection generic lists a redirection-mode PayPal wallet as one of
        // the methods it serves, and integration_mode outranks the exact pair.
        val session = sessionWith(Partner.Paypal, Method.Wallet, integrationMode = IntegrationMode.REDIRECTION)
        assertTrue(PluginRegistry.getPlugin(Partner.Paypal, Method.Wallet, session) is RedirectionPlugin)
    }

    @Test
    fun `an installed module's exact pair and method fallback resolve, and the module renders them`() {
        PluginRegistry.install(AcmeModule)

        val exact = PluginRegistry.getPlugin(AcmeModule.acme, AcmeModule.wallet, sessionWith(AcmeModule.acme, AcmeModule.wallet))
        assertTrue(exact is AcmePlugin)
        val fallbackSession = sessionWith(fintecture, AcmeModule.anyPartnerMethod)
        val fallback = PluginRegistry.getPlugin(fintecture, AcmeModule.anyPartnerMethod, fallbackSession)
        assertTrue(fallback is AcmePlugin && fallback.partner == fintecture)
        assertSame(AcmeModule, PluginRegistry.moduleFor(exact!!))
    }

    @Test
    fun `installing a module twice registers it once, and the built-ins come first`() {
        PluginRegistry.install(AcmeModule)
        PluginRegistry.install(AcmeModule)

        assertEquals(1, PluginRegistry.modules.count { it === AcmeModule })
        assertEquals(listOf(CardModule, RedirectionModule), PluginRegistry.modules.take(2))
    }

    @Test
    fun `the built-in modules render their own plugins and nothing else`() {
        val card = PluginRegistry.getPlugin(aci, Method.CreditCard, sessionWith(aci, Method.CreditCard, vaultVendor = VaultVendor.PURSE))!!
        val redirectionSession = sessionWith(fintecture, instantPayment, template = RedirectionPlugin.TEMPLATE)
        val redirection = PluginRegistry.getPlugin(fintecture, instantPayment, redirectionSession)!!

        assertSame(CardModule, PluginRegistry.moduleFor(card))
        assertSame(RedirectionModule, PluginRegistry.moduleFor(redirection))
        assertTrue(card is CardPlugin && redirection is RedirectionPlugin)
    }

    @Test
    fun `a method the session doesn't offer resolves to nothing`() {
        val session = sessionWith(fintecture, instantPayment, template = RedirectionPlugin.TEMPLATE)
        assertNull(PluginRegistry.getPlugin(aci, instantPayment, session))
    }
}
