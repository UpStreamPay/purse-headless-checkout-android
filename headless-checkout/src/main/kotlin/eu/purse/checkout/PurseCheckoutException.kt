package eu.purse.checkout

import eu.purse.core.error.PurseErrorCode

/**
 * Errors raised by the headless checkout orchestration — the analog of web's
 * `PurseHeadlessCheckoutError`. Every exception carries a machine-readable [code] (shared
 * vocabulary with web, see [PurseErrorCode]) and a [documentationLink]. Session-payload errors
 * live in `:core` (`eu.purse.core.session.PurseSessionException`); API/transport errors are
 * `eu.purse.core.api.PaymentApiException`.
 */
sealed class PurseCheckoutException(
    message: String,
    val code: PurseErrorCode,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The shared error-codes documentation anchor for [code]. */
    val documentationLink: String get() = code.documentationLink

    /** `submitPayment` called while the split doesn't yet cover the amount / has no primary. */
    class PaymentNotFulfilled(message: String = "payment is not fulfilled") :
        PurseCheckoutException(message, PurseErrorCode.PAYMENT_NOT_FULFILLED_ON_SUBMIT)

    /**
     * An item in the split has an interactive form that isn't complete and valid yet — web's
     * `ELEMENT_NOT_FULFILLED`. Raised by `submitPayment`; the reactive guard against ever getting
     * here is `isPaymentFulfilled`, which is `false` for the same reason.
     */
    class ItemNotFulfilled(itemId: String) :
        PurseCheckoutException(
            "$itemId is not fulfilled — its form is incomplete or invalid",
            PurseErrorCode.ELEMENT_NOT_FULFILLED,
        )

    /** No `:partner-ui` plugin is registered for a `(partner, method)` in the current split. */
    class NoPluginRegistered(message: String) :
        PurseCheckoutException(message, PurseErrorCode.METHOD_NOT_IMPLEMENTED)

    /**
     * A merchant hook ([PurseCheckoutHooks]) threw. [hook] names it (`onBeforeValidate`,
     * `onRedirection`); the merchant's own exception is the [cause]. `onBeforeValidate` carries
     * web's `ON_BEFORE_VALIDATE_HOOK_FAILED`; `onRedirection` — which web has no dedicated code
     * for, its handler runs inside `postValidation` — carries `POST_VALIDATE_FAILED`.
     */
    class HookFailed(val hook: String, cause: Throwable, code: PurseErrorCode) :
        PurseCheckoutException("$hook hook failed: ${cause.message}", code, cause)

    /** A client-side or API validation step failed during "create payment". */
    class ValidateFailed(
        message: String,
        cause: Throwable? = null,
        code: PurseErrorCode = PurseErrorCode.VALIDATE_FAILED,
    ) : PurseCheckoutException(message, code, cause)

    /** No `SecondaryConfig` is registered for a secondary `(partner, method)`. */
    class NoSecondaryConfig(message: String) :
        PurseCheckoutException(message, PurseErrorCode.METHOD_NOT_IMPLEMENTED)

    /** A secondary card failed client-side PAN/CVV validation before tokenization. */
    class InvalidSecondaryCard(
        message: String,
        code: PurseErrorCode = PurseErrorCode.SECONDARY_METHOD_WRONG_PAN_FORMAT,
    ) : PurseCheckoutException(message, code)

    /**
     * A wallet/token management operation (rename, delete, favorite, register) failed its
     * preconditions — missing wallet session, token not in the wallet, invalid or duplicate label,
     * or a method that can't be registered.
     */
    class WalletOperationFailed(message: String, code: PurseErrorCode) :
        PurseCheckoutException(message, code)

    /** The `/init` tokenization of a secondary was unsuccessful, or the card carries no balance. */
    class SecondaryTokenizationFailed(
        message: String,
        code: PurseErrorCode = PurseErrorCode.FAILED_TO_INIT_SECONDARY_TOKEN,
    ) : PurseCheckoutException(message, code)
}
