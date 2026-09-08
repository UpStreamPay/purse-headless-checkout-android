package eu.purse.checkout

import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.checkout.monitoring.CheckoutMonitoringEvent
import eu.purse.core.api.PaymentApiException
import eu.purse.core.datetime.ServerDatetimeChecker
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
