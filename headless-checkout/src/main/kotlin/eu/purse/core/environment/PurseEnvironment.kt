// Web: packages/sdk/src/types.ts (`Target`) and the per-environment API roots
// Same: same targets and roots.
// Differs: a release build can lock the tier; the session's `api_url` is then kept only on that tier's host (web keeps it on any host).
package eu.purse.core.environment

import java.net.URI

/**
 * Payment API environment tier — see
 * docs/architecture/headless-checkout.md.
 *
 * A release build should pass a locked [PurseEnvironment] to `PurseHeadlessCheckout.init()` rather than
 * trusting whatever a session declares — see
 * docs/architecture/headless-checkout.md for why. The lock pins the tier's host, not the API version:
 * see [lockedPaymentApiRoot].
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

    /**
     * The Payment API root a build locked to this tier uses for a session declaring [apiUrl]: the
     * session's own root when it is `https` on this tier's host with no port or user info (an
     * orchestration session declares `…/payment/v2`), otherwise [paymentApiRoot].
     */
    internal fun lockedPaymentApiRoot(apiUrl: String): String {
        val declared = runCatching { URI(apiUrl) }.getOrNull() ?: return paymentApiRoot
        val onTier = declared.scheme.equals("https", ignoreCase = true) &&
            declared.host.equals(URI(paymentApiRoot).host, ignoreCase = true) &&
            declared.port == -1 &&
            declared.userInfo == null
        return if (onTier) apiUrl else paymentApiRoot
    }

    companion object {
        /** Returns `null` for a missing or unrecognized value — callers decide whether that's fatal. */
        fun fromWire(value: String?): PurseEnvironment? = entries.firstOrNull { it.wireValue == value }
    }
}
