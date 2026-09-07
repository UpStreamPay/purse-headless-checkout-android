// Web: none — web surfaces a partner SDK failure as a partner-UI event, never as a typed error.
// Same: n/a.
// Differs: a typed error under the SDK-wide `PurseException` root, so `PaymentOutcome.Failed` never
//          carries a bare string.
package eu.purse.partnerui

import eu.purse.core.error.PurseErrorCode
import eu.purse.core.error.PurseException

/**
 * A partner's native step failed — its SDK returned an error, a card tokenization was refused, the
 * redirect Activity could not complete, or a coordinator was driven before `prepare`. Reaches the
 * merchant as `PaymentOutcome.Failed`; the partner's own error, when there is one, is the [cause].
 */
class PartnerUiException(message: String, cause: Throwable? = null) :
    PurseException(message, PurseErrorCode.PARTNER_UI_ERROR, cause)

/** This throwable as the SDK's typed error: itself when it already is one, else wrapped as a partner failure. */
internal fun Throwable.asPurseException(): PurseException =
    this as? PurseException ?: PartnerUiException(message ?: "unexpected partner error", this)
