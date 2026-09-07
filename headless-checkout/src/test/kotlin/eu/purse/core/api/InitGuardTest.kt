package eu.purse.core.api

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ports the behaviour pinned by web partner-ui's `initGuard.spec.ts` (SDK-12073). */
class InitGuardTest {

    @Before
    fun noSleep() {
        InitGuard.sleeper = { }
    }

    @After
    fun reset() {
        InitGuard.reset()
        InitGuard.sleeper = Thread::sleep
    }

    private fun networkError() = PaymentApiException("boom", status = null)
    private fun serverError() = PaymentApiException("boom", status = 500)
    private fun clientError() = PaymentApiException("spent", status = 400)

    @Test
    fun `a transient failure is retried up to three attempts`() {
        val calls = AtomicInteger()
        val result = InitGuard.run("k1") {
            if (calls.incrementAndGet() < 3) throw serverError()
            JSONObject().put("ok", true)
        }

        assertEquals(3, calls.get())
        assertTrue(result.getBoolean("ok"))
    }

    @Test
    fun `a network failure with no status is also retried`() {
        val calls = AtomicInteger()
        InitGuard.run("k2") {
            if (calls.incrementAndGet() < 2) throw networkError()
            JSONObject()
        }

        assertEquals(2, calls.get())
    }

    @Test
    fun `a 4xx is terminal - no retry, and immediate repeats fast-reject with the original error`() {
        val calls = AtomicInteger()
        val original = assertThrows(PaymentApiException::class.java) {
            InitGuard.run("k3") {
                calls.incrementAndGet()
                throw clientError()
            }
        }
        assertEquals(1, calls.get())

        // The repeat never reaches the network.
        val repeated = assertThrows(PaymentApiException::class.java) {
            InitGuard.run("k3") {
                calls.incrementAndGet()
                JSONObject()
            }
        }
        assertEquals(1, calls.get())
        assertSame(original, repeated)
    }

    @Test
    fun `a retryable failure that exhausts its attempts becomes spent too`() {
        val calls = AtomicInteger()
        assertThrows(PaymentApiException::class.java) {
            InitGuard.run("k4") {
                calls.incrementAndGet()
                throw serverError()
            }
        }
        assertEquals(InitGuard.MAX_ATTEMPTS, calls.get())

        assertThrows(PaymentApiException::class.java) {
            InitGuard.run("k4") {
                calls.incrementAndGet()
                JSONObject()
            }
        }
        assertEquals(InitGuard.MAX_ATTEMPTS, calls.get())
    }

    @Test
    fun `success is not cached - a later identical call runs again`() {
        val calls = AtomicInteger()
        repeat(2) {
            InitGuard.run("k5") {
                calls.incrementAndGet()
                JSONObject()
            }
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun `concurrent duplicate calls coalesce onto one request`() {
        val calls = AtomicInteger()
        val enteredCall = CountDownLatch(1)
        val releaseCall = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val leader = pool.submit<JSONObject> {
            InitGuard.run("k6") {
                calls.incrementAndGet()
                enteredCall.countDown()
                releaseCall.await(5, TimeUnit.SECONDS)
                JSONObject().put("n", 1)
            }
        }
        assertTrue(enteredCall.await(5, TimeUnit.SECONDS))
        val follower = pool.submit<JSONObject> {
            InitGuard.run("k6") {
                calls.incrementAndGet()
                JSONObject()
            }
        }
        // Give the follower a moment to reach the guard before releasing the leader.
        Thread.sleep(100)
        releaseCall.countDown()

        assertEquals(1, leader.get(5, TimeUnit.SECONDS).getInt("n"))
        assertEquals(1, follower.get(5, TimeUnit.SECONDS).getInt("n"))
        assertEquals("both callers shared the single in-flight request", 1, calls.get())
        pool.shutdown()
    }

    @Test
    fun `distinct keys have independent lifecycles`() {
        assertThrows(PaymentApiException::class.java) {
            InitGuard.run("session:paypal:wallet:10") { throw clientError() }
        }

        // A different method (or amount) on the same session is not blocked by the spent key.
        val result = InitGuard.run("session:klarna:installments:10") { JSONObject().put("ok", true) }
        assertTrue(result.getBoolean("ok"))
    }

    @Test
    fun `keyFor uses session, partner, method and amount`() {
        val payload = JSONObject().put("partner", "paypal").put("method", "wallet").put("amount", 4.8)
        assertEquals("s1:paypal:wallet:4.8", InitGuard.keyFor("s1", payload))
    }
}
