package eu.purse.checkout

import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.checkout.monitoring.CheckoutMonitoringEvent
import eu.purse.checkout.monitoring.EventLevel
import eu.purse.checkout.state.CheckoutSavedState
import eu.purse.core.api.PaymentApiException
import eu.purse.core.datetime.ServerDatetimeChecker
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.PluginEvent
import eu.purse.partnerui.PluginRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        eu.purse.core.api.ValidateGuard.reset() // process-wide; every test here submits the same session id
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

    /** Wait for an asynchronous post (eligibility checks run on the engine scope). */
    private fun await(code: CheckoutEventCode, timeoutMs: Long = 5_000): CheckoutMonitoringEvent {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            events.firstOrNull { it.code == code }?.let { return it }
            Thread.sleep(20)
        }
        error("no $code within ${timeoutMs}ms; saw ${events.map { it.code }}")
    }

    @Test
    fun `a platform suspension is a METHOD_DISABLED event at INFO, not an error`() {
        val suspended = CheckoutTestSessions.samplePaymentSessionJson
            .replace("\"stable\": true, \"labels\": [\"paypal\"", "\"stable\": false, \"labels\": [\"paypal\"")
        checkout(CheckoutTestSessions.widgetData(suspended))

        val disabled = single(CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_DISABLED)
        assertEquals(EventLevel.INFO, disabled.type)
        val payload = disabled.payload!!
        assertEquals("paypal", payload.getString("partner"))
        assertEquals("wallet", payload.getString("method"))
        assertEquals("METHOD_UNSTABLE", payload.getString("code"))
        assertTrue(payload.getBoolean("filtered"))
        assertFalse("only the code travels — a state's message can quote amounts", payload.has("message"))
        // Posted before READY, so a dashboard sees the session's shape at ready time.
        assertTrue(events.indexOf(disabled) < events.indexOf(single(CheckoutEventCode.HEADLESS_CHECKOUT_READY)))
    }

    @Test
    fun `a plugin refusing the client logs web's pluginIsIncompatibleWithClient as a WARNING`() {
        PluginRegistry.register(Partner.Aci, Method.Wallet) { data ->
            object : PartnerUiPlugin {
                override val partner = data.partner
                override val method = data.method
                override suspend fun ensureClientCompatibility(context: PartnerUiContext) {
                    error("no wallet app on this device")
                }
                override suspend fun preValidation(context: PartnerUiContext) = error("never reached")
            }
        }
        val session = JSONObject(CheckoutTestSessions.samplePaymentSessionJson)
        session.getJSONObject("protocols").put(
            "aci",
            JSONObject(
                """
                { "wallet": { "configurations": {
                    "stable": true, "labels": ["aci", "wallet", "primary"], "position": 9,
                    "settings": { "registration_mode": "OFF_SESSION" } } } }
                """,
            ),
        )
        checkout(CheckoutTestSessions.widgetData(session.toString()))

        val uiEvent = await(CheckoutEventCode.HEADLESS_CHECKOUT_PARTNER_UI_EVENT)
        assertEquals("a nominal deactivation is never status:error (SDK-12071)", EventLevel.WARNING, uiEvent.type)
        assertEquals("pluginIsIncompatibleWithClient", uiEvent.payload!!.getString("code"))
        assertEquals("aci", uiEvent.payload!!.getString("partner"))
        assertEquals("no wallet app on this device", uiEvent.payload!!.getString("reason"))

        val disabled = await(CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_DISABLED)
        assertEquals(EventLevel.INFO, disabled.type)
        assertEquals("PLUGIN_INCOMPATIBLE", disabled.payload!!.getString("code"))
        assertEquals("aci", disabled.payload!!.getString("partner"))
    }

    @Test
    fun `partner-UI events carry web's levels and the method's resulting disabled state follows`() {
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }

        checkout.pluginContext(paypal).dispatchEvent(PluginEvent.PartnerError("blip", recoverable = true))
        val recoverable = single(CheckoutEventCode.HEADLESS_CHECKOUT_PARTNER_UI_EVENT)
        assertEquals("a failure the method recovers from is a warning", EventLevel.WARNING, recoverable.type)
        assertEquals("blip", recoverable.payload!!.getString("reason"))
        val dimmed = single(CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_DISABLED)
        assertEquals(EventLevel.INFO, dimmed.type)
        assertEquals("PARTNER_UI_RECOVERABLE_ERROR", dimmed.payload!!.getString("code"))
        assertTrue(dimmed.payload!!.getBoolean("recoverable"))
        events.clear()

        checkout.pluginContext(paypal).dispatchEvent(PluginEvent.ClientError("sdk died"))
        assertEquals(EventLevel.ERROR, single(CheckoutEventCode.HEADLESS_CHECKOUT_PARTNER_UI_EVENT).type)
        val fatal = single(CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_DISABLED)
        assertEquals("a technical failure is a warning on the method", EventLevel.WARNING, fatal.type)
        assertEquals("PARTNER_UI_FATAL_ERROR", fatal.payload!!.getString("code"))
        events.clear()

        // The same state again is not a transition: nothing new to report.
        checkout.pluginContext(paypal).dispatchEvent(PluginEvent.ClientError("sdk died again"))
        assertTrue(events.none { it.code == CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_DISABLED })
    }

    @Test
    fun `SET_SESSION names the session and its shape, never the amount`() {
        checkout()

        val payload = payload(CheckoutEventCode.HEADLESS_CHECKOUT_SET_SESSION)
        assertEquals("session-123", payload.getString("paymentSessionId"))
        assertTrue(payload.getInt("nb_methods") > 0)
        assertFalse("web sends the amount; the Android SDK keeps raw amounts out of telemetry", payload.has("amount"))
        assertFalse(payload.toString().contains("4.8"))
    }

    @Test
    fun `each checkout has its own instanceId, kept across setSession and restore`() {
        val first = checkout()
        val other = checkout()
        assertTrue(first.instanceId.isNotBlank())
        assertNotEquals("two checkouts in one process are two instances", first.instanceId, other.instanceId)
        val creates = events.filter { it.code == CheckoutEventCode.HEADLESS_CHECKOUT_CREATE }
        assertEquals(2, creates.size)
        assertTrue("a fresh checkout is not a restored one", creates.none { it.payload?.has("restored") == true })

        val id = first.instanceId
        val refreshed = CheckoutTestSessions.samplePaymentSessionJson.replace("session-123", "session-456")
        runBlocking { first.setSession(CheckoutTestSessions.widgetData(refreshed)) }
        assertEquals("session-456", first.currentSessionId)
        assertEquals("a new session is the same instance", id, first.instanceId)

        events.clear()
        val restored = PurseHeadlessCheckout.restore(first.saveState(), datetimeChecker = offlineChecker(), watchdogIntervalMs = 60_000)
        assertEquals("process death does not start a new instance", first.instanceId, restored.instanceId)
        assertTrue(payload(CheckoutEventCode.HEADLESS_CHECKOUT_CREATE).getBoolean("restored"))
    }

    @Test
    fun `a snapshot without an instanceId (older build) restores with a fresh one`() {
        val checkout = checkout()
        val json = JSONObject(checkout.saveState().toJson())
        json.remove("instanceId")

        val restored = PurseHeadlessCheckout.restore(
            CheckoutSavedState.fromJson(json.toString()),
            datetimeChecker = offlineChecker(),
            watchdogIntervalMs = 60_000,
        )

        assertTrue(restored.instanceId.isNotBlank())
        assertNotEquals(checkout.instanceId, restored.instanceId)
    }
}
