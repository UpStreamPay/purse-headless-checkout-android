package eu.purse.checkout

/**
 * Errors raised by the headless checkout orchestration — a trimmed analog of web's
 * `PurseHeadlessCheckoutError` codes. Session-payload errors live in `:core`
 * (`eu.purse.core.session.PurseSessionException`); API/transport errors are
 * `eu.purse.core.api.PaymentApiException`.
 */
sealed class PurseCheckoutException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** `submitPayment` called while the split doesn't yet cover the amount / has no primary. */
    class PaymentNotFulfilled(message: String = "payment is not fulfilled") :
        PurseCheckoutException(message)

    /** No `:partner-ui` plugin is registered for a `(partner, method)` in the current split. */
    class NoPluginRegistered(message: String) : PurseCheckoutException(message)

    /** A client-side or API validation step failed during "create payment". */
    class ValidateFailed(message: String, cause: Throwable? = null) :
        PurseCheckoutException(message, cause)
}
