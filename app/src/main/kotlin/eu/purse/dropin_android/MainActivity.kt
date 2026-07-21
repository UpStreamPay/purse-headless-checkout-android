package eu.purse.dropin_android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import eu.purse.dropin_android.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Session-builder screen. Mirrors `softpos-android`'s demo app: manual View-system UI (no
 * ViewModel, no DI), and a raw [HttpURLConnection] call on a background [Thread] — no
 * Retrofit/coroutines. See docs/internal/testing-with-alfred.md.
 *
 * "Create session" hits usp-widget's alfred dev server to mint a real Purse orchestration session;
 * "Initialize Dropin SDK" then hands its `widget.data` to [DropinActivity], which decodes the
 * session and renders the payment-method list on its own page.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // The orchestration session's own top-level `id` (the v2 ClientSession id `POST
    // /orchestration_session` itself returns — distinct from `widget.data`'s `payment_session.id`,
    // see docs/decisions/pain-points-recap.md) — kept around only so DropinActivity's alfred debug
    // tool has a sensible default to fall back to, mirroring usp-widget's demo confirmation page.
    private var lastOrchestrationSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets()

        binding.alfredBaseUrlText.text = getString(R.string.label_alfred_base_url, alfredBaseUrl())
        binding.createSessionButton.setOnClickListener { createSession() }
        binding.initDropinButton.setOnClickListener { initializeDropin() }
    }

    /**
     * targetSdk 35+ draws edge-to-edge by default, so without this the content scrolls under the
     * status/navigation bars. Pads the scrollable content by the system-bar insets on top of its
     * base 16dp so nothing overlaps the bars.
     */
    private fun applyContentInsets() {
        val base = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = base + bars.left,
                top = base + bars.top,
                right = base + bars.right,
                bottom = base + bars.bottom,
            )
            windowInsets
        }
    }

    private fun createSession() {
        val baseUrl = alfredBaseUrl()
        binding.createSessionButton.isEnabled = false
        Thread {
            try {
                // Two-step: fetch alfred's demo legacy order, then create a session from it.
                // GET /order returns { "order": <legacyOrder> }; /orchestration_session runs
                // fromLegacyOrder on its request body itself, so we post the unwrapped legacy order.
                val orderResponse = httpGet("$baseUrl/order")
                val legacyOrder = JSONObject(orderResponse).getJSONObject("order")
                val sessionResponse = JSONObject(httpPost("$baseUrl/orchestration_session", legacyOrder.toString()))
                val widgetData = sessionResponse.getJSONObject("widget").getString("data")
                lastOrchestrationSessionId = sessionResponse.optString("id").takeIf { it.isNotBlank() }
                runOnUiThread {
                    binding.widgetDataInput.setText(widgetData)
                    binding.widgetDataLayout.error = null
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_create_session_failed, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { binding.createSessionButton.isEnabled = true }
            }
        }.start()
    }

    private fun initializeDropin() {
        val widgetData = binding.widgetDataInput.text?.toString()?.trim()
        if (widgetData.isNullOrEmpty()) {
            binding.widgetDataLayout.error = getString(R.string.error_required)
            return
        }
        binding.widgetDataLayout.error = null

        // The session is decoded and rendered on its own page (DropinActivity), not under the
        // builder — we pass the raw widget.data and let it call DropinAndroid.init().
        startActivity(
            Intent(this, DropinActivity::class.java)
                .putExtra(DropinActivity.EXTRA_WIDGET_DATA, widgetData)
                .putExtra(DropinActivity.EXTRA_ORCHESTRATION_SESSION_ID, lastOrchestrationSessionId)
        )
    }

    /**
     * Alfred dev server base URL, sourced from the `ALFRED_BASE_URL` env var / `.env` file at
     * build time (see app/build.gradle.kts and .env.example). Falls back to the emulator default
     * when unset so the demo still runs out of the box.
     */
    private fun alfredBaseUrl(): String =
        BuildConfig.ALFRED_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() }
            ?: "http://10.0.2.2:9001"

    private fun httpGet(urlString: String): String = request(urlString, method = "GET", body = null)

    private fun httpPost(urlString: String, body: String? = null): String =
        request(urlString, method = "POST", body = body)

    private fun request(urlString: String, method: String, body: String?): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (body != null) {
                    connection.outputStream.use { it.write(body.toByteArray()) }
                } else {
                    connection.outputStream.close()
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code from $urlString: $responseBody")
            }
            responseBody
        } finally {
            connection.disconnect()
        }
    }
}
