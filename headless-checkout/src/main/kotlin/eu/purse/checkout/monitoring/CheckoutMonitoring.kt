// Web: packages/headless-checkout/src/headless-checkout.ts `init` (ApiLogger + `sendLogs`) and `beforeunload`.
// Same: one batched logger per session, flushed when the page/app goes away.
// Differs: `ProcessLifecycleOwner.ON_STOP` stands in for `beforeunload`; `platform: android` on the envelope.
package eu.purse.checkout.monitoring

import eu.purse.core.monitoring.ApiCallMonitor
import eu.purse.core.monitoring.MonitoringApiClient
import eu.purse.core.session.PurseSessionParams
import org.json.JSONObject

/** A monitoring payload from non-null pairs. */
internal fun monitoringPayload(vararg pairs: Pair<String, Any?>): JSONObject =
    JSONObject().apply { pairs.forEach { (key, value) -> if (value != null) put(key, value) } }

/** Points the singleton [CheckoutEventBus] at the monitoring API for one session. */
internal object CheckoutMonitoring {

    @Volatile
    private var backgroundFlushInstalled = false

    /** The shipper the last [install] wired — the one [sessionChanged] / [walletSessionSet] re-stamp. */
    @Volatile
    private var logger: MonitoringApiClient? = null

    /**
     * Wire the bus to the collector for the session in [params]. [monitoringApiRoot] is the
     * environment's collector unless a test points it at a loopback server.
     */
    fun install(
        params: PurseSessionParams,
        sessionId: String,
        version: String,
        monitoringApiRoot: String = params.environment.monitoringApiRoot,
    ) {
        val logger = MonitoringApiClient(
            apiKey = params.apiKey,
            monitoringApiRoot = monitoringApiRoot,
            envWireValue = params.environment.wireValue,
        )
        // `wrapper` keeps web's meaning (which product embeds the SDK); a merchant integration is `direct`.
        logger.patchExtras(
            entityId = params.entityId,
            paymentSessionId = sessionId,
            walletSessionId = params.walletSession?.sessionId,
            version = version,
            wrapper = "direct",
        )
        this.logger = logger
        val bus = CheckoutEventBus.getInstance()
        bus.setLogProcessor { batch -> logger.sendLogs(batch.map { it.toJson() }) }

        ApiCallMonitor.listener = object : ApiCallMonitor.Listener {
            override fun onCallStart(method: String, path: String) {
                bus.post(
                    CheckoutEventCode.HEADLESS_CHECKOUT_PARTNER_API_CALL_START,
                    EventLevel.NETWORK,
                    monitoringPayload("call" to method, "path" to path),
                )
            }

            override fun onCallEnd(method: String, path: String, durationMs: Long, success: Boolean) {
                bus.post(
                    CheckoutEventCode.HEADLESS_CHECKOUT_PARTNER_API_CALL_END,
                    EventLevel.PERFORMANCE,
                    monitoringPayload("call" to method, "path" to path, "durationMs" to durationMs, "success" to success),
                )
            }
        }
        installBackgroundFlush(bus)
    }

    /**
     * Re-stamp the envelope after `setSession` — web calls `patchExtrasData` again on every
     * `_setSession`, so events after a refresh carry the session they belong to, not the first one.
     */
    fun sessionChanged(params: PurseSessionParams, sessionId: String) {
        logger?.patchExtras(
            entityId = params.entityId,
            paymentSessionId = sessionId,
            walletSessionId = params.walletSession?.sessionId,
        )
    }

    /** Flush when the whole app goes to background. No-op on the JVM test classpath (no Android process). */
    private fun installBackgroundFlush(bus: CheckoutEventBus) {
        if (backgroundFlushInstalled) return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                        androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                                bus.post(CheckoutEventCode.HEADLESS_CHECKOUT_PAGE_UNLOAD)
                                bus.flush()
                            }
                        },
                    )
                }
            }
            backgroundFlushInstalled = true
        }
    }

    /** The process's HTTP user agent, available without a Context; falls back to `"android"`. */
    fun androidUserAgent(): String = System.getProperty("http.agent")?.takeIf { it.isNotBlank() } ?: "android"
}
