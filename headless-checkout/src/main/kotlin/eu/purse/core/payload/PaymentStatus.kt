// Web: packages/sdk/src/API/types.ts (`StatusStates`)
// Same: the five states the Payment API puts in `global_status`.
// Differs: an enum with an `UNKNOWN` catch-all, so a new server value never crashes a merchant's `when`.
package eu.purse.core.payload

/** The Payment API's `global_status` of a `/validate` answer — web's `StatusState`. */
enum class PaymentStatus(val wireValue: String) {
    /** The payment is authorized (or captured): show the shopper a success. */
    SUCCESS("SUCCESS"),

    /** The payment was refused. */
    FAILED("FAILED"),

    /** The API could not process the request. */
    ERROR("ERROR"),

    /** The outcome is not known yet; confirm against the session. */
    UNCERTAIN("UNCERTAIN"),

    /** The payment waits on a later step (an asynchronous partner). */
    WAITING("WAITING"),

    /** A value this SDK version does not know; the raw string is on `ValidationResult.globalStatus`. */
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String): PaymentStatus =
            entries.firstOrNull { it != UNKNOWN && it.wireValue.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}
