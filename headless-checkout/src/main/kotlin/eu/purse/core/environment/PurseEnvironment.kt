// Web: packages/sdk/src/types.ts (`Target`) and the per-environment API roots
// Same: same targets and roots.
// Differs: a release build can lock the tier; the session's `api_url` is then kept only on that tier's host (web keeps it on any host). TEST / DEVELOPMENT are refused with ENVIRONMENT_NOT_ALLOWED in a release (non-debuggable) app, as on iOS (SDK-12577).
package eu.purse.core.environment

import eu.purse.core.session.PurseSessionException
import eu.purse.core.session.PurseSessionParams
import java.net.URI

/**
 * Payment API environment tier — see
 * docs/architecture/headless-checkout.md.
 *
 * A release build should pass a locked [PurseEnvironment] to `PurseHeadlessCheckout.init()` rather than
 * trusting whatever a session declares — see
 * docs/architecture/headless-checkout.md for why. The lock pins the tier's host, not the API version:
 * see [lockedPaymentApiRoot].
 *
 * [TEST] and [DEVELOPMENT] are Purse-internal: a release (non-debuggable) app is refused them with
 * `ENVIRONMENT_NOT_ALLOWED` when the checkout is created — see [requireAllowed].
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

    /**
     * This tier, unless it is [TEST] or [DEVELOPMENT] and the host app is not debuggable: refused,
     * never swapped for production, so a release app cannot reach an internal tier and fail later
     * for no visible reason. Runtime, not `BuildConfig.DEBUG`: the SDK itself is always a release
     * artifact — what counts is the host app's build ([HostApp.isDebuggable]).
     *
     * @throws PurseSessionException.EnvironmentNotAllowed `ENVIRONMENT_NOT_ALLOWED`.
     */
    internal fun requireAllowed(isDebugHost: Boolean): PurseEnvironment {
        if ((this == TEST || this == DEVELOPMENT) && !isDebugHost) throw PurseSessionException.EnvironmentNotAllowed(this)
        return this
    }

    companion object {
        /** Returns `null` for a missing or unrecognized value — callers decide whether that's fatal. */
        fun fromWire(value: String?): PurseEnvironment? = entries.firstOrNull { it.wireValue == value }

        /**
         * The tier whose Payment API host [apiUrl] names, if it names one (host compared
         * case-insensitively, and without the trailing `.` of a fully qualified name, which still
         * resolves to the same host).
         */
        internal fun ofApiUrl(apiUrl: String): PurseEnvironment? {
            val host = runCatching { URI(apiUrl).host }.getOrNull()?.removeSuffix(".") ?: return null
            return entries.firstOrNull { URI(it.paymentApiRoot).host.equals(host, ignoreCase = true) }
        }
    }
}

/**
 * How a checkout picks and gates its tier: the build lock ([locked], see
 * [PurseEnvironment.lockedPaymentApiRoot]) and whether the host app is debuggable
 * ([HostApp.isDebuggable]), kept together so `setSession` re-applies exactly what `init` decided.
 */
internal data class EnvironmentPolicy(val locked: PurseEnvironment?, val isDebugHost: Boolean) {
    /**
     * TEST / DEVELOPMENT are refused in a release app (iOS's SDK-12562): the locked tier when there
     * is one, else the session's tier and the tier its `api_url` names — an unlocked checkout calls
     * that `api_url`, whatever `environment` the blob declares.
     *
     * @throws PurseSessionException.EnvironmentNotAllowed `ENVIRONMENT_NOT_ALLOWED`.
     */
    fun requireAllowed(session: PurseSessionParams) {
        if (locked != null) {
            locked.requireAllowed(isDebugHost)
        } else {
            session.environment.requireAllowed(isDebugHost)
            PurseEnvironment.ofApiUrl(session.apiUrl)?.requireAllowed(isDebugHost)
        }
    }
}
