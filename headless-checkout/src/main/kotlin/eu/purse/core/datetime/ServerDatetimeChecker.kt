// Web: packages/sdk/src/server-datetime-checker.ts
// Same: server-offset-corrected expiry check.
// Differs: injectable clock and fetch for tests.
package eu.purse.core.datetime

import java.time.Instant

/**
 * Tracks the offset between the device clock and the Purse server clock, so session-expiry checks
 * survive a wrong device clock — the Kotlin analog of web's `ServerDatetimeChecker`
 * (usp-widget `packages/sdk/src/datetime/server-datetime-checker.ts`).
 *
 * [sync] is naive on purpose (one round trip, no latency compensation) — precision to the second is
 * enough for expiry. Until the first successful sync, checks fall back to the local clock, exactly
 * like web (which logs a warning and proceeds).
 */
class ServerDatetimeChecker(
    /** Fetches the server's current time — typically [DatetimeApiClient.datetime]. */
    private val fetchServerTime: () -> Instant,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var serverOffsetMillis: Long = 0

    @Volatile
    var synced: Boolean = false
        private set

    /**
     * Fetch the server time once and record the device-minus-server offset. Blocking (one HTTP
     * call) — run it off the main thread. Failures are swallowed: the checker simply stays
     * unsynced, matching web's catch-and-log.
     */
    fun sync() {
        if (synced) return
        try {
            val serverNow = fetchServerTime()
            serverOffsetMillis = clock() - serverNow.toEpochMilli()
            synced = true
        } catch (_: Exception) {
            // Stay unsynced; expiry checks use the local clock until a later sync succeeds.
        }
    }

    /** Whether [expiration] has passed *for the server* (device clock corrected by the offset). */
    fun isExpiredForServer(expiration: Instant): Boolean =
        expiration.toEpochMilli() + serverOffsetMillis < clock()
}
