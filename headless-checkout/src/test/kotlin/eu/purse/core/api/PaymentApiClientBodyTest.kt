package eu.purse.core.api

import com.sun.net.httpserver.HttpServer
import eu.purse.core.payment.LoanProduct
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import java.math.BigDecimal
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * A success status with a body that is not JSON (a captive portal or WAF page): the
 * [PaymentApiException] names the call but never carries the body — not in its message, not through
 * a chained `JSONException`, whose message quotes the input.
 */
class PaymentApiClientBodyTest {

    private lateinit var server: HttpServer
    private val body = "<html>captive portal SECRET-4242</html>"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
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

    private fun assertBodyNotCarried(error: PaymentApiException, path: String) {
        assertEquals(path, error.path)
        assertNull(error.cause)
        assertFalse(error.message!!.contains("SECRET-4242"))
        assertFalse(error.stackTraceToString().contains("SECRET-4242"))
    }

    @Test
    fun `a non-JSON object answer keeps the body out of the exception`() {
        val product = LoanProduct(BigDecimal("200.00"), "EUR")
        val error = assertThrows(PaymentApiException::class.java) {
            client().getSimulation(Partner.Other("oney"), Method.Installments, product)
        }
        assertBodyNotCarried(error, "/simulation/entity-1/oney/installments")
    }

    @Test
    fun `a non-JSON array answer keeps the body out of the exception`() {
        val product = LoanProduct(BigDecimal("200.00"), "EUR")
        val error = assertThrows(PaymentApiException::class.java) {
            client().getSimulationMulti(Partner.Other("oney"), Method.Installments, product)
        }
        assertBodyNotCarried(error, "/simulation/entity-1/oney/installments/multi")
    }

    @Test
    fun `a non-JSON session answer keeps the body out of the exception`() {
        val error = assertThrows(PaymentApiException::class.java) { client().getSession() }
        assertBodyNotCarried(error, "")
    }
}
