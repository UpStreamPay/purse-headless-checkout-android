package eu.purse.checkout

import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.checkout.monitoring.CheckoutMonitoringEvent
import eu.purse.checkout.monitoring.EventLevel
import eu.purse.core.api.PaymentApiException
import eu.purse.core.api.ValidateGuard
import eu.purse.core.datetime.ServerDatetimeChecker
import eu.purse.partnerui.PaymentCoordinator
import eu.purse.partnerui.PaymentElementListener
import eu.purse.partnerui.PaymentOutcome
import eu.purse.partnerui.PluginHost
import eu.purse.partnerui.redirect.RedirectAction
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `HEADLESS_CHECKOUT_REDIRECTION` end to end (SDK-12712): a real checkout, a scripted `/validate`,
 * and the coordinator flow every payment element runs — so the refused-validation exit, the
 * merchant hook and the SDK's own WebView are each checked where they really happen.
 */
class RedirectionTelemetryTest {

    private val events = CopyOnWriteArrayList<CheckoutMonitoringEvent>()
    private lateinit var unsubscribe: () -> Unit
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun record() {
        ValidateGuard.reset() // process-wide; every test here submits the same session id
        unsubscribe = CheckoutEventBus.getInstance().subscribe(CheckoutEventBus.Scope.ALL) { events.add(it) }
    }

    @After
    fun stop() {
        unsubscribe()
        scope.cancel()
    }

    /** The smallest coordinator: no native step, straight to `/validate` and the shared redirect flow. */
    private class TestCoordinator(scope: CoroutineScope, listener: PaymentElementListener) : PaymentCoordinator(scope, listener) {
        override val tag = "TestCoordinator"

        fun pay(host: PluginHost) {
            attach(host)
            capture(host)
        }
    }

    private class Run(val launched: RedirectAction?, val ending: String)

    /** Pays the PayPal token through a [TestCoordinator] against [validate]; returns what the SDK launched. */
    private fun pay(validate: String, hooks: PurseCheckoutHooks? = null): Run = FakePaymentApi().use { api ->
        api.respond("init", """{ "status": { "state": "SUCCESS" }, "step_id": 31, "plugin_result": { "logs": {} } }""")
        api.respond("validate", validate)
        val checkout = PurseHeadlessCheckout.init(
            CheckoutTestSessions.widgetData(CheckoutTestSessions.paypalTokenSessionJson(), api.apiRoot),
            hooks = hooks,
            datetimeChecker = ServerDatetimeChecker(fetchServerTime = { throw PaymentApiException("offline") }),
            watchdogIntervalMs = 60_000,
        )
        val token = checkout.paymentTokens.value.single { it.id == "pp-1" }
        checkout.setPrimaryToken(token)

        val ending = CompletableDeferred<String>()
        val coordinator = TestCoordinator(
            scope,
            object : PaymentElementListener {
                override fun onRequiresAction() {
                    ending.complete("requiresAction")
                }

                override fun onOutcome(outcome: PaymentOutcome) {
                    ending.complete(outcome::class.simpleName.orEmpty())
                }
            },
        )
        var launched: RedirectAction? = null
        coordinator.launchRedirect = { launched = it }
        coordinator.pay(checkout.host(token))
        val end = runBlocking { withTimeout(10_000) { ending.await() } }
        Run(launched, end)
    }

    private fun redirections() = events.filter { it.code == CheckoutEventCode.HEADLESS_CHECKOUT_REDIRECTION }

    private fun refused(redirectionUrl: String) =
        """
        {"global_status":"ERROR",
         "results":[{"partner":"paypal","method":"wallet","status":{"state":"ERROR","code":"REFUSED"}}],
         "redirection":{"url":"$redirectionUrl"}}
        """

    private val failureUrl = "https://psp.example.com/fail/ORDER-1?email=shopper%40example.com&amount=4.80"

    @Test
    fun `a refused validation that sends the shopper away logs the destination hostname`() {
        val run = pay(refused(failureUrl))

        assertEquals("requiresAction", run.ending)
        assertEquals(RedirectAction.Get(failureUrl), run.launched)
        assertTrue(events.any { it.code == CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_ERROR })
        val event = redirections().single()
        assertEquals(EventLevel.INFO, event.type)
        val payload = event.payload!!
        assertEquals("psp.example.com", payload.getString("url"))
        assertFalse(payload.getBoolean("delegated"))
        assertFalse("hostname only", payload.toString().contains("ORDER-1") || payload.toString().contains("shopper"))
    }

    @Test
    fun `a redirection the onRedirection hook takes over is logged as delegated, the hook seeing the same action`() {
        val handed = mutableListOf<RedirectAction>()

        val run = pay(
            refused(failureUrl),
            hooks = PurseCheckoutHooks(onRedirection = { action ->
                handed += action
                true
            }),
        )

        assertEquals("requiresAction", run.ending)
        assertNull("the SDK opens nothing", run.launched)
        assertEquals(listOf(RedirectAction.Get(failureUrl)), handed)
        val payload = redirections().single().payload!!
        assertEquals("psp.example.com", payload.getString("url"))
        assertTrue(payload.getBoolean("delegated"))
    }

    @Test
    fun `a hook that declines lets the SDK open its WebView, logged as not delegated`() {
        val run = pay(refused(failureUrl), hooks = PurseCheckoutHooks(onRedirection = { false }))

        assertEquals(RedirectAction.Get(failureUrl), run.launched)
        assertFalse(redirections().single().payload!!.getBoolean("delegated"))
    }

    @Test
    fun `a 3DS form from the item's own logs is logged as action`() {
        val validate = JSONObject()
            .put("global_status", "PENDING")
            .put(
                "results",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("partner", "paypal")
                        .put("method", "wallet")
                        .put("status", JSONObject().put("state", "PENDING"))
                        .put(
                            "plugin_result",
                            JSONObject().put(
                                "logs",
                                JSONObject()
                                    .put("redirection_url", "https://acs.bank.example/3ds?TermUrl=x")
                                    .put("redirection_json", "PaReq=secret-pareq"),
                            ),
                        ),
                ),
            )

        val run = pay(validate.toString())

        assertTrue(run.launched is RedirectAction.PostForm)
        val payload = redirections().single().payload!!
        assertEquals("acs.bank.example", payload.getString("action"))
        assertFalse(payload.has("url"))
        assertFalse(payload.toString().contains("secret-pareq"))
    }

    @Test
    fun `a failing hook opens nothing and logs no redirection`() {
        val run = pay(refused(failureUrl), hooks = PurseCheckoutHooks(onRedirection = { error("no browser available") }))

        assertEquals("Failed", run.ending)
        assertNull(run.launched)
        assertTrue(redirections().isEmpty())
        assertTrue(events.any { it.code == CheckoutEventCode.HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR })
    }

    @Test
    fun `the widget confirmation page is not followed, so no redirection is logged`() {
        val run = pay(refused("https://merchant.example/confirmation?purse-redirection-data=a.b.c"))

        assertNull(run.launched)
        assertTrue(redirections().isEmpty())
    }
}
