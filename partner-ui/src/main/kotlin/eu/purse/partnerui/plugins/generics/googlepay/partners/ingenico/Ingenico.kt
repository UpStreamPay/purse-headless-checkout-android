package eu.purse.partnerui.plugins.generics.googlepay.partners.ingenico

import eu.purse.partnerui.plugins.generics.googlepay.GooglePayConfig
import eu.purse.partnerui.plugins.generics.googlepay.optStringOrNull
import org.json.JSONObject

/**
 * Ingenico (Worldline) — ports web's `plugins/generics/googlepay/partners/ingenico.ts`. The gateway
 * config is spread directly across `logs` (no nested JSON string). Networks aren't provided here —
 * they default to `[MASTERCARD, VISA]` and are refined by [WorldlineProductClient]. Gateway defaults
 * to [DEFAULT_GATEWAY].
 *
 * Alone among the builders it also carries `logs.countryCode` into the request's `transactionInfo`
 * (web's `ingenico.ts` does the same, off the same field its own product lookup is keyed on) — the
 * others have no country in `/init` and fall back to `"FR"`.
 */
internal object Ingenico {

    const val WIRE_VALUE = "ingenico"

    /** Web's ingenico default gateway; refined by the Worldline product-320 lookup when reachable. */
    const val DEFAULT_GATEWAY = "worldlineingenicoogone"

    /** Web's ingenico gatewayMerchantId fallback when `/init` doesn't carry one. */
    private const val DEFAULT_MERCHANT_ID = "UPSTREAMPAY"

    fun parseConfig(logs: JSONObject): GooglePayConfig = GooglePayConfig(
        gateway = DEFAULT_GATEWAY,
        gatewayMerchantId = logs.optStringOrNull("gatewayMerchantId") ?: DEFAULT_MERCHANT_ID,
        merchantId = logs.optStringOrNull("merchantId"),
        merchantName = logs.optStringOrNull("merchantName"),
        allowedCardNetworks = listOf("MASTERCARD", "VISA"),
        countryCode = logs.optStringOrNull("countryCode"),
    )
}
