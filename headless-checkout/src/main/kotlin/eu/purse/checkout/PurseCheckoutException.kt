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

    /**
     * An item in the split has an interactive form that isn't complete and valid yet — web's
     * `ELEMENT_NOT_FULFILLED`. Raised by `submitPayment`; the reactive guard against ever getting
     * here is `isPaymentFulfilled`, which is `false` for the same reason.
     */
    class ItemNotFulfilled(itemId: String) :
        PurseCheckoutException("$itemId is not fulfilled — its form is incomplete or invalid")

    /** No `:partner-ui` plugin is registered for a `(partner, method)` in the current split. */
    class NoPluginRegistered(message: String) : PurseCheckoutException(message)

    /** A client-side or API validation step failed during "create payment". */
    class ValidateFailed(message: String, cause: Throwable? = null) :
        PurseCheckoutException(message, cause)

    /** No `SecondaryConfig` is registered for a secondary `(partner, method)`. */
    class NoSecondaryConfig(message: String) : PurseCheckoutException(message)

    /** A secondary card failed client-side PAN/CVV validation before tokenization. */
    class InvalidSecondaryCard(message: String) : PurseCheckoutException(message)

    /** The `/init` tokenization of a secondary was unsuccessful, or the card carries no balance. */
    class SecondaryTokenizationFailed(message: String) : PurseCheckoutException(message)
}
