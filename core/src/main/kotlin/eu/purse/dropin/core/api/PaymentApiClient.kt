package eu.purse.dropin.core.api

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin, synchronous Payment API client. Mirrors web's `FetchPaymentAPI`
 * (usp-widget `packages/sdk/.../payment-api.ts`): posts JSON to
 * `{apiRoot}/{entityId}/sessions/{sessionId}/<path>` with `x-api-key` auth.
 *
 * Blocking on purpose — `:dropin` pulls in no coroutines/OkHttp (see
 * docs/internal/project-setup.md), so callers must invoke this off the main thread.
 *
 * @param apiRoot the Payment API root, e.g. `https://api.purse-test.com/payment/v1` (see
 * [eu.purse.dropin.core.environment.PurseEnvironment.paymentApiRoot]). A trailing slash is tolerated.
 */
class PaymentApiClient(
    private val apiKey: String,
    apiRoot: String,
    private val entityId: String,
    private val sessionId: String,
) {
    private val root: String = apiRoot.trimEnd('/')

    /** `POST /init` — see docs/architecture/session-and-protocols.md#init-and-validate. */
    fun init(payload: JSONObject): JSONObject = post("/init", payload.toString())

    /**
     * `POST /validate` — the validate call takes a JSON *array* of items (one per method being
     * submitted), mirroring web's `FetchPaymentAPI.validate`. Returns the `{results, redirection,
     * global_status}` object. See docs/architecture/session-and-protocols.md#init-and-validate.
     */
    fun validate(payload: JSONArray): JSONObject = post("/validate", payload.toString())

    /**
     * `GET /{entityId}/sessions/{sessionId}` — every transaction (`/init`/`/validate` call) made
     * so far against this session, each with its own `status`. This is the source of truth to
     * re-query after a capture rather than trusting a locally-held `/validate` result alone —
     * confirmed against Purse's own published spec/tutorial (see
     * https://docs.purse.tech/docs/integrate/purse-api/tutorials/simple-payment and
     * docs/architecture/session-and-protocols.md#init-and-validate).
     */
    fun getSession(): JSONArray {
        val responseBody = execute("GET", path = "", body = null)
        return try {
            JSONArray(responseBody)
        } catch (e: JSONException) {
            throw PaymentApiException("Payment API GET session returned non-JSON: ${e.message}", e)
        }
    }

    private fun post(path: String, body: String): JSONObject {
        val responseBody = execute("POST", path, body)
        return try {
            JSONObject(responseBody)
        } catch (e: JSONException) {
            throw PaymentApiException("Payment API $path returned non-JSON: ${e.message}", e)
        }
    }

    private fun execute(method: String, path: String, body: String?): String {
        val url = "$root/$entityId/sessions/$sessionId$path"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("x-api-key", apiKey)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            body?.let { payload -> connection.outputStream.use { it.write(payload.toByteArray()) } }
            // Web's FetchPaymentAPI treats status < 400 as success.
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) {
                throw PaymentApiException("Payment API $method $path failed: HTTP $code $responseBody")
            }
            responseBody
        } catch (e: IOException) {
            throw PaymentApiException("Payment API $method $path failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }
}
