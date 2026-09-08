package eu.purse.checkout

import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.checkout.monitoring.CheckoutMonitoringEvent
import eu.purse.checkout.monitoring.EventLevel
import eu.purse.core.api.PaymentApiException
import eu.purse.core.datetime.ServerDatetimeChecker
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the checkout tells the monitoring pipeline (SDK-12220): the same event names as web, the
 * device context RUN needs, per-attempt failure detail, and nothing that must not leave the device.
 */
class CheckoutTelemetryTest {

    private val events = CopyOnWriteArrayList<CheckoutMonitoringEvent>()
    private lateinit var unsubscribe: () -> Unit

    @Before
    fun record() {
        unsubscribe = CheckoutEventBus.getInstance().subscribe(CheckoutEventBus.Scope.ALL) { events.add(it) }
    }

    @After
    fun stop() = unsubscribe()

    /** A checker that never syncs (no network) and trusts the local clock. */
    private fun offlineChecker() = ServerDatetimeChecker(fetchServerTime = { throw PaymentApiException("offline") })

    private fun checkout(widgetData: String = CheckoutTestSessions.widgetData()) =
        PurseHeadlessCheckout.init(widgetData, datetimeChecker = offlineChecker(), watchdogIntervalMs = 60_000)

    private fun single(code: CheckoutEventCode): CheckoutMonitoringEvent = events.single { it.code == code }

    private fun payload(code: CheckoutEventCode): JSONObject = single(code).payload ?: JSONObject()

    @Test
    fun `CREATE carries the device context next to the user agent`() {
        checkout()

        val create = payload(CheckoutEventCode.HEADLESS_CHECKOUT_CREATE)
        assertEquals("v2", create.getString("sessionType"))
        assertTrue(create.getString("user_agent").isNotBlank())
        // Off-device (Android stubs) only the constant survives; on a device Build fills the rest.
        assertEquals("android", create.getJSONObject("device").getString("os"))
        assertFalse("no screen off-device", create.has("screen"))
    }

    /** A checkout whose PayPal token pays without a form — the shortest road to a real `/validate`. */
    private fun payableCheckout(api: FakePaymentApi): PurseHeadlessCheckout {
        api.respond("init", """{ "status": { "state": "SUCCESS" }, "step_id": 31, "plugin_result": { "logs": {} } }""")
        val checkout = checkout(CheckoutTestSessions.widgetData(CheckoutTestSessions.paypalTokenSessionJson(), api.apiRoot))
        checkout.setPrimaryToken(checkout.paymentTokens.value.single { it.id == "pp-1" })
        return checkout
    }

    @Test
    fun `a refused validate says why, attempt by attempt, on VALIDATION_FAILED and PAYMENT_ERROR`() {
        FakePaymentApi().use { api ->
            api.respond(
                "validate",
                """
                {"global_status":"ERROR","results":[
                  {"partner":"paypal","method":"wallet","amount":4.8,"id":"tx-9",
                   "status":{"state":"ERROR","code":"TECHNICAL_ISSUE_TO_CONTACT_PARTNER","description":"declined"},
                   "plugin_result":{"status":"DECLINED","off_session_data":{"secret":1}}},
                  {"partner":"illicado","method":"giftcard","status":{"state":"SUCCESS"}}
                ]}
                """,
            )
            val checkout = payableCheckout(api)

            runBlocking { checkout.submitPayment() }

            val failed = single(CheckoutEventCode.HEADLESS_CHECKOUT_VALIDATION_FAILED)
            assertEquals("a refusal is a warning here, the error is PAYMENT_ERROR's", EventLevel.WARNING, failed.type)
            assertEquals("ERROR", failed.payload!!.getString("global_status"))
            assertEquals(2, failed.payload!!.getJSONArray("results").length())

            val error = single(CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_ERROR)
            assertEquals(EventLevel.ERROR, error.type)
            val payload = error.payload!!
            assertEquals("validate answered ERROR", payload.getString("message"))
            assertEquals("ERROR", payload.getString("api_status"))
            assertFalse(payload.getBoolean("has_redirection"))
            val attempts = payload.getJSONArray("results")
            assertEquals("only the failed leg", 1, attempts.length())
            assertEquals("DECLINED", attempts.getJSONObject(0).getString("plugin_status"))
            val status = attempts.getJSONObject(0).getJSONObject("status")
            assertEquals("TECHNICAL_ISSUE_TO_CONTACT_PARTNER", status.getString("code"))
            val text = payload.toString()
            val leaked = text.contains("4.8") || text.contains("tx-9") || text.contains("secret")
            assertFalse("no amount, no transaction id, no partner payload", leaked)
        }
    }

    @Test
    fun `a failed validate call names the code and the API answer, not the response body`() {
        FakePaymentApi().use { api ->
            api.on("validate") { 502 to """{"error":"PARTNER_DOWN","details":"very private body"}""" }
            val checkout = payableCheckout(api)

            assertThrows(PurseCheckoutException.ValidateFailed::class.java) { runBlocking { checkout.submitPayment() } }

            val payload = payload(CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_ERROR)
            assertEquals("VALIDATE_FAILED", payload.getString("code"))
            assertEquals("/validate", payload.getString("api_path"))
            assertEquals(502, payload.getInt("api_status"))
            assertEquals("PARTNER_DOWN", payload.getString("api_error"))
            assertFalse(payload.toString().contains("very private body"))
            assertTrue(payload.getString("stack").length <= 1_000)
        }
    }
}
