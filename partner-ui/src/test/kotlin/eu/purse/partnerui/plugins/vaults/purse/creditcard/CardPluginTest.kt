package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.core.api.PaymentApiClient
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.partnerui.PartnerUiContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class CardPluginTest {

    private val session = LegacyPaymentSession(
        id = "s-1",
        amount = BigDecimal("4.80"),
        informationRequest = false,
        currencyCode = "EUR",
        expirationDate = Instant.parse("2999-01-01T00:00:00Z"),
        success = null,
        failure = null,
        redirection = null,
        protocols = emptyMap(),
        vault = null,
    )

    private fun context(collected: Map<String, Any?>) = PartnerUiContext(
        partner = Partner.Purse,
        method = Method.CreditCard,
        session = session,
        amount = BigDecimal("4.80"),
        api = PaymentApiClient("k", "https://api.example", "e", "s-1"),
        collected = collected,
    )

    @Test
    fun `validateUi refuses an item whose form never produced a vault token`() = runBlocking {
        val plugin = CardPlugin(Partner.Purse)

        assertFalse(plugin.validateUi(context(emptyMap())))
        assertTrue(plugin.validateUi(context(mapOf(CardPlugin.COLLECTED_TOKEN to "vft-1"))))
    }
}
