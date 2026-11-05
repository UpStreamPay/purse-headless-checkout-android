// Web: no counterpart — a browser tab holds one checkout per session; an Android process can hold several.
// Same: the spent rule of web's `#validationConsumed` (answered or unknown spends; refused does not).
// Differs: process-wide and keyed by session id, so it outlives a recreated checkout instance.
package eu.purse.core.api

/**
 * Once-only guard on `POST /validate`, keyed by session id and shared by every [PaymentApiClient]
 * in the process (SDK-12621). A session is `/validate`-d at most once: a second call while one is
 * in flight, or after one the server may have accepted, is refused before it leaves the device.
 *
 * A call **spends** the session when the API answered about the payment (any 2xx), when the outcome
 * is unknown (the request left the device and no answer came back), or when the API says the
 * session is already finished. A call the API *refused* (4xx/5xx) or that never left the device
 * leaves it open, so the shopper can retry — the same rule as web's `#validationConsumed`.
 *
 * Unlike [PurseHeadlessCheckout][eu.purse.checkout.PurseHeadlessCheckout]'s own latch, this one
 * survives a checkout being rebuilt for the same session (a re-created Activity or ViewModel), so
 * a fresh instance cannot replay a payment the previous one already sent.
 */
internal object ValidateGuard {

    const val MAX_TRACKED_SESSIONS = 100

    private val inFlight = mutableSetOf<String>()

    /** Insertion-ordered so over-capacity eviction drops the oldest session. */
    private val spent = LinkedHashSet<String>()

    /** Whether a `/validate` for [sessionId] is in flight or already spent the session. */
    fun blocks(sessionId: String): Boolean = synchronized(this) { sessionId in inFlight || sessionId in spent }

    /** Whether a `/validate` for [sessionId] already spent the session. */
    fun isSpent(sessionId: String): Boolean = synchronized(this) { sessionId in spent }

    /**
     * Run [call] as the session's one `/validate`.
     *
     * @throws PaymentApiException with [VALIDATE_ALREADY_SENT] (and `requestSent = false`) when
     * another `/validate` for [sessionId] is in flight or already spent it — nothing is sent.
     */
    fun <T> run(sessionId: String, call: () -> T): T {
        synchronized(this) {
            if (sessionId in inFlight || sessionId in spent) {
                throw PaymentApiException(
                    "Payment API POST /validate not sent: this session's /validate is already in flight or answered",
                    errorCode = VALIDATE_ALREADY_SENT,
                    path = "/validate",
                    requestSent = false,
                )
            }
            inFlight += sessionId
        }
        var spends = true
        try {
            return call()
        } catch (e: PaymentApiException) {
            spends = e.outcomeUnknown || e.isSessionTerminal
            throw e
        } finally {
            synchronized(this) {
                inFlight -= sessionId
                if (spends) {
                    spent += sessionId
                    if (spent.size > MAX_TRACKED_SESSIONS) spent.firstOrNull()?.let(spent::remove)
                }
            }
        }
    }

    /** Test-only: clears all tracked state between test cases. */
    fun reset() = synchronized(this) {
        inFlight.clear()
        spent.clear()
    }

    /** [PaymentApiException.errorCode] for a `/validate` this guard refused to send. */
    const val VALIDATE_ALREADY_SENT = "VALIDATE_ALREADY_SENT"
}
