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

    /** The unversioned `/datetime` root for this tier — web's `DATETIME_API_ROOTS`. */
    val datetimeApiRoot: String get() = paymentApiRoot.removeSuffix("/payment/v1") + "/datetime"

    /** The monitoring/analytics ingestion root for this tier — web's `MONITORING_API_ROOTS`. */
    val monitoringApiRoot: String
        get() = paymentApiRoot.removeSuffix("/payment/v1") + "/widget/headless_checkout"

    /** The Wallet API v2 root for this tier — web's `WALLET_API_ROOTS`. */
    val walletApiRoot: String get() = paymentApiRoot.removeSuffix("/payment/v1") + "/wallet/v2"

    companion object {
        /** Returns `null` for a missing or unrecognized value — callers decide whether that's fatal. */
        fun fromWire(value: String?): PurseEnvironment? = entries.firstOrNull { it.wireValue == value }
    }
}
