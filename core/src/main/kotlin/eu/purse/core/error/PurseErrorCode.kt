package eu.purse.core.error

/**
 * Machine-readable error codes for the SDK's exception surface — the Kotlin analog of web's
 * `PurseHeadlessCheckoutErrorCodes` (`utils/error/error-codes.ts`). Names match web exactly so a
 * merchant integrating both platforms handles one vocabulary; codes web can't produce on Android
 * (DOM payment-element rendering, wallet UI states) are added only once the matching feature ships.
 *
 * Each code links to the shared docs at [documentationLink], like web's `getErrorDocLink`.
 */
enum class PurseErrorCode {
    // Session
    EXPIRED_SESSION,
    SESSION_MALFORMED,

    // Payment process
    VALIDATE_FAILED,
    POST_VALIDATE_FAILED,
    PAYMENT_NOT_FULFILLED_ON_SUBMIT,
    ELEMENT_NOT_FULFILLED,
    UI_VALIDATION_FAILED_ERROR,
    ON_BEFORE_VALIDATE_HOOK_FAILED,
    METHOD_NOT_IMPLEMENTED,

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
