// Web: packages/headless-checkout `SESSION_MALFORMED` errors
// Same: same meaning.
// Differs: nothing.
package eu.purse.core.session

/**
 * Mirrors `PurseHeadlessCheckoutError`'s `SESSION_MALFORMED` — thrown for anything wrong with a
 * session payload itself, as opposed to a network/API failure (which belongs to the future
 * Payment API client, not this package).
 */
sealed class PurseSessionException(message: String) : Exception(message) {
    class SessionMalformed(message: String) : PurseSessionException(message)
}
