// Web: packages/headless-checkout/src/utils/error/error.ts (`PurseHeadlessCheckoutError`)
// Same: typed codes on every failure.
// Differs: a sealed hierarchy instead of one class with a code.
package eu.purse.checkout

import eu.purse.checkout.payment.item.DisabledState
import eu.purse.checkout.state.SessionState
import eu.purse.core.api.PaymentApiException
import eu.purse.core.error.PurseErrorCode
import eu.purse.core.error.PurseException

/**
 * Errors raised by the headless checkout orchestration — the analog of web's
 * `PurseHeadlessCheckoutError`. Every exception carries a machine-readable `code` (shared
 * vocabulary with web, see [PurseErrorCode]) and a `documentationLink`, through the SDK-wide
 * [PurseException] root it shares with session-payload errors
 * (`eu.purse.core.session.PurseSessionException`) and API/transport errors ([PaymentApiException]).
 */
sealed class PurseCheckoutException(
    message: String,
    code: PurseErrorCode,
    cause: Throwable? = null,
) : PurseException(message, code, cause) {

    /** `submitPayment` called while the split doesn't yet cover the amount / has no primary. */
    class PaymentNotFulfilled(message: String = "payment is not fulfilled") :
        PurseCheckoutException(message, PurseErrorCode.PAYMENT_NOT_FULFILLED_ON_SUBMIT)

    /**
     * `submitPayment` while a submission is in flight, or on a session whose one `/validate` is
     * already spent (submitted, declined, or terminated by the API) — the throwing form of web's
     * re-entrancy latch, which posts `HEADLESS_CHECKOUT_SUBMIT_BLOCKED` and returns.
     */
    class SubmitBlocked(val state: SessionState) :
        PurseCheckoutException(
            when (state) {
                SessionState.SUBMITTING -> "a payment submission is already in flight"
                else -> "the session's payment has already been submitted (state $state)"
            },
            PurseErrorCode.SUBMIT_BLOCKED,
        )

    /**
     * Selecting a method or saved token whose [DisabledState] is set — web's `METHOD_DISABLED`
     * from `setAsPrimarySource`. [disabled] says why, with a displayable `message`.
     */
    class MethodDisabled(itemId: String, val disabled: DisabledState) :
        PurseCheckoutException("$itemId is disabled: ${disabled.message}", PurseErrorCode.METHOD_DISABLED)

    /**
     * A secondary draw the splitter refuses — web's `ensureTakePolicy` / `isMethodLimitReached`:
     * `INVALID_TAKE_AMOUNT` (negative, above the balance or above what is left to pay),
     * `MAX_AMOUNT_REACHED` (above the method's `payment_max_amount`) or `USAGE_LIMIT_ERROR` (the
     * method's `usage_per_session_limit` is already used up by other tokens in the split).
     */
    class InvalidTake(message: String, code: PurseErrorCode) : PurseCheckoutException(message, code)

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

    /** `setSession` was handed a session that is already expired — web's `EXPIRED_SESSION`. */
    class SessionExpired(sessionId: String) :
        PurseCheckoutException("session $sessionId is expired", PurseErrorCode.EXPIRED_SESSION)

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

    /**
     * A client-side or API validation step failed during "create payment". When [outcomeUnknown]
     * is `true` the `POST /validate` left the device but no answer came back (transport failure):
     * the payment may exist server-side — reconcile with `paymentStatus()` /
     * `awaitPaymentOutcome()` instead of telling the shopper it failed.
     */
    class ValidateFailed(
        message: String,
        cause: Throwable? = null,
        code: PurseErrorCode = PurseErrorCode.VALIDATE_FAILED,
    ) : PurseCheckoutException(message, code, cause) {
        val outcomeUnknown: Boolean get() = (cause as? PaymentApiException)?.outcomeUnknown == true
    }

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

    /** `simulateLoan` on a method whose configuration lacks the `simulable` label. */
    class MethodNotSimulable(itemId: String) :
        PurseCheckoutException(
            "loan simulation is not available for $itemId",
            PurseErrorCode.METHOD_IS_NOT_SIMULABLE,
        )

    /**
     * The simulation API answered without usable schedule data (no `plugin_result`) — web's
     * `INVALID_LOAN_SIMULATION`.
     */
    class LoanSimulationFailed(message: String, cause: Throwable? = null) :
        PurseCheckoutException(message, PurseErrorCode.INVALID_LOAN_SIMULATION, cause)
}
