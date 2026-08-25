package eu.purse.core.api

/**
 * Raised when a Payment API call (e.g. `/init`) fails at the network/transport level, returns a
 * non-2xx/3xx status, or produces a response the caller can't use (unsuccessful init, empty-balance
 * card). See [PaymentApiClient].
 *
 * Mirrors web's `ApiError` (usp-widget `packages/sdk/src/API/types.ts`): carries the HTTP [status]
 * (null for a pure transport failure), the backend's body [errorCode] (the `error` field, e.g.
 * `SESSION_EXPIRED`), and the request [path].
 */
class PaymentApiException(
    message: String,
    cause: Throwable? = null,
    /** HTTP status of the failing response; `null` when the request never got one (network). */
    val status: Int? = null,
    /** The `error` code from the API's JSON error body, when one was parseable. */
    val errorCode: String? = null,
    /** The request path that failed, e.g. `/init`. */
    val path: String? = null,
) : Exception(message, cause) {

    /**
     * The session itself is spent — already submitted, expired, or gone — so every further call is
     * pointless: web's `SESSION_TERMINAL_ERRORS` (`SESSION_ALREADY_SUBMITTED_ERROR`,
     * `SESSION_EXPIRED`, `API_404`). Callers should surface a terminated session state.
     */
    val isSessionTerminal: Boolean get() = errorCode in SESSION_TERMINAL_ERROR_CODES

    /**
     * Whether retrying the exact same call could help. A 4xx is a definitive backend decision
     * (submitted, expired, not found, malformed, 429 step budget spent) — only a 5xx (outage) or a
     * transport failure with no status at all is worth retrying. Web `initGuard.isRetryable`.
     */
    val isRetryable: Boolean get() = status == null || status >= 500

    companion object {
        /** Body `error` values that mean the session is finished for good. */
        val SESSION_TERMINAL_ERROR_CODES: Set<String> =
            setOf("SESSION_ALREADY_SUBMITTED_ERROR", "SESSION_EXPIRED", "NOT_FOUND")
    }
}
