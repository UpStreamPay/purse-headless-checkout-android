package eu.purse.dropin.core.api

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
    fun init(payload: JSONObject): JSONObject = post("/init", payload)

    private fun post(path: String, payload: JSONObject): JSONObject {
        val url = "$root/$entityId/sessions/$sessionId$path"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
        }
        val body = try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            // Web's FetchPaymentAPI treats status < 400 as success.
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) {
                throw PaymentApiException("Payment API $path failed: HTTP $code $responseBody")
            }
            responseBody
        } catch (e: IOException) {
            throw PaymentApiException("Payment API $path failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw PaymentApiException("Payment API $path returned non-JSON: ${e.message}", e)
        }
    }
}
