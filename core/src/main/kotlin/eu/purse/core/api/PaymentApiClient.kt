package eu.purse.core.api

import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payload.ValidationResult
import eu.purse.core.payment.LoanProduct
import eu.purse.core.payment.LoanSimulation
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
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
 * Blocking on purpose — this module pulls in no OkHttp, so callers must invoke it off the main
 * thread (`:headless-checkout` does so behind its coroutine-backed surface).
 *
 * @param apiRoot the Payment API root, e.g. `https://api.purse-test.com/payment/v1` (see
 * [eu.purse.core.environment.PurseEnvironment.paymentApiRoot]). A trailing slash is tolerated.
 */
class PaymentApiClient(
    private val apiKey: String,
    apiRoot: String,
    private val entityId: String,
    private val sessionId: String,
) {
    private val root: String = apiRoot.trimEnd('/')

    /**
     * `POST /init` — see docs/architecture/headless-checkout.md#init-and-validate. Runs under
     * [InitGuard]: concurrent duplicates coalesce, transient failures retry with backoff, and a
     * terminal failure fast-rejects immediate repeats of the same call.
     */
    fun init(payload: JSONObject): JSONObject =
        InitGuard.run(InitGuard.keyFor(sessionId, payload)) { post("/init", payload.toString()) }

    /**
     * `POST /validate` — the validate call takes a JSON *array* of items (one per method being
     * submitted), mirroring web's `FetchPaymentAPI.validate`. Returns the `{results, redirection,
     * global_status}` object. See docs/architecture/headless-checkout.md#init-and-validate.
     */
    fun validate(payload: JSONArray): JSONObject = post("/validate", payload.toString())

    /**
     * Typed `POST /validate` — serializes each [ValidateItemPayload] and parses the response into a
     * [ValidationResult]. This is what `:headless-checkout`'s "create payment" pipeline calls.
     */
    fun validate(items: List<ValidateItemPayload>): ValidationResult {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        return ValidationResult.fromJson(validate(array))
    }

    /**
     * `GET /{entityId}/sessions/{sessionId}` — every transaction (`/init`/`/validate` call) made
     * so far against this session, each with its own `status`. This is the source of truth to
     * re-query after a capture rather than trusting a locally-held `/validate` result alone —
     * confirmed against Purse's own published spec/tutorial (see
     * https://docs.purse.tech/docs/integrate/purse-api/tutorials/simple-payment and
     * docs/architecture/headless-checkout.md).
     */
    fun getSession(): JSONArray {
        val responseBody = execute("GET", path = "", body = null)
        return try {
            JSONArray(responseBody)
        } catch (e: JSONException) {
            throw PaymentApiException("Payment API GET session returned non-JSON: ${e.message}", e, path = "")
        }
    }

    /**
     * `POST {root}/simulation/{entityId}/{partner}/{method}` — one BNPL instalment schedule for
     * [product], web's `FetchPaymentAPI.getSimulation`. Not session-scoped: the merchant entity
     * is enough, so the URL skips `/sessions/{sessionId}`.
     *
     * @throws PaymentApiException with `errorCode == INVALID_LOAN_SIMULATION` when the response
     * has no `plugin_result` (web's `INVALID_LOAN_SIMULATION`); `:headless-checkout` maps it to
     * `PurseErrorCode.INVALID_LOAN_SIMULATION`. Transport/HTTP failures as for every other call.
     */
    fun getSimulation(partner: Partner, method: Method, product: LoanProduct): LoanSimulation {
        val path = simulationPath(partner, method)
        val response = post(path, product.toJson().toString(), url = "$root$path")
        return LoanSimulation.fromResponse(response) ?: throw PaymentApiException(
            "Payment API $path returned no usable simulation data",
            errorCode = INVALID_LOAN_SIMULATION,
            path = path,
        )
    }

    /**
     * `POST {root}/simulation/{entityId}/{partner}/{method}/multi` — every schedule the partner
     * offers for [product], web's `getSimulationMulti`. Candidates without a `plugin_result` are
     * skipped rather than failing the call, matching web.
     */
    fun getSimulationMulti(partner: Partner, method: Method, product: LoanProduct): List<LoanSimulation> {
        val path = simulationPath(partner, method) + "/multi"
        val responseBody = execute("POST", path, product.toJson().toString(), url = "$root$path")
        val candidates = try {
            JSONArray(responseBody)
        } catch (e: JSONException) {
            throw PaymentApiException("Payment API $path returned non-JSON array: ${e.message}", e, path = path)
        }
        return (0 until candidates.length()).mapNotNull { index ->
            candidates.optJSONObject(index)?.let(LoanSimulation::fromResponse)
        }
    }

    private fun simulationPath(partner: Partner, method: Method): String =
        "/simulation/$entityId/${partner.wireValue}/${method.wireValue}"

    /**
     * The `error` code from an API JSON error body, or `null` when the body isn't that shape —
     * web's `#init` parses the same field to classify `SESSION_ALREADY_SUBMITTED_ERROR` /
     * `SESSION_EXPIRED` / `NOT_FOUND`.
     */
    private fun parseErrorCode(responseBody: String): String? = try {
        JSONObject(responseBody).optString("error").ifBlank { null }
    } catch (_: JSONException) {
        null
    }

    private fun post(path: String, body: String, url: String = sessionUrl(path)): JSONObject {
        val responseBody = execute("POST", path, body, url)
        return try {
            JSONObject(responseBody)
        } catch (e: JSONException) {
            throw PaymentApiException("Payment API $path returned non-JSON: ${e.message}", e, path = path)
        }
    }

    /** Session-scoped URL — every call but the simulation ones lives under the session. */
    private fun sessionUrl(path: String): String = "$root/$entityId/sessions/$sessionId$path"

    private fun execute(method: String, path: String, body: String?, url: String = sessionUrl(path)): String =
        eu.purse.core.monitoring.ApiCallMonitor.time(method, path) { executeUnmonitored(method, path, body, url) }

    private fun executeUnmonitored(method: String, path: String, body: String?, url: String): String {
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
                throw PaymentApiException(
                    "Payment API $method $path failed: HTTP $code $responseBody",
                    status = code,
                    errorCode = parseErrorCode(responseBody),
                    path = path,
                )
            }
            responseBody
        } catch (e: IOException) {
            throw PaymentApiException("Payment API $method $path failed: ${e.message}", e, path = path)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** [PaymentApiException.errorCode] for a `/simulation` answer without `plugin_result`. */
        const val INVALID_LOAN_SIMULATION = "INVALID_LOAN_SIMULATION"
    }
}
