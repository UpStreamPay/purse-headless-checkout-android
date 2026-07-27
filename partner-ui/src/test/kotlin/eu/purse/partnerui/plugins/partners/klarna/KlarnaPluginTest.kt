package eu.purse.partnerui.plugins.partners.klarna

import eu.purse.core.api.PaymentApiClient
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.partnerui.PartnerUiContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class KlarnaPluginTest {

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
        partner = Partner.Klarna,
        method = Method.Installments,
        session = session,
        amount = BigDecimal("4.80"),
        api = PaymentApiClient("k", "https://api.example", "e", "s-1"),
        collected = collected,
    )

    @Test
    fun `preValidation carries the collected step id and an empty body`() = runBlocking {
        val plugin = KlarnaPlugin(Partner.Klarna)
        val payload = plugin.preValidation(context(mapOf(KlarnaPlugin.COLLECTED_STEP_ID to 7)))

        assertEquals(Partner.Klarna, payload.partner)
        assertEquals(Method.Installments, payload.method)
        assertEquals(7, payload.stepId)
        assertEquals(0, payload.order.length())
        assertEquals(0, payload.partnerData.length())
    }

    @Test
    fun `preValidation has a null step id when none was collected`() = runBlocking {
        val payload = KlarnaPlugin(Partner.Klarna).preValidation(context(emptyMap()))
        assertNull(payload.stepId)
    }
}
