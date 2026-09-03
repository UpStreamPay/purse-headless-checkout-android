package eu.purse.checkout

import com.sun.net.httpserver.HttpServer
import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.core.api.PaymentApiException
import eu.purse.core.datetime.ServerDatetimeChecker
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.payment.LoanProduct
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.Base64

/**
 * `simulateLoan` / `simulateLoans` — the `simulable` label gate, the dedicated error code, the
 * monitoring event, and the happy path against a local simulation endpoint.
 */
class LoanSimulationCheckoutTest {

    private lateinit var server: HttpServer
    private var lastPath: String? = null
    private var lastBody: String? = null
    private var responseBody = "{}"
    private var responseStatus = 200

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            lastPath = exchange.requestURI.path
            lastBody = exchange.requestBody.bufferedReader().readText()
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    /**
     * The shared fixture, with PayPal's `wallet` flagged `simulable` and `api_url` pointed at the
     * local server (the checkout trusts `api_url` when no environment lock is passed).
     */
    private fun simulableWidgetData(): String {
        val root = JSONObject(String(Base64.getDecoder().decode(CheckoutTestSessions.widgetData())))
        root.put("api_url", "http://127.0.0.1:${server.address.port}/payment/v1")
        root.getJSONObject("payment_session").getJSONObject("protocols")
            .getJSONObject("paypal").getJSONObject("wallet").getJSONObject("configurations")
            .getJSONArray("labels").put("simulable")
        return Base64.getEncoder().encodeToString(root.toString().toByteArray())
    }

    private fun checkout() = PurseHeadlessCheckout.init(
        simulableWidgetData(),
        lockedEnvironment = null,
        hooks = null,
        datetimeChecker = ServerDatetimeChecker(fetchServerTime = { throw PaymentApiException("offline") }),
        watchdogIntervalMs = 60_000,
    )

    private val product = LoanProduct(BigDecimal("4.80"), "EUR")

    @Test
    fun `simulable derives from the configuration labels`() {
        val checkout = checkout()

        assertTrue(checkout.primaryMethods.single { it.partner == Partner.Paypal }.simulable)
        assertFalse(checkout.primaryMethods.single { it.method == Method.CreditCard }.simulable)
    }

    @Test
    fun `a non-simulable method fails fast with METHOD_IS_NOT_SIMULABLE and no API call`() {
        val checkout = checkout()
        val card = checkout.primaryMethods.single { it.method == Method.CreditCard }

        val error = assertThrows(PurseCheckoutException.MethodNotSimulable::class.java) {
            runBlocking { checkout.simulateLoan(card, product) }
        }

        assertEquals(PurseErrorCode.METHOD_IS_NOT_SIMULABLE, error.code)
        assertEquals(null, lastPath)
    }

    @Test
    fun `simulateLoan calls the entity-scoped endpoint and maps the schedule`() {
        responseBody = """{"plugin_result": {"initial_instalment_amount": 1.60, "instalment_amount": 1.60,
            "final_instalment_amount": 1.60, "instalment_number": 3, "total_cost": 0}}"""
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        val events = mutableListOf<CheckoutEventCode>()
        val unsubscribe = CheckoutEventBus.getInstance().subscribe(CheckoutEventBus.Scope.ALL) { events.add(it.code) }

        val simulation = try {
            runBlocking { checkout.simulateLoan(paypal, product) }
        } finally {
            unsubscribe()
        }

        assertEquals("/payment/v1/simulation/entity-1/paypal/wallet", lastPath)
        assertEquals("EUR", JSONObject(lastBody!!).getString("currency_code"))
        assertEquals(3, simulation.installmentCount)
        // Web arithmetic: (count - 1) × recurring + initial + final = 2 × 1.60 + 1.60 + 1.60.
        assertEquals(BigDecimal("6.40"), simulation.totalAmount)
        assertTrue(events.contains(CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_LOAN_SIM))
    }

    @Test
    fun `an answer without schedule data is INVALID_LOAN_SIMULATION`() {
        responseBody = """{"status": {"state": "ERROR"}}"""
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }

        val error = assertThrows(PurseCheckoutException.LoanSimulationFailed::class.java) {
            runBlocking { checkout.simulateLoan(paypal, product) }
        }

        assertEquals(PurseErrorCode.INVALID_LOAN_SIMULATION, error.code)
        assertTrue(error.cause is PaymentApiException)
    }

    /**
     * What the sandbox actually answers for Riverty 6x when the partner doesn't offer the method for
     * the order (observed 2026-09-03): a 409 with `error: EMPTY_SIMULATION`. Web reads the body
     * regardless of status and ends in `INVALID_LOAN_SIMULATION`; so do we, keeping the API's code.
     */
    @Test
    fun `an API refusal such as 409 EMPTY_SIMULATION is INVALID_LOAN_SIMULATION with the API code on the cause`() {
        responseStatus = 409
        responseBody = """{"error":"EMPTY_SIMULATION","error_description":"The plugin did not managed to parse partner's API response and returned an empty result.","status":409}"""
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }

        val error = assertThrows(PurseCheckoutException.LoanSimulationFailed::class.java) {
            runBlocking { checkout.simulateLoan(paypal, product) }
        }

        assertEquals(PurseErrorCode.INVALID_LOAN_SIMULATION, error.code)
        val cause = error.cause as PaymentApiException
        assertEquals(409, cause.status)
        assertEquals("EMPTY_SIMULATION", cause.errorCode)
        assertTrue(error.message!!.contains("EMPTY_SIMULATION"))
    }

    @Test
    fun `the multi endpoint's refusal is INVALID_LOAN_SIMULATION too`() {
        responseStatus = 501
        responseBody = """{"error":"NOT_IMPLEMENTED","error_description":"The multi simulation processing method is not implemented.","status":400}"""
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }

        val error = assertThrows(PurseCheckoutException.LoanSimulationFailed::class.java) {
            runBlocking { checkout.simulateLoans(paypal, product) }
        }
        assertEquals("NOT_IMPLEMENTED", (error.cause as PaymentApiException).errorCode)
    }

    @Test
    fun `a transport failure stays a PaymentApiException`() {
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }
        server.stop(0) // nothing listens any more → connection refused, no HTTP status

        val error = assertThrows(PaymentApiException::class.java) {
            runBlocking { checkout.simulateLoan(paypal, product) }
        }
        assertEquals(null, error.status)
    }

    @Test
    fun `simulateLoans returns every usable candidate from the multi endpoint`() {
        responseBody = """[
            {"plugin_result": {"initial_instalment_amount": 2.40, "instalment_amount": 2.40,
              "final_instalment_amount": 2.40, "instalment_number": 2, "total_cost": 0}},
            {"status": {"state": "ERROR"}}
        ]"""
        val checkout = checkout()
        val paypal = checkout.primaryMethods.single { it.partner == Partner.Paypal }

        val simulations = runBlocking { checkout.simulateLoans(paypal, product) }

        assertEquals("/payment/v1/simulation/entity-1/paypal/wallet/multi", lastPath)
        assertEquals(listOf(2), simulations.map { it.installmentCount })
    }
}
