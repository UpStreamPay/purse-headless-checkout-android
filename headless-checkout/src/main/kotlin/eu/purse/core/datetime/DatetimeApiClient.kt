// Web: packages/sdk/src/server-datetime-checker.ts (`GET /datetime`)
// Same: same endpoint.
// Differs: nothing.
package eu.purse.core.datetime

import eu.purse.core.api.PaymentApiException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject

/**
 * `GET /datetime` — the server clock, used to check session expiry against server time instead of
 * trusting the device clock. Mirrors web's `FetchDatetimeAPI`
 * (usp-widget `packages/sdk/src/API/datetime-api.ts`): unversioned root, `x-api-key` auth,
 * `{ "iso": "..." }` response.
 *
 * Blocking on purpose, like [eu.purse.core.api.PaymentApiClient] — call it off the main thread.
 */
class DatetimeApiClient(
    private val apiKey: String,
    datetimeApiRoot: String,
) {
    private val root: String = datetimeApiRoot.trimEnd('/')

    /** The server's current datetime. @throws PaymentApiException on transport/HTTP/parse failure. */
    fun datetime(): Instant {
        val connection = (URL(root).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) {
                throw PaymentApiException("Datetime call failed: HTTP $code", status = code, path = "/datetime")
            }
            Instant.parse(JSONObject(body).getString("iso"))
        } catch (e: IOException) {
            throw PaymentApiException("Datetime call failed: ${e.message}", e, path = "/datetime")
        } catch (_: JSONException) {
            // Not the JSONException: org.json quotes the body in its message.
            throw PaymentApiException("Datetime call returned an unusable body", path = "/datetime")
        } finally {
            connection.disconnect()
        }
    }
}
