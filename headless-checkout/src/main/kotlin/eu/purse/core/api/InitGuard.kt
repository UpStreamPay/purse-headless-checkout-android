// Web: packages/partner-ui/src/commons/PartnerUI/initGuard.ts
// Same: coalesce duplicate /init calls, retry transient failures, fast-reject a call that just failed.
// Differs: key includes the amount; lives in :core so secondaries and plugins share it.
package eu.purse.core.api

import org.json.JSONObject

/**
 * Guards `/init` calls at the network boundary — the Kotlin port of web partner-ui's `initGuard`
 * (usp-widget `packages/partner-ui/src/commons/PartnerUI/initGuard.ts`, SDK-12073):
 *
 * - **coalesces** concurrent duplicate calls (same key → all callers share one in-flight request);
 * - **retries** transient failures — [PaymentApiException.isRetryable]: no HTTP status (a transport
 *   failure, *or a success status whose body is not JSON*) or ≥ 500, and any other exception — up to
 *   [MAX_ATTEMPTS] sends in total, [BACKOFF_MS] (300 ms, then 900 ms) apart. One `/init` the app
 *   asked for can therefore put three identical requests on the wire, silently: only the last
 *   failure is thrown;
 * - never retries a 4xx (a definitive backend decision — retrying changes nothing);
 * - **fast-rejects** repeats of a call that failed for good — a 4xx, or a transient failure once
 *   its attempts are spent: every later call with the same key rethrows that original error
 *   without a send, until the key falls out of the [MAX_TRACKED_KEYS] most recent ones;
 * - successes are *not* cached — only the in-flight window coalesces.
 *
 * Keyed on session + partner + method + amount (not session alone): distinct methods on the same
 * session each get their own init lifecycle, and a legitimate amount-driven reinit is a different
 * key, never blocked by a prior terminal failure.
 *
 * Process-wide singleton, like web's module-level instance. Blocking (callers already run
 * [PaymentApiClient] off the main thread); coalesced waiters block on the leader's result.
 */
object InitGuard {

    const val MAX_ATTEMPTS = 3
    val BACKOFF_MS = longArrayOf(300, 900)
    const val MAX_TRACKED_KEYS = 100

    private sealed interface Entry {
        class InFlight : Entry {
            val done = java.util.concurrent.CountDownLatch(1)
            var result: JSONObject? = null
            var error: Throwable? = null
        }

        class Spent(val error: Throwable) : Entry
    }

    /** Insertion-ordered so over-capacity eviction drops the oldest key, like web's Map. */
    private val guarded = LinkedHashMap<String, Entry>()

    /** Test hook — overridable so retry backoff doesn't slow the suite down. */
    internal var sleeper: (Long) -> Unit = Thread::sleep

    fun keyFor(sessionId: String, payload: JSONObject): String =
        "$sessionId:${payload.opt("partner")}:${payload.opt("method")}:${payload.opt("amount")}"

    /** Run [call] under the guard for [key]. */
    fun run(key: String, call: () -> JSONObject): JSONObject {
        val (entry, isLeader) = synchronized(guarded) {
            when (val existing = guarded[key]) {
                is Entry.Spent -> throw existing.error
                is Entry.InFlight -> existing to false
                null -> {
                    val fresh = Entry.InFlight()
                    guarded[key] = fresh
                    evictOldestIfOverCapacity()
                    fresh to true
                }
            }
        }

        if (!isLeader) {
            entry.done.await()
            entry.error?.let { throw it }
            return checkNotNull(entry.result)
        }

        var attempts = 0
        while (true) {
            try {
                val result = call()
                synchronized(guarded) { guarded.remove(key) }
                entry.result = result
                entry.done.countDown()
                return result
            } catch (error: Throwable) {
                attempts += 1
                val retryable = (error as? PaymentApiException)?.isRetryable ?: true
                if (retryable && attempts < MAX_ATTEMPTS) {
                    // Clamped to the last entry so BACKOFF_MS can be shorter than
                    // MAX_ATTEMPTS - 1 without indexing out of bounds.
                    sleeper(BACKOFF_MS[minOf(attempts - 1, BACKOFF_MS.size - 1)])
                    continue
                }
                synchronized(guarded) { guarded[key] = Entry.Spent(error) }
                entry.error = error
                entry.done.countDown()
                throw error
            }
        }
    }

    private fun evictOldestIfOverCapacity() {
        if (guarded.size <= MAX_TRACKED_KEYS) return
        guarded.keys.firstOrNull()?.let { guarded.remove(it) }
    }

    /** Test-only: clears all tracked guard state between test cases. */
    fun reset() = synchronized(guarded) { guarded.clear() }
}
