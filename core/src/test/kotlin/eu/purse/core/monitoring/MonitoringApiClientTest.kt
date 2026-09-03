package eu.purse.core.monitoring

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The batch `MonitoringApiClient` posts must be web's `MonitoringApiEventMessage[]` — same route
 * (`…/widget/headless_checkout?api-key=…`), same `text/plain` body — plus the `platform` field the
 * native SecureFields loggers already send, so one facet tells web, Android and iOS apart.
 */
class MonitoringApiClientTest {

    private lateinit var server: HttpServer
    private var receivedPath: String? = null
    private var receivedQuery: String? = null
    private var receivedContentType: String? = null
    private var receivedBody: String? = null
    private val received = CountDownLatch(1)

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            receivedPath = exchange.requestURI.path
            receivedQuery = exchange.requestURI.query
            receivedContentType = exchange.requestHeaders.getFirst("Content-Type")
            receivedBody = exchange.requestBody.bufferedReader().readText()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
            received.countDown()
        }
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    private fun client() = MonitoringApiClient(
        apiKey = "key-123",
        monitoringApiRoot = "http://127.0.0.1:${server.address.port}/widget/headless_checkout",
        envWireValue = "sandbox",
    )

    @Test
    fun `posts web's envelope with platform android on every message`() {
        val client = client()
        client.patchExtras(entityId = "entity-1", paymentSessionId = "session-1", version = "0.1.0", wrapper = "direct")

        client.sendLogs(
            listOf(
                JSONObject().put("code", "HEADLESS_CHECKOUT_CREATE").put("type", "INFO"),
                JSONObject().put("code", "HEADLESS_CHECKOUT_READY").put("type", "INFO"),
            ),
        )

        assertTrue("batch never reached the server", received.await(5, TimeUnit.SECONDS))
        assertEquals("/widget/headless_checkout", receivedPath)
        assertEquals("api-key=key-123", receivedQuery)
        assertEquals("text/plain", receivedContentType)
        val batch = JSONArray(receivedBody)
        assertEquals(2, batch.length())
        for (i in 0 until batch.length()) {
            val message = batch.getJSONObject(i)
            assertEquals("android", message.getString("platform"))
            assertEquals("direct", message.getString("wrapper"))
            assertEquals("sandbox", message.getString("env"))
            assertEquals("entity-1", message.getString("entityId"))
            assertEquals("session-1", message.getString("paymentSessionId"))
            assertEquals("0.1.0", message.getString("version"))
            assertEquals(MonitoringApiClient.instanceId, message.getString("instanceId"))
            assertTrue(message.getJSONObject("event").getString("code").startsWith("HEADLESS_CHECKOUT_"))
        }
        assertEquals("HEADLESS_CHECKOUT_READY", batch.getJSONObject(1).getJSONObject("event").getString("code"))
    }

    @Test
    fun `an empty batch or a blank api key sends nothing`() {
        client().sendLogs(emptyList())
        MonitoringApiClient("", "http://127.0.0.1:${server.address.port}/widget/headless_checkout", "sandbox")
            .sendLogs(listOf(JSONObject().put("code", "X")))

        assertEquals(false, received.await(500, TimeUnit.MILLISECONDS))
    }
}
