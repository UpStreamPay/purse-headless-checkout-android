// Web: packages/headless-checkout `setSession(widgetData: string)` base64 decode
// Same: same blob.
// Differs: nothing.
package eu.purse.core.session

import eu.purse.core.environment.PurseEnvironment
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * Decodes the base64-encoded `widget.data` blob — see
 * docs/architecture/headless-checkout.md.
 */
object SessionCodec {

    /**
     * @throws PurseSessionException.SessionMalformed if [widgetData] isn't valid base64/JSON,
     * or the decoded session fails the checks in [LegacyPaymentSessionParser].
     */
    fun decode(widgetData: String): PurseSessionParams {
        val json = try {
            JSONObject(String(Base64.getDecoder().decode(widgetData), Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw PurseSessionException.SessionMalformed("could not base64-decode widget data: ${e.message}")
        } catch (e: JSONException) {
            throw PurseSessionException.SessionMalformed("could not parse widget data as JSON: ${e.message}")
        }

        val paymentSessionJson = json.optJSONObject("payment_session")
            ?: throw PurseSessionException.SessionMalformed("payment_session is missing from widget data")

        return PurseSessionParams(
            apiKey = json.optStringOrNull("api_key") ?: "",
            apiUrl = json.optStringOrNull("api_url") ?: "",
            environment = PurseEnvironment.fromWire(json.optStringOrNull("environment"))
                ?: throw PurseSessionException.SessionMalformed(
                    "unknown or missing environment: ${json.optStringOrNull("environment")}",
                ),
            entityId = json.optStringOrNull("entity_id") ?: "",
            paymentSession = LegacyPaymentSessionParser.parse(paymentSessionJson),
            walletSession = json.optJSONObject("wallet_session")?.let { wallet ->
                WalletSessionParams(
                    sessionId = wallet.optStringOrNull("session_id") ?: return@let null,
                    ownerReference = wallet.optStringOrNull("owner_reference") ?: return@let null,
                    merchant = wallet.optStringOrNull("merchant") ?: "",
                    expirationDate = wallet.optStringOrNull("expiration_date"),
                )
            },
        )
    }
}
