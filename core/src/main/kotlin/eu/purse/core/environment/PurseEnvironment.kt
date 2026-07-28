package eu.purse.core.environment

/**
 * Payment API environment tier — see
 * docs/architecture/headless-checkout.md.
 *
 * A release build should pass a locked [PurseEnvironment] to `PurseHeadlessCheckout.init()` rather than
 * trusting whatever a session declares — see
 * docs/architecture/headless-checkout.md for why.
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
