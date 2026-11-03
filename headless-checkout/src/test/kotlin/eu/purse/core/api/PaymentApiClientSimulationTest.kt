package eu.purse.core.api

import com.sun.net.httpserver.HttpServer
import eu.purse.core.payment.LoanProduct
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import java.math.BigDecimal
import java.net.InetSocketAddress
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Drives [PaymentApiClient.getSimulation] / [PaymentApiClient.getSimulationMulti] against a local
 * HTTP server: the simulation endpoints are the only ones **not** scoped under
 * `/sessions/{sessionId}`, so the URL shape is worth pinning.
 */
class PaymentApiClientSimulationTest {

    private lateinit var server: HttpServer
    private var lastPath: String? = null
    private var lastMethod: String? = null
    private var lastApiKey: String? = null
    private var lastBody: String? = null
    private var responseStatus = 200
    private var responseBody = "{}"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            lastPath = exchange.requestURI.path
            lastMethod = exchange.requestMethod
            lastApiKey = exchange.requestHeaders.getFirst("x-api-key")
            lastBody = exchange.requestBody.bufferedReader().readText()
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    private fun client() = PaymentApiClient(
        apiKey = "key-1",
        apiRoot = "http://127.0.0.1:${server.address.port}/payment/v1/",
        entityId = "entity-1",
        sessionId = "session-1",
    )

    private val product = LoanProduct(BigDecimal("200.00"), "EUR")

    @Test
    fun `getSimulation posts the product to the entity-scoped simulation path`() {
        responseBody = """{"plugin_result": {"initial_instalment_amount": 70.34, "instalment_amount": 66.67,
            "final_instalment_amount": 66.66, "instalment_number": 2, "total_cost": 3.67}}"""

        val simulation = client().getSimulation(Partner.Other("oney"), Method.Installments, product)

        assertEquals("POST", lastMethod)
        assertEquals("/payment/v1/simulation/entity-1/oney/installments", lastPath)
        assertEquals("key-1", lastApiKey)
        val body = JSONObject(lastBody!!)
        assertEquals("EUR", body.getString("currency_code"))
        assertEquals(0, BigDecimal("200").compareTo(BigDecimal(body.get("amount").toString())))
        assertEquals(2, simulation.installmentCount)
        assertEquals(BigDecimal("203.67"), simulation.totalAmount)
    }

    @Test
    fun `a 2xx answer without plugin_result is INVALID_LOAN_SIMULATION`() {
        responseBody = """{"status": {"state": "ERROR"}}"""

        val error = assertThrows(PaymentApiException::class.java) {
            client().getSimulation(Partner.Other("oney"), Method.Installments, product)
        }

        assertEquals(PaymentApiClient.INVALID_LOAN_SIMULATION, error.errorCode)
        assertEquals("/simulation/entity-1/oney/installments", error.path)
    }

    @Test
    fun `an HTTP failure surfaces as a plain API exception with its status`() {
        responseStatus = 502
        responseBody = """{"error": "PARTNER_UNAVAILABLE"}"""

        val error = assertThrows(PaymentApiException::class.java) {
            client().getSimulation(Partner.Other("oney"), Method.Installments, product)
        }

        assertEquals(502, error.status)
        assertEquals("PARTNER_UNAVAILABLE", error.errorCode)
    }

    @Test
    fun `getSimulationMulti hits the multi path and skips candidates without plugin_result`() {
        responseBody = """[
            {"plugin_result": {"initial_instalment_amount": 50, "instalment_amount": 50,
              "final_instalment_amount": 50, "instalment_number": 4, "total_cost": 0}},
            {"status": {"state": "ERROR"}},
            {"plugin_result": {"initial_instalment_amount": 20, "instalment_amount": 20,
              "final_instalment_amount": 20, "instalment_number": 10, "total_cost": 0}}
        ]"""

        val simulations = client().getSimulationMulti(Partner.Other("oney"), Method.Installments, product)

        assertEquals("/payment/v1/simulation/entity-1/oney/installments/multi", lastPath)
        assertEquals(listOf(4, 10), simulations.map { it.installmentCount })
    }

    @Test
    fun `session-scoped calls still live under sessions`() {
        responseBody = "[]"

        client().getSession()

        assertEquals("GET", lastMethod)
        assertEquals("/payment/v1/entity-1/sessions/session-1", lastPath)
    }
}
