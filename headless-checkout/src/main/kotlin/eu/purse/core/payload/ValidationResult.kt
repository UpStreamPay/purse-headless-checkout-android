// Web: packages/model /validate response
// Same: `global_status` + optional `redirection`.
// Differs: raw JSON kept for the redirect resolver.
package eu.purse.core.payload

import org.json.JSONObject

/**
 * The `/validate` response — the typed Kotlin analog of web's `ValidationResultData`
 * (`packages/sdk/src/API/types.ts`): the overall [globalStatus], an optional [redirection] the
 * merchant must follow (3-D Secure, PayPal approval, …), and the [raw] body for anything a plugin's
 * `postValidation` still needs to inspect. JSON stays at the edge.
 */
data class ValidationResult(
    val globalStatus: String,
    val redirection: Redirection?,
    val raw: JSONObject,
) {
    /** [globalStatus] as the typed [PaymentStatus]; `UNKNOWN` for a value this SDK does not know. */
    val status: PaymentStatus get() = PaymentStatus.fromWire(globalStatus)

    /** Web treats `global_status === SUCCESS` as the go-ahead for post-validation. */
    val isSuccess: Boolean get() = status == PaymentStatus.SUCCESS

    data class Redirection(
        val shouldSubmitWidget: Boolean,
        val url: String?,
        val form: String?,
        val name: String?,
    )

    companion object {
        fun fromJson(json: JSONObject): ValidationResult {
            val redirection = json.optJSONObject("redirection")?.let {
                Redirection(
                    shouldSubmitWidget = it.optBoolean("should_submit_widget", false),
                    url = it.optString("url").takeIf(String::isNotBlank),
                    form = it.optString("form").takeIf(String::isNotBlank),
                    name = it.optString("name").takeIf(String::isNotBlank),
                )
            }
            return ValidationResult(
                globalStatus = json.optString("global_status"),
                redirection = redirection,
                raw = json,
            )
        }
    }
}
