package eu.purse.checkout.demo

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The four calls this demo makes against **alfred** — the `usp-widget` dev server that holds the
 * server-side Purse credentials a real merchant backend would (see
 * `docs/internal/testing-with-alfred.md`). Nothing here is part of the SDK: session creation, session
 * lookup and capture are all merchant-backend concerns, and the demo only needs them to have
 * something real to pay for and to check afterwards.
 *
 * Deliberately bare: raw [HttpURLConnection] on [Dispatchers.IO], no client library, no auth (alfred
 * adds its own credentials server-side).
 */
class AlfredClient(private val baseUrl: String) {

    /** What [createSession] came back with — see its doc for the two ids. */
    data class Session(val widgetData: String, val orchestrationSessionId: String?)

    /**
     * Mint an orchestration session, in the two steps alfred's own demo site uses:
     *
     * 1. `GET /order` → alfred's demo legacy order, wrapped as `{ "order": <legacyOrder> }`.
     * 2. `POST /orchestration_session` with that unwrapped legacy order as the body — alfred runs its
     *    own `fromLegacyOrder` conversion on it, so posting the *converted* order instead would
     *    double-convert and fail.
     *
     * [Session.widgetData] is the base64 blob [eu.purse.checkout.PurseHeadlessCheckout.init] takes.
     * [Session.orchestrationSessionId] is the response's own top-level `id` (the v2 ClientSession id,
     * distinct from `payment_session.id`) — kept only as the fallback default for the transactions
     * lookup, see `DemoController.resolveAlfredSessionId`.
     */
    suspend fun createSession(): Session = withContext(Dispatchers.IO) {
        val legacyOrder = JSONObject(get("$baseUrl/order")).getJSONObject("order")
        val session = JSONObject(post("$baseUrl/orchestration_session", legacyOrder.toString()))
        Session(
            widgetData = session.getJSONObject("widget").getString("data"),
            orchestrationSessionId = session.optString("id").takeIf { it.isNotBlank() },
        )
    }

    /**
     * `GET /payment_session/{id}` — the call behind the result screen's "Get transactions", which
     * alfred forwards to the real Payment API's `GET /{entity}/sessions/{session}` using its own
     * server-side credentials. This is the source of truth after a redirect return, where the
     * coordinators deliberately don't chase the outcome themselves.
     *
     * Returns the session's transactions, newest first. It really is a JSON **array** — one object per
     * transaction (`{status: {action, state, code}, partner, method, plugin_result, …}`), not an
     * object wrapping one; parsing it as an object is what used to make this tool fail outright.
     */
    suspend fun paymentSession(sessionId: String): JSONArray = withContext(Dispatchers.IO) {
        JSONArray(get("$baseUrl/payment_session/$sessionId"))
    }

    /**
     * `POST /payments/{paymentId}/capture` — alfred forwards to the orchestration API's
     * `POST /v2/payments/{id}/captures` with its own credentials, for a `capture_mode: MANUAL`
     * payment or any client whose own client-facing `api_key` can't call that endpoint directly.
     * [amountMinorUnits] is the API's integer minor-unit amount (480 for 4.80).
     */
    suspend fun capture(paymentId: String, amountMinorUnits: Long): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("amount", amountMinorUnits)
        JSONObject(post("$baseUrl/payments/$paymentId/capture", body.toString())).toString(2)
    }

    private fun get(url: String): String = request(url, "GET", null)
    private fun post(url: String, body: String): String = request(url, "POST", body)

    private fun request(urlString: String, method: String, body: String?): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (body != null) connection.outputStream.use { it.write(body.toByteArray()) } else connection.outputStream.close()
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from $urlString: $responseBody")
            responseBody
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * alfred's base URL from `ALFRED_BASE_URL` (env / `.env`, see `app/build.gradle.kts`),
         * falling back to the emulator default so the demo runs out of the box.
         */
        fun baseUrlFromBuildConfig(): String =
            BuildConfig.ALFRED_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: "http://10.0.2.2:9001"
    }
}
