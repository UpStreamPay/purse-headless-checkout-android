package eu.purse.checkout.demo

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.demo.databinding.ActivityMainBinding
import eu.purse.checkout.payment.item.PaymentItem
import eu.purse.checkout.payment.item.PrimaryMethod
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal headless smoke test. "Create session (alfred)" mints a real Purse orchestration session
 * via the usp-widget dev server; "Initialize headless checkout" hands its `widget.data` to
 * [PurseHeadlessCheckout.init] and prints the resulting session, payment methods, and split state.
 *
 * Deliberately bare: raw [HttpURLConnection] on a background [Thread], no ViewModel/DI, no payment
 * UI. See docs/internal/testing-with-alfred.md.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets()

        binding.alfredBaseUrlText.text = getString(R.string.label_alfred_base_url, alfredBaseUrl())
        binding.createSessionButton.setOnClickListener { createSession() }
        binding.initCheckoutButton.setOnClickListener { initCheckout() }
    }

    /** targetSdk 35+ draws edge-to-edge; pad the content by the system-bar insets on top of 16dp. */
    private fun applyContentInsets() {
        val base = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            windowInsets
        }
    }

    private fun createSession() {
        val baseUrl = alfredBaseUrl()
        binding.createSessionButton.isEnabled = false
        Thread {
            try {
                // GET /order returns { "order": <legacyOrder> }; /orchestration_session runs
                // fromLegacyOrder on its request body, so we post the unwrapped legacy order.
                val legacyOrder = JSONObject(httpGet("$baseUrl/order")).getJSONObject("order")
                val session = JSONObject(httpPost("$baseUrl/orchestration_session", legacyOrder.toString()))
                val widgetData = session.getJSONObject("widget").getString("data")
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

    private fun initCheckout() {
        val widgetData = binding.widgetDataInput.text?.toString()?.trim()
        if (widgetData.isNullOrEmpty()) {
            binding.widgetDataLayout.error = getString(R.string.error_required)
            return
        }
        binding.widgetDataLayout.error = null
        binding.outputText.text = try {
            summarize(PurseHeadlessCheckout.init(widgetData))
        } catch (e: Exception) {
            getString(R.string.session_error, e.message)
        }
    }

    /** Render the SDK's initial reactive state — the smoke test's proof it decoded the session. */
    private fun summarize(checkout: PurseHeadlessCheckout): String = buildString {
        appendLine("session id:        ${checkout.currentSessionId}")
        appendLine("currency:          ${checkout.currency}")
        appendLine("amount:            ${checkout.session.amount}")
        appendLine("informationReq:    ${checkout.isInformationRequest}")
        appendLine("state:             ${checkout.sessionState.value}")
        appendLine("remainingToPay:    ${checkout.remainingAmountToPay.value}")
        appendLine("fulfilled:         ${checkout.isPaymentFulfilled.value}")
        appendLine("vault tenant:      ${checkout.session.vaultsConfiguration?.purse?.tenantId ?: "—"}")
        appendLine()
        appendLine("payment methods (${checkout.paymentMethods.value.size}):")
        checkout.paymentMethods.value.forEach { appendLine("  ${describe(it)}") }
    }

    private fun describe(item: PaymentItem): String {
        val kind = if (item.isSecondary) "secondary" else "primary"
        val disabled = item.disabled?.let { " [disabled: ${it.code}]" } ?: ""
        val integration = (item as? PrimaryMethod)?.integrationType?.let { " integration=${it.wireValue}" } ?: ""
        val brands = (item as? PrimaryMethod)?.supportedBrands
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",") { it.wireValue }
            ?.let { " brands=$it" } ?: ""
        return "${item.id} ($kind)$integration$brands$disabled"
    }

    /**
     * Alfred base URL from `ALFRED_BASE_URL` (env / .env, see app/build.gradle.kts). Falls back to
     * the emulator default so the demo runs out of the box.
     */
    private fun alfredBaseUrl(): String =
        BuildConfig.ALFRED_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: "http://10.0.2.2:9001"

    private fun httpGet(urlString: String): String = request(urlString, "GET", null)
    private fun httpPost(urlString: String, body: String): String = request(urlString, "POST", body)

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
}
