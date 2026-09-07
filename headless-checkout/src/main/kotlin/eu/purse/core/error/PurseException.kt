// Web: packages/headless-checkout/src/utils/error/error.ts (`PurseHeadlessCheckoutError`)
// Same: one root for every SDK error, each carrying a stable code and its documentation link.
// Differs: the root is abstract; session, API and checkout errors are its subclasses.
package eu.purse.core.error

/**
 * The root of every exception this SDK raises — session decoding
 * (`eu.purse.core.session.PurseSessionException`), Payment API transport
 * (`eu.purse.core.api.PaymentApiException`) and checkout orchestration
 * (`eu.purse.checkout.PurseCheckoutException`). Catch this type for generic handling and branch on
 * [code]; every code is stable and shared with web (see [PurseErrorCode]).
 */
abstract class PurseException(
    message: String,
    /** The machine-readable reason — the same vocabulary as web's `PurseHeadlessCheckoutErrors`. */
    val code: PurseErrorCode,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The shared error-codes documentation anchor for [code]. */
    val documentationLink: String get() = code.documentationLink
}
