package eu.purse.dropin.session

import eu.purse.dropin.core.environment.PurseEnvironment

/**
 * The decoded `widget.data` payload — see docs/architecture/session-and-protocols.md#decoding-widgetdata.
 */
data class PurseSessionParams(
    val apiKey: String,
    val apiUrl: String,
    val environment: PurseEnvironment,
    val entityId: String,
    val paymentSession: LegacyPaymentSession,
)
