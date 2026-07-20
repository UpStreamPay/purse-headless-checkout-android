package eu.purse.dropin.session

/**
 * Mirrors `PurseHeadlessCheckoutError`'s `SESSION_MALFORMED` — thrown for anything wrong with a
 * session payload itself, as opposed to a network/API failure (which belongs to the future
 * Payment API client, not this package).
 */
sealed class DropinSessionException(message: String) : Exception(message) {
    class SessionMalformed(message: String) : DropinSessionException(message)
}
