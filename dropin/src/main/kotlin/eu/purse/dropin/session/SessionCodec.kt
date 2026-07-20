package eu.purse.dropin.session

import eu.purse.dropin.core.environment.PurseEnvironment
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * Decodes the base64-encoded `widget.data` blob — see
 * docs/architecture/session-and-protocols.md#decoding-widgetdata.
 */
object SessionCodec {

    /**
     * @throws DropinSessionException.SessionMalformed if [widgetData] isn't valid base64/JSON,
     * or the decoded session fails the checks in [LegacyPaymentSessionParser].
     */
    fun decode(widgetData: String): PurseSessionParams {
        val json = try {
            JSONObject(String(Base64.getDecoder().decode(widgetData), Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw DropinSessionException.SessionMalformed("could not base64-decode widget data: ${e.message}")
        } catch (e: JSONException) {
            throw DropinSessionException.SessionMalformed("could not parse widget data as JSON: ${e.message}")
        }

        val paymentSessionJson = json.optJSONObject("payment_session")
            ?: throw DropinSessionException.SessionMalformed("payment_session is missing from widget data")

        return PurseSessionParams(
            apiKey = json.optStringOrNull("api_key") ?: "",
            apiUrl = json.optStringOrNull("api_url") ?: "",
            environment = PurseEnvironment.fromWire(json.optStringOrNull("environment"))
                ?: throw DropinSessionException.SessionMalformed(
                    "unknown or missing environment: ${json.optStringOrNull("environment")}",
                ),
            entityId = json.optStringOrNull("entity_id") ?: "",
            paymentSession = LegacyPaymentSessionParser.parse(paymentSessionJson),
        )
    }
}
