package eu.purse.partnerui.plugins.partners.paypal.wallet

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

class PaypalPluginTest {

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
        partner = Partner.Paypal,
        method = Method.Wallet,
        session = session,
        amount = BigDecimal("4.80"),
        api = PaymentApiClient("k", "https://api.example", "e", "s-1"),
        collected = collected,
    )

    @Test
    fun `preValidation carries the collected step id and an empty body`() = runBlocking {
        val plugin = PaypalPlugin(Partner.Paypal)
        val payload = plugin.preValidation(context(mapOf(PaypalPlugin.COLLECTED_STEP_ID to 7)))

        assertEquals(Partner.Paypal, payload.partner)
        assertEquals(Method.Wallet, payload.method)
        assertEquals(BigDecimal("4.80"), payload.amount)
        assertEquals(7, payload.stepId)
        assertEquals(0, payload.order.length())
        assertEquals(0, payload.partnerData.length())
    }

    @Test
    fun `preValidation has a null step id when none was collected`() = runBlocking {
        val payload = PaypalPlugin(Partner.Paypal).preValidation(context(emptyMap()))
        assertNull(payload.stepId)
    }

    @Test
    fun `the approved order and payer ids are diagnostic only and never reach the payload`() = runBlocking {
        val payload = PaypalPlugin(Partner.Paypal).preValidation(
            context(
                mapOf(
                    PaypalPlugin.COLLECTED_STEP_ID to 7,
                    PaypalPlugin.COLLECTED_ORDER_ID to "ORDER-123",
                    PaypalPlugin.COLLECTED_PAYER_ID to "PAYER-9",
                ),
            ),
        )

        // The backend re-reads the approval off the step_id — see PaypalPlugin.preValidation.
        assertEquals(0, payload.partnerData.length())
        assertEquals(0, payload.paymentData.length())
        assertEquals(7, payload.stepId)
    }
}
