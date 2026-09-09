package eu.purse.checkout.monitoring

import com.sun.net.httpserver.HttpServer
import eu.purse.checkout.CheckoutTestSessions
import eu.purse.core.session.SessionCodec
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * The envelope the collector receives must describe the session an event belongs to — including
 * after `setSession` swapped it (web re-patches its extras on every `_setSession`).
 */
class CheckoutMonitoringTest {

    private lateinit var server: HttpServer
    private val batches = LinkedBlockingQueue<JSONArray>()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            batches.add(JSONArray(exchange.requestBody.bufferedReader().readText()))
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
        // The bus is a process singleton: leave no collector behind for the next test class.
        CheckoutEventBus.getInstance().setLogProcessor { }
    }

    private fun nextBatch(): JSONArray {
        val batch = batches.poll(5, TimeUnit.SECONDS)
        assertNotNull("batch never reached the server", batch)
        return batch!!
    }

    @Test
    fun `the envelope follows the session after a setSession`() {
        val params = SessionCodec.decode(CheckoutTestSessions.widgetData())
        val bus = CheckoutEventBus.getInstance()
        CheckoutMonitoring.install(
            params,
            sessionId = "session-123",
            version = "1.2.3",
            instanceId = "inst-42",
            monitoringApiRoot = "http://127.0.0.1:${server.address.port}/widget/headless_checkout",
        )

        bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_READY)
        bus.flush()
        val first = nextBatch().getJSONObject(0)
        assertEquals("session-123", first.getString("paymentSessionId"))
        assertEquals("entity-1", first.getString("entityId"))
        assertEquals("1.2.3", first.getString("version"))
        assertEquals("inst-42", first.getString("instanceId"))
        assertEquals("sandbox", first.getString("env"))
        assertEquals("android", first.getString("platform"))
        assertEquals("direct", first.getString("wrapper"))
        assertFalse("no wallet session in the fixture", first.has("walletSessionId"))

        CheckoutMonitoring.sessionChanged(params.copy(entityId = "entity-2"), "session-456")
        bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_SET_SESSION)
        bus.flush()
        val second = nextBatch().getJSONObject(0)
        assertEquals("session-456", second.getString("paymentSessionId"))
        assertEquals("entity-2", second.getString("entityId"))
        assertEquals("1.2.3", second.getString("version"))
        assertEquals("the instance outlives the session", "inst-42", second.getString("instanceId"))
    }
}
