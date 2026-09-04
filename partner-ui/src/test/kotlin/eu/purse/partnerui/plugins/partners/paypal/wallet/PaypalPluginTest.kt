package eu.purse.partnerui.plugins.partners.paypal.wallet

import com.sun.net.httpserver.HttpServer
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.api.PaymentApiException
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PayToken
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
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

    /**
     * A loopback Payment API answering every POST with [answer] — the SDK's client is a plain
     * `HttpURLConnection`, so the JDK's own `HttpServer` is enough to script the saved-token `/init`.
     */
    private fun withApi(answer: String, block: (api: PaymentApiClient, sent: List<JSONObject>) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sent = mutableListOf<JSONObject>()
        server.createContext("/") { exchange ->
            sent += JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = answer.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(PaymentApiClient("k", "http://127.0.0.1:${server.address.port}/payment/v1", "e", "s-1"), sent)
        } finally {
            server.stop(0)
        }
    }

    private fun tokenContext(api: PaymentApiClient, token: PayToken) = PartnerUiContext(
        partner = Partner.Paypal,
        method = Method.Wallet,
        session = session,
        amount = BigDecimal("4.80"),
        api = api,
        token = token,
    )

    @Test
    fun `preValidation with a saved token inits first, then sends the token and the init step id`() =
        withApi("""{ "status": { "state": "SUCCESS" }, "step_id": 42, "plugin_result": { "logs": {} } }""") { api, sent ->
            val payload = runBlocking {
                PaypalPlugin(Partner.Paypal).preValidation(tokenContext(api, PayToken(value = "pp-tok-1")))
            }

            // One /init, the pay-now body — web's token-mode initSession posts exactly this.
            val init = sent.single()
            assertEquals("paypal", init.getString("partner"))
            assertEquals("wallet", init.getString("method"))
            assertEquals(0, BigDecimal("4.80").compareTo(BigDecimal(init.get("amount").toString())))
            assertFalse(init.getJSONObject("payment_preferences").getBoolean("registration"))

            // The /validate item: envelope, the init's step id, and the token patched in.
            assertEquals(Partner.Paypal, payload.partner)
            assertEquals(Method.Wallet, payload.method)
            assertEquals(42, payload.stepId)
            assertEquals("pp-tok-1", payload.paymentData.getString("token"))
            // No card behind a PayPal token: no card block, no CVV vault token, nothing collected.
            assertFalse(payload.paymentData.has("card"))
            assertFalse(payload.paymentData.has("holder"))
            assertFalse(payload.paymentData.has("vault_vendor_token_id"))
            assertEquals(0, payload.partnerData.length())
            assertEquals(0, payload.order.length())
        }

    @Test
    fun `preValidation with a saved token needs no order_id off the init`() =
        withApi("""{ "status": { "state": "SUCCESS" } }""") { api, _ ->
            val payload = runBlocking {
                PaypalPlugin(Partner.Paypal).preValidation(tokenContext(api, PayToken(value = "pp-tok-1")))
            }
            assertNull(payload.stepId)
            assertEquals("pp-tok-1", payload.paymentData.getString("token"))
        }

    @Test
    fun `preValidation with a saved token surfaces a failed init`() =
        withApi("""{ "status": { "state": "FAILED", "description": "nope" } }""") { api, _ ->
            assertThrows(PaymentApiException::class.java) {
                runBlocking { PaypalPlugin(Partner.Paypal).preValidation(tokenContext(api, PayToken(value = "pp-tok-1"))) }
            }
        }

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
