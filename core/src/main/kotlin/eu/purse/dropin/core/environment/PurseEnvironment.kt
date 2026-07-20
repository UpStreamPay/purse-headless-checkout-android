package eu.purse.dropin.core.environment

/**
 * Payment API environment tier — see
 * docs/architecture/session-and-protocols.md#payment-api--environments-and-endpoints.
 *
 * A release build should pass a locked [PurseEnvironment] to `DropinAndroid.init()` rather than
 * trusting whatever a session declares — see
 * docs/architecture/session-and-protocols.md#validation-rules for why.
 */
enum class PurseEnvironment(val wireValue: String, val paymentApiRoot: String) {
    DEVELOPMENT("development", "https://api.purse-dev.com/payment/v1"),
    TEST("test", "https://api.purse-test.com/payment/v1"),
    SANDBOX("sandbox", "https://api.purse-sandbox.com/payment/v1"),
    PRODUCTION("production", "https://api.purse-live.com/payment/v1");

    companion object {
        /** Returns `null` for a missing or unrecognized value — callers decide whether that's fatal. */
        fun fromWire(value: String?): PurseEnvironment? = entries.firstOrNull { it.wireValue == value }
    }
}
