package eu.purse.core.api

import eu.purse.checkout.FakePaymentApi
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `POST /validate` leaves the device at most once per session (SDK-12621): no transparent replay by
 * `HttpURLConnection`, and no second call — from any client in the process — once one may have been
 * accepted. A refused or never-sent call leaves the session open.
 */
class PaymentApiClientValidateTest {

    private val submitted = """{"error":"SESSION_ALREADY_SUBMITTED_ERROR","status":400}"""
    private val success = """{"global_status":"SUCCESS","results":[]}"""

    @Before
    @After
    fun reset() = ValidateGuard.reset()

    private fun client(api: FakePaymentApi, sessionId: String = "session-1") =
        PaymentApiClient("key", api.apiRoot, "entity-1", sessionId)

    private fun FakePaymentApi.validateCount() = requests.count { it.path.endsWith("/validate") }

    @Test
    fun `a validate whose answer is lost is not replayed and is reported outcome unknown`() {
        FakePaymentApi().use { api ->
            var calls = 0
            // First call: the server takes the payment, then the answer is lost. A replay would get SUBMITTED.
            api.on("validate") { if (calls++ == 0) FakePaymentApi.DROP to "" else 400 to submitted }

            val thrown = assertThrows(PaymentApiException::class.java) { client(api).validate(JSONArray()) }

            assertEquals("one POST on the wire, no transparent replay", 1, api.validateCount())
            assertTrue("the payment may exist server-side", thrown.outcomeUnknown)
            assertTrue(ValidateGuard.isSpent("session-1"))
        }
    }

    @Test
    fun `an answered validate is never sent again for the same session, by any client`() {
        FakePaymentApi().use { api ->
            api.respond("validate", success)
            client(api).validate(JSONArray())

            val thrown = assertThrows(PaymentApiException::class.java) { client(api).validate(JSONArray()) }

            assertEquals(ValidateGuard.VALIDATE_ALREADY_SENT, thrown.errorCode)
            assertFalse(thrown.requestSent)
            assertFalse("refused locally — not an unknown outcome", thrown.outcomeUnknown)
            assertEquals(1, api.validateCount())
        }
    }

    @Test
    fun `two concurrent validates for one session send one request`() {
        FakePaymentApi().use { api ->
            val release = CountDownLatch(1)
            api.on("validate") {
                release.await(5, TimeUnit.SECONDS)
                200 to success
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit(Callable { runCatching { client(api).validate(JSONArray()) } })
                api.awaitRequests("validate")
                val second = pool.submit(Callable { runCatching { client(api).validate(JSONArray()) } }).get(5, TimeUnit.SECONDS)
                release.countDown()

                assertTrue(first.get(5, TimeUnit.SECONDS).isSuccess)
                assertEquals(ValidateGuard.VALIDATE_ALREADY_SENT, (second.exceptionOrNull() as PaymentApiException).errorCode)
                assertEquals(1, api.validateCount())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `a validate the API refused leaves the session open`() {
        FakePaymentApi().use { api ->
            var calls = 0
            api.on("validate") { if (calls++ == 0) 500 to """{"error":"OUTAGE"}""" else 200 to success }

            val refused = assertThrows(PaymentApiException::class.java) { client(api).validate(JSONArray()) }
            assertEquals(500, refused.status)
            assertFalse(ValidateGuard.isSpent("session-1"))

            client(api).validate(JSONArray())
            assertEquals(2, api.validateCount())
            assertTrue(ValidateGuard.isSpent("session-1"))
        }
    }

    @Test
    fun `a session the API reports already submitted is spent`() {
        FakePaymentApi().use { api ->
            api.on("validate") { 400 to submitted }
            val thrown = assertThrows(PaymentApiException::class.java) { client(api).validate(JSONArray()) }
            assertTrue(thrown.isSessionTerminal)
            assertTrue(ValidateGuard.isSpent("session-1"))
        }
    }

    @Test
    fun `a validate that never left the device leaves the session open`() {
        val api = FakePaymentApi()
        val client = client(api)
        api.close() // connection refused

        val thrown = assertThrows(PaymentApiException::class.java) { client.validate(JSONArray()) }

        assertFalse(thrown.requestSent)
        assertFalse(thrown.outcomeUnknown)
        assertFalse(ValidateGuard.isSpent("session-1"))
    }

    @Test
    fun `sessions are guarded independently`() {
        FakePaymentApi().use { api ->
            api.respond("validate", success)
            client(api, "session-1").validate(JSONArray())
            client(api, "session-2").validate(JSONArray())
            assertEquals(2, api.validateCount())
        }
    }
}
