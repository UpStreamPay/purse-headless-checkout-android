// Web: packages/headless-checkout/src/headless-checkout-validation.ts `summarizeValidationResults`
// Same: field allowlist per attempt, caps, `onlyFailures`.
// Differs: the error payload also names the Purse error code and the failing API call — an Android
//          stack trace says nothing once R8 has run, the codes do.
package eu.purse.checkout.monitoring

import eu.purse.core.api.PaymentApiException
import eu.purse.core.error.PurseException
import org.json.JSONArray
import org.json.JSONObject

/** Attempts kept per event — a split is a handful of items; anything beyond this is a runaway response. */
private const val MAX_SUMMARIZED_RESULTS = 5

/** `status.description` is the only free-text field the API returns per attempt, so it is the only one capped. */
private const val MAX_DESCRIPTION_LENGTH = 200

/** Web caps its stacks at 500–1000 characters; a minified Android stack is worth no more. */
private const val MAX_STACK_LENGTH = 1_000

/** An exception message is free text (partner wording, merchant wording): capped like a description, wider. */
private const val MAX_MESSAGE_LENGTH = 500

/**
 * Flattens a `/validate` response into the per-attempt detail the monitoring events carry (SDK-12197):
 * `method`, `partner`, `status { state, code, description }` and `plugin_status` — the partner-side
 * outcome behind the Purse status code, without which a `TECHNICAL_ISSUE_TO_CONTACT_PARTNER` caused by
 * a timeout and one caused by a refusal read the same.
 *
 * Deliberately a field allowlist rather than a copy of the response: `plugin_result` as a whole
 * carries partner payloads (`off_session_data` and friends) that have no place in a log, so only its
 * `status` is taken. No amount, no transaction id. Defensive throughout — this runs on the error
 * path, where the response may well be the malformed thing that broke the payment.
 *
 * [onlyFailures] drops the attempts that succeeded: on the error path a consumed voucher is not the
 * diagnosis, and a split would otherwise log every leg of a payment on every event.
 */
internal fun summarizeValidationResults(response: JSONObject?, onlyFailures: Boolean = false): JSONArray {
    val results = response?.optJSONArray("results") ?: return JSONArray()
    val attempts = buildList { for (index in 0 until results.length()) results.optJSONObject(index)?.let(::add) }
    val summary = JSONArray()
    attempts.asSequence()
        .filterNot { onlyFailures && it.optJSONObject("status")?.optString("state") == "SUCCESS" }
        .take(MAX_SUMMARIZED_RESULTS)
        .forEach { result ->
            val status = result.optJSONObject("status")
            summary.put(
                monitoringPayload(
                    "method" to result.optString("method").ifBlank { null },
                    "partner" to result.optString("partner").ifBlank { null },
                    "status" to monitoringPayload(
                        "state" to status?.optString("state")?.ifBlank { null },
                        "code" to status?.optString("code")?.ifBlank { null },
                        "description" to status?.optString("description")?.ifBlank { null }?.take(MAX_DESCRIPTION_LENGTH),
                    ),
                    "plugin_status" to result.optJSONObject("plugin_result")?.optString("status")?.ifBlank { null },
                ),
            )
        }
    return summary
}

/**
 * The payload of an error-shaped event for [error]: a capped `message`, the stable Purse `code`
 * when there is one, and — when a Payment API call is the cause, anywhere down the chain — which call
 * (`api_path`), how it answered (`api_status`, `api_error`) and whether the answer never came
 * (`outcome_unknown`). The capped `stack` is web's; [extra] pairs land as they are.
 *
 * Never the response body: [PaymentApiException] keeps it out of its message for the same reason.
 */
internal fun errorPayload(error: Throwable, message: String? = error.message, vararg extra: Pair<String, Any?>): JSONObject {
    val api = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
        .filterIsInstance<PaymentApiException>()
        .firstOrNull()
    return monitoringPayload(
        "message" to message?.take(MAX_MESSAGE_LENGTH),
        "code" to (error as? PurseException)?.code?.name,
        "api_path" to api?.path,
        "api_status" to api?.status,
        "api_error" to api?.errorCode,
        "outcome_unknown" to api?.outcomeUnknown?.takeIf { it },
        "stack" to error.stackTraceToString().take(MAX_STACK_LENGTH),
        *extra,
    )
}
