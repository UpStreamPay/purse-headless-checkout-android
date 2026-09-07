// Web: packages/sdk/src/API wallet client (`setWalletSession`, token edit/delete/favorite)
// Same: same routes and headers.
// Differs: nothing.
package eu.purse.core.api

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Thin, synchronous Wallet API v2 client — the Kotlin analog of web's `FetchWalletAPI`
 * (`packages/sdk/src/API/wallet-api.ts`). URLs are
 * `{walletApiRoot}/{ownerReference}/sessions/{walletSessionId}/…`, authenticated with both
 * `x-api-key` and `x-merchant-id`.
 *
 * Blocking on purpose, like [PaymentApiClient] — call it off the main thread.
 */
class WalletApiClient(
    private val apiKey: String,
    walletApiRoot: String,
    private val ownerReference: String,
    private val walletSessionId: String,
    private val merchantId: String,
) {
    private val root: String = walletApiRoot.trimEnd('/')

    /** One row of `GET /tokens` — web's `WalletItem`. */
    data class WalletToken(val id: String, val name: String?, val favorite: Boolean?)

    /**
     * `GET /tokens?vault=…`. A non-2xx answer returns an empty list, matching web (`if (!r.ok)
     * return []`) — wallet trouble must never break the checkout.
     */
    fun getTokens(vault: Boolean = true): List<WalletToken> {
        val body = try {
            execute("GET", "/tokens?vault=$vault", body = null)
        } catch (_: PaymentApiException) {
            return emptyList()
        }
        return try {
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { return@mapNotNull null }
                WalletToken(
                    id = id,
                    name = if (item.has("name")) item.optString("name") else null,
                    favorite = if (item.has("favorite")) item.optBoolean("favorite") else null,
                )
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    /** `DELETE /tokens/{id}`. */
    fun deleteToken(tokenId: String) {
        execute("DELETE", "/tokens/$tokenId", body = null)
    }

    /** `DELETE /tokens`. */
    fun deleteAllTokens() {
        execute("DELETE", "/tokens", body = null)
    }

    /** `PATCH /tokens/{id}/name` with `{name, reason}`. */
    fun editTokenName(tokenId: String, name: String, reason: String = "") {
        execute("PATCH", "/tokens/$tokenId/name", JSONObject().put("name", name).put("reason", reason).toString())
    }

    /** `PATCH /token/{id}/favorite?favorite=…` — note web's singular `token` segment. */
    fun setAsFavorite(tokenId: String, favorite: Boolean) {
        execute("PATCH", "/token/$tokenId/favorite?favorite=$favorite", body = null)
    }

    private fun execute(method: String, path: String, body: String?): String {
        val url = "$root/$ownerReference/sessions/$walletSessionId$path"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            // HttpURLConnection refuses PATCH as a request method; the conventional workaround —
            // which the Purse APIs accept — is POST + X-HTTP-Method-Override.
            if (method == "PATCH") {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                requestMethod = method
            }
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("x-merchant-id", merchantId)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            body?.let { payload -> connection.outputStream.use { it.write(payload.toByteArray()) } }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) {
                throw PaymentApiException(
                    "Wallet API $method $path failed: HTTP $code $responseBody",
                    status = code,
                    path = path,
                )
            }
            responseBody
        } catch (e: IOException) {
            throw PaymentApiException("Wallet API $method $path failed: ${e.message}", e, path = path)
        } finally {
            connection.disconnect()
        }
    }
}
