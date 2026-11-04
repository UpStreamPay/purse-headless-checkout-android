// Web: packages/headless-checkout/src/utils/error/error-codes.ts
// Same: same codes.
// Differs: SUBMIT_BLOCKED and CHECKOUT_TORN_DOWN are thrown here where web only logs (or has no lifecycle end); ENVIRONMENT_NOT_ALLOWED is native-only (web is not an installed app), shared with iOS.
package eu.purse.core.error

/**
 * Machine-readable error codes for the SDK's exception surface — the Kotlin analog of web's
 * `PurseHeadlessCheckoutErrorCodes` (`utils/error/error-codes.ts`). Names match web exactly so a
 * merchant integrating both platforms handles one vocabulary; codes web can't produce on Android
 * (DOM payment-element rendering, wallet UI states) are added only once the matching feature ships.
 *
 * Three codes are Android-only, because the situations are refused here where web stays silent:
 * [SUBMIT_BLOCKED] (web posts the `HEADLESS_CHECKOUT_SUBMIT_BLOCKED` monitoring event and returns),
 * [CHECKOUT_TORN_DOWN] (web's `teardown` leaves the instance usable) and [PARTNER_UI_ERROR] (a
 * partner SDK failure reaching the payment element's outcome as a typed error). One more is native
 * (Android and iOS) only: [ENVIRONMENT_NOT_ALLOWED], since a web page has no release build.
 *
 * Each code links to the shared docs at [documentationLink], like web's `getErrorDocLink`.
 */
enum class PurseErrorCode {
    // Session
    EXPIRED_SESSION,
    SESSION_MALFORMED,

    /**
     * A `TEST` / `DEVELOPMENT` session (or an `api_url` on their hosts) in a release, i.e.
     * non-debuggable, app — iOS's `ENVIRONMENT_NOT_ALLOWED`.
     */
    ENVIRONMENT_NOT_ALLOWED,

    /** `submitPayment` on a session that is being submitted, already submitted, or terminated. */
    SUBMIT_BLOCKED,

    /** Any operation after `teardown()`. */
    CHECKOUT_TORN_DOWN,

    // Payment process
    VALIDATE_FAILED,
    POST_VALIDATE_FAILED,
    PAYMENT_NOT_FULFILLED_ON_SUBMIT,
    ELEMENT_NOT_FULFILLED,
    UI_VALIDATION_FAILED_ERROR,
    ON_BEFORE_VALIDATE_HOOK_FAILED,
    METHOD_NOT_IMPLEMENTED,

    /** Selecting a method (or saved token) whose `disabled` state is set — web's `setAsPrimarySource`. */
    METHOD_DISABLED,

    /**
     * A partner's native step failed: its SDK returned an error, a tokenization was refused, the
     * redirect Activity could not complete, or a coordinator was driven before `prepare`. Android-only:
     * web surfaces these as partner-UI events, not as errors.
     */
    PARTNER_UI_ERROR,

    // Secondary methods
    FAILED_TO_INIT_SECONDARY_TOKEN,
    SECONDARY_METHOD_REQUIRED_CVV,
    SECONDARY_METHOD_WRONG_PAN_FORMAT,
    SECONDARY_METHOD_WRONG_CVV_FORMAT,
    SECONDARY_TOKEN_EMPTY_BALANCE,
    INVALID_TAKE_AMOUNT,
    MAX_AMOUNT_REACHED,
    USAGE_LIMIT_ERROR,

    // Wallet / tokens
    MISSING_WALLET_SESSION,
    MISSING_TOKEN,
    SET_WALLET_SESSION_FAILED,
    INVALID_TOKEN_NAME,
    TOKEN_NAME_EXISTS,
    TOKEN_NOT_REGISTERABLE,
    METHOD_NOT_REGISTERABLE,
    FAILED_TO_DELETE_ALL_TOKENS,

    // Loan simulation
    METHOD_IS_NOT_SIMULABLE,
    INVALID_LOAN_SIMULATION,

    // Transport / other
    API_ERROR,
    UNKNOWN_ERROR,
    ;

    /** The shared error-codes documentation anchor for this code — web's `getErrorDocLink`. */
    val documentationLink: String
        get() = "https://docs.purse.tech/docs/integrate/purse-checkout/headless-checkout/" +
            "error-handling/error-codes#${name.lowercase()}"
}
