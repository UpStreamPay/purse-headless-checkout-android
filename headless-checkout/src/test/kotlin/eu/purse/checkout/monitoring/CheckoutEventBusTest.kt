package eu.purse.checkout.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutEventBusTest {

    @Test
    fun `a public-scope subscriber only sees web's six public codes`() {
        val bus = CheckoutEventBus.getInstance()
        val publicSeen = mutableListOf<CheckoutEventCode>()
        val allSeen = mutableListOf<CheckoutEventCode>()
        val unsubPublic = bus.subscribe(CheckoutEventBus.Scope.PUBLIC) { publicSeen.add(it.code) }
        val unsubAll = bus.subscribe(CheckoutEventBus.Scope.ALL) { allSeen.add(it.code) }

        try {
            bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_READY)
            bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_SET_PRIMARY)
            bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_ERROR, EventLevel.ERROR)
        } finally {
            unsubPublic()
            unsubAll()
        }

        assertEquals(
            listOf(CheckoutEventCode.HEADLESS_CHECKOUT_READY, CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_ERROR),
            publicSeen,
        )
        assertTrue(allSeen.contains(CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_SET_PRIMARY))
    }

    @Test
    fun `events flow into the batch queue and reach the log processor on flush`() {
        val bus = CheckoutEventBus.getInstance()
        val logged = mutableListOf<CheckoutMonitoringEvent>()
        bus.setLogProcessor { logged.addAll(it) }

        try {
            bus.post(
                CheckoutEventCode.HEADLESS_CHECKOUT_SET_SESSION,
                payload = org.json.JSONObject().put("paymentSessionId", "s-1"),
            )
            bus.flush()
        } finally {
            bus.setLogProcessor { }
        }

        val event = logged.last()
        assertEquals(CheckoutEventCode.HEADLESS_CHECKOUT_SET_SESSION, event.code)
        val json = event.toJson()
        assertEquals("HEADLESS_CHECKOUT_SET_SESSION", json.getString("code"))
        assertEquals("s-1", json.getJSONObject("payload").getString("paymentSessionId"))
        assertTrue(json.getString("dateString").isNotBlank())
    }

    @Test
    fun `a throwing subscriber never breaks a post`() {
        val bus = CheckoutEventBus.getInstance()
        var secondSaw = false
        val unsub1 = bus.subscribe(CheckoutEventBus.Scope.ALL) { throw IllegalStateException("bad listener") }
        val unsub2 = bus.subscribe(CheckoutEventBus.Scope.ALL) { secondSaw = true }

        try {
            bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_READY)
        } finally {
            unsub1()
            unsub2()
        }

        assertTrue(secondSaw)
    }
}
