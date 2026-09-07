// Web: packages/partner-ui/src/libs/ingenico-onlinepayments
// Same: Worldline product-320 lookup refining networks and gateway.
// Differs: nothing.
package eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico

import android.util.Log
import eu.purse.partnerui.plugins.generics.googlepay.GooglePayConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Refines an Ingenico [GooglePayConfig] using Worldline's product-320 data — the Android port of
 * web's `libs/ingenico-onlinepayments` lookup. `/init` gives Ingenico's merchant/gateway ids but not
 * the supported card networks; this makes a direct `GET` to Worldline for them (and the precise
 * gateway string). Best-effort: any failure keeps the [GooglePayConfig] defaults.
 */
object WorldlineProductClient {

    private const val PREPROD = "https://payment.preprod.direct.worldline-solutions.com"

    /** Refine [config] from the ingenico `/init` `plugin_result.logs`; returns it unchanged on failure. */
    fun refine(config: GooglePayConfig, initLogs: JSONObject): GooglePayConfig = try {
        val customerId = initLogs.optString("customerId").ifBlank { error("no customerId") }
        val clientSessionId = initLogs.optString("clientSessionId").ifBlank { error("no clientSessionId") }
        val productId = initLogs.optString("productId").ifBlank { error("no productId") }
        val base = initLogs.optString("clientApiUrl").ifBlank { PREPROD }.trimEnd('/')
        val query = "?countryCode=${initLogs.optString("countryCode")}" +
            "&isRecurring=false" +
            "&amount=${initLogs.optString("centsAmount")}" +
            "&currencyCode=${initLogs.optString("currencyCode")}" +
            "&locale=${initLogs.optString("localeCode")}" +
            "&cacheBust=1"
        val body = get("$base/client/v1/$customerId/products/$productId$query", "GCS v1Client:$clientSessionId")
        parseProduct320(body, config)
    } catch (e: Exception) {
        Log.w("WorldlineProduct", "product-320 refine failed, keeping defaults: ${e.message}")
        config
    }

    /**
     * Pure parse of a product JSON body — overrides [config]'s `allowedCardNetworks` / `gateway`
     * from `paymentProduct320SpecificData` when present. Extracted so it can be unit-tested.
     */
    fun parseProduct320(body: String, config: GooglePayConfig): GooglePayConfig {
        val p320 = JSONObject(body).optJSONObject("paymentProduct320SpecificData") ?: return config
        val networks = p320.optJSONArray("networks")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it, null) } }
            .orEmpty()
        val gateway = p320.optString("gateway").takeIf { it.isNotBlank() }
        return config.copy(
            allowedCardNetworks = networks.ifEmpty { config.allowedCardNetworks },
            gateway = gateway ?: config.gateway,
        )
    }

    private fun get(urlString: String, authorization: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", authorization)
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from Worldline: $text")
            text
        } finally {
            connection.disconnect()
        }
    }
}
