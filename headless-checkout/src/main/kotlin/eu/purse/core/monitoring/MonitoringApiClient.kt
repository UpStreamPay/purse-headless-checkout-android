// Web: packages/sdk `ApiLogger` (`/widget/headless_checkout`)
// Same: same route and message envelope.
// Differs: `platform: android` on every message.
package eu.purse.core.monitoring

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ships monitoring event batches to `POST {root}?api-key=…` — the Kotlin port of web sdk's
 * `FetchMonitoringAPI` (`monitoring-api.ts`). Each event is wrapped in the same message envelope:
 * `{event, instanceId, entityId?, paymentSessionId?, walletSessionId?, version?, wrapper?, env, platform}`
 * — web's envelope plus the `platform` the native vault SDKs already send.
 *
 * Fire-and-forget: sends happen on a dedicated daemon worker (the Android stand-in for
 * `navigator.sendBeacon`) and failures are logged nowhere louder than a swallowed exception — a
 * failed log batch must never break a payment.
 */
class MonitoringApiClient(
    private val apiKey: String,
    monitoringApiRoot: String,
    private val envWireValue: String,
    /**
     * Which SDK family emitted the batch — the same top-level `platform` the native SecureFields
     * loggers stamp on `/widget/secure_fields` (`"android"` / `"ios"`; web sends `"web"`). This is
     * the field to filter a platform on; `wrapper` keeps its web meaning (which product embeds the
     * SDK: `direct` or `dropin`).
     */
    private val platform: String = PLATFORM_ANDROID,
    /**
     * The analog of web's `window.__PURSE_INSTANCE_ID__` — one id per checkout instance here (web:
     * per page load), kept across `setSession` and `saveState`/`restore` so one shopper's journey
     * reads as one instance even after Android killed the process.
     */
    private val instanceId: String = UUID.randomUUID().toString(),
) {
    private val root: String = monitoringApiRoot.trimEnd('/')

    @Volatile
    private var extras: Map<String, String> = emptyMap()

    /** Merge context onto every future message — web's `patchExtrasData`. */
    fun patchExtras(
        entityId: String? = null,
        paymentSessionId: String? = null,
        walletSessionId: String? = null,
        version: String? = null,
        wrapper: String? = null,
    ) {
        val merged = extras.toMutableMap()
        entityId?.let { merged["entityId"] = it }
        paymentSessionId?.let { merged["paymentSessionId"] = it }
        walletSessionId?.let { merged["walletSessionId"] = it }
        version?.let { merged["version"] = it }
        wrapper?.let { merged["wrapper"] = it }
        extras = merged
    }

    /** Queue [events] (each already serialized) for an async POST. No-op without an API key. */
    fun sendLogs(events: List<JSONObject>) {
        if (apiKey.isBlank() || events.isEmpty()) return
        val body = JSONArray()
        events.forEach { event ->
            val message = JSONObject()
            message.put("event", event)
            extras.forEach { (key, value) -> message.put(key, value) }
            message.put("instanceId", instanceId)
            message.put("env", envWireValue)
            message.put("platform", platform)
            body.put(message)
        }
        EXECUTOR.execute { runCatching { post(body.toString()) } }
    }

    private fun post(body: String) {
        val url = "$root?api-key=${URLEncoder.encode(apiKey, "UTF-8")}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            // Web posts the batch as a text/plain Blob (sendBeacon's CORS-safe type); match it.
            setRequestProperty("Content-Type", "text/plain")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode // drive the request; the response body is irrelevant
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PLATFORM_ANDROID = "android"

        private val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "purse-monitoring").apply { isDaemon = true }
        }
    }
}
