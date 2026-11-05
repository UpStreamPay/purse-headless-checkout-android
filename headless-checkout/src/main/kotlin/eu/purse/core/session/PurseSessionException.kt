// Web: packages/headless-checkout `SESSION_MALFORMED` errors
// Same: same meaning and code.
// Differs: ENVIRONMENT_NOT_ALLOWED (an internal tier in a release app) has no web equivalent; iOS raises the same code.
package eu.purse.core.session

import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.error.PurseException

/**
 * Mirrors `PurseHeadlessCheckoutError`'s `SESSION_MALFORMED` — thrown for anything wrong with a
 * session payload itself, as opposed to a network/API failure ([eu.purse.core.api.PaymentApiException]).
 */
sealed class PurseSessionException(message: String, code: PurseErrorCode) : PurseException(message, code) {
    class SessionMalformed(message: String) : PurseSessionException(message, PurseErrorCode.SESSION_MALFORMED)

    /**
     * `ENVIRONMENT_NOT_ALLOWED`: [environment] is [PurseEnvironment.TEST] or
     * [PurseEnvironment.DEVELOPMENT] — Purse-internal tiers — and the host app is a release
     * (non-debuggable) build. Refused rather than swapped for production, so the cause is visible.
     */
    class EnvironmentNotAllowed(val environment: PurseEnvironment) : PurseSessionException(
        "PurseEnvironment.${environment.name} is internal and not allowed in a release (non-debuggable) app: " +
            "create the session on sandbox or production",
        PurseErrorCode.ENVIRONMENT_NOT_ALLOWED,
    )
}
