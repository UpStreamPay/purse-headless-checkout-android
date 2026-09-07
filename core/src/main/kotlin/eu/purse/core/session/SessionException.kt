// Web: packages/headless-checkout `SESSION_MALFORMED` errors
// Same: same meaning and code.
// Differs: nothing.
package eu.purse.core.session

import eu.purse.core.error.PurseErrorCode
import eu.purse.core.error.PurseException

/**
 * Mirrors `PurseHeadlessCheckoutError`'s `SESSION_MALFORMED` — thrown for anything wrong with a
 * session payload itself, as opposed to a network/API failure ([eu.purse.core.api.PaymentApiException]).
 */
sealed class PurseSessionException(message: String) : PurseException(message, PurseErrorCode.SESSION_MALFORMED) {
    class SessionMalformed(message: String) : PurseSessionException(message)
}
