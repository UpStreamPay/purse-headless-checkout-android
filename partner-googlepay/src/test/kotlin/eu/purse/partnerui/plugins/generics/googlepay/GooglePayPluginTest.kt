package eu.purse.partnerui.plugins.generics.googlepay

import eu.purse.core.api.PaymentApiClient
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.partnerui.PartnerUiContext
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GooglePayPluginTest {

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
        partner = Partner.Other("ingenico"),
        method = Method.GooglePay,
        session = session,
        amount = BigDecimal("4.80"),
        api = PaymentApiClient("k", "https://api.example", "e", "s-1"),
        collected = collected,
    )

    @Test
    fun `preValidation maps the collected token and network into the validate payload`() = runBlocking {
        val plugin = GooglePayPlugin(Partner.Other("ingenico"))
        val payload = plugin.preValidation(
            context(
                mapOf(
                    GooglePayPlugin.COLLECTED_TOKEN to "tok-abc",
                    GooglePayPlugin.COLLECTED_NETWORK to "VISA",
                ),
            ),
        )

        assertEquals(Partner.Other("ingenico"), payload.partner)
        assertEquals(Method.GooglePay, payload.method)
        val session = JSONObject(payload.partnerData.getString("session")).getJSONObject("paymentMethod")
        assertEquals("tok-abc", session.getString("googlePayToken"))
        assertEquals("VISA", payload.paymentData.getJSONObject("card").getString("selected_network"))
    }

    @Test(expected = IllegalStateException::class)
    fun `preValidation fails when no token was collected`() = runBlocking {
        GooglePayPlugin(Partner.Other("ingenico")).preValidation(context(emptyMap()))
        Unit
    }
}
