// Web: packages/headless-checkout payment-element events (`validationSucceeded` / `error` / `cancelled`)
// Same: one terminal signal per payment attempt.
// Differs: a sealed class instead of stringly events — merchants branch on the type, never on a
//          message; a redirect return is its own case because it is not an outcome.
package eu.purse.partnerui

import eu.purse.core.error.PurseException
import eu.purse.core.payload.PaymentStatus
import eu.purse.core.payload.ValidationResult

/**
 * How a payment attempt driven by a payment element ended. Delivered once per attempt on
 * [PaymentElementListener.onOutcome]; the shopper may start another attempt after
 * [Canceled] or [Failed], never after [Completed] (the session is spent).
 */
sealed class PaymentOutcome {

    /**
     * `/validate` answered without a redirect. [status] is the Payment API's verdict —
     * [PaymentStatus.SUCCESS] is a payment, anything else a refusal or an error the API reported.
     */
    data class Completed(val result: ValidationResult) : PaymentOutcome() {
        val status: PaymentStatus get() = result.status
        val isSuccess: Boolean get() = result.isSuccess
    }

    /**
     * The shopper came back from a redirect (3-D Secure, a PSP page, PayPal's approval). This is
     * **not** an outcome: the final redirect is built server-side and can land on a URL this client
     * cannot read. Confirm from your backend.
     */
    data object ReturnedFromRedirect : PaymentOutcome()

    /** The shopper backed out — a dismissed wallet sheet, an abandoned approval, a closed redirect page. */
    data object Canceled : PaymentOutcome()

    /**
     * The attempt did not produce a payment. Nothing was charged, with one exception: a
     * `PurseCheckoutException.ValidateFailed` whose `outcomeUnknown` is `true` means the request
     * left the device unanswered — confirm from your backend before showing a failure. A
     * `PurseCheckoutException.PartnerFailed` before any submit means the method could not be set
     * up; the item is disabled accordingly on `paymentMethods`.
     */
    data class Failed(val error: PurseException) : PaymentOutcome()
}
