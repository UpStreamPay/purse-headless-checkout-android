package eu.purse.core.assets

import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.vault.CardBrand

/**
 * CDN badge/asset URL resolution — the Kotlin port of web's `@upstreampay/assets` package
 * (`index.ts`, `cdn.ts`, `card-brands.ts`). Pure URL builders; rendering is the integrator's
 * (load the SVGs with Coil or similar).
 */
object PurseAssets {

    data class CardSchemeAsset(val url: String, val label: String)

    /** Web's `getCdnBase` — production uses the secure CDN, every other tier the sandbox one. */
    fun cdnBase(environment: PurseEnvironment): String =
        if (environment == PurseEnvironment.PRODUCTION) {
            "https://cdn.purse-secure.com/assets/"
        } else {
            "https://cdn.purse-sandbox.com/assets/"
        }

    fun badgeUrl(environment: PurseEnvironment, badgeName: String): String =
        "${cdnBase(environment)}img/badges/$badgeName.svg"

    /**
     * The badge SVG for a `(partner, method)` — web's `resolveIconUrl`: a partner-specific badge,
     * a per-method override where one exists, or the generic primary/secondary fallback.
     */
    fun resolveIconUrl(
        partner: Partner,
        method: Method,
        isSecondary: Boolean,
        environment: PurseEnvironment,
    ): String {
        val entry = MOP_BADGES[partner.wireValue]
        val name = when {
            entry == null -> if (isSecondary) DEFAULT_SECONDARY else DEFAULT_PRIMARY
            else -> entry[method.wireValue] ?: entry.getValue(DEFAULT_KEY)
        }
        return badgeUrl(environment, name)
    }

    /** Web's `buildCardSchemeAssets` — badge + display label per known card scheme. */
    fun buildCardSchemeAssets(
        brands: List<CardBrand>,
        environment: PurseEnvironment,
    ): List<CardSchemeAsset> = brands.mapNotNull { brand ->
        CARD_SCHEME_BADGES[brand]?.let { (badge, label) ->
            CardSchemeAsset(badgeUrl(environment, badge), label)
        }
    }

    private const val DEFAULT_KEY = "default"
    private const val DEFAULT_PRIMARY = "creditcard"
    private const val DEFAULT_SECONDARY = "giftcard2"

    private fun badge(name: String): Map<String, String> = mapOf(DEFAULT_KEY to name)

    private val XPAY_CARD_PARTNER = mapOf(
        "applepay" to "applepay",
        "googlepay" to "googlepay",
        DEFAULT_KEY to "creditcard",
    )

    /** Web's `MOPs_BADGES`, keyed by partner wire value, then method wire value (or `default`). */
    private val MOP_BADGES: Map<String, Map<String, String>> = mapOf(
        "aci" to badge("creditcard"),
        "ancv" to badge("ancv"),
        "adyen" to XPAY_CARD_PARTNER + mapOf("bancontact" to "bancontact", "ideal" to "ideal"),
        "alma" to badge("alma"),
        "axepta" to XPAY_CARD_PARTNER,
        "braintree" to badge("paypal"),
        "centralpay" to badge("creditcard"),
        "checkout" to XPAY_CARD_PARTNER,
        "cofidis" to badge("cofidis"),
        "dalenys" to badge("creditcard"),
        "easycollect" to badge("creditcard"),
        "fintecture" to badge("fintecture"),
        "floa" to badge("floa"),
        "gocardless" to badge("giftcard2"),
        "hipay" to badge("creditcard") + mapOf("mbway" to "mbway", "multibanco" to "multibanco"),
        "illicado" to badge("illicado"),
        "easy2play" to mapOf("voucher" to "voucher", DEFAULT_KEY to "giftcard2"),
        "ingenico" to XPAY_CARD_PARTNER,
        "klarna" to badge("klarna"),
        "mercanet" to badge("creditcard"),
        "oney" to badge("oney"),
        "oneycard" to badge("oney"),
        "paybox" to badge("creditcard"),
        "payline" to badge("creditcard") + mapOf("mbway" to "mbway", "multibanco" to "multibanco"),
        "paypal" to badge("paypal"),
        "payzen" to badge("creditcard") + mapOf("sepa" to "sepa"),
        "purse" to badge("creditcard"),
        "redsys" to badge("creditcard"),
        "riverty" to badge("riverty"),
        "scalapay" to badge("scalapay"),
        "sipsv2" to badge("creditcard"),
        "slimpay" to badge("slimpay"),
        "sogecommerce" to badge("creditcard"),
        "sogenactif" to badge("creditcard"),
        "stripe" to XPAY_CARD_PARTNER,
        "systempay" to badge("creditcard"),
        "worldpay" to badge("creditcard"),
        "scellius" to badge("sepa"),
        "younited" to badge("younited"),
    )

    /** Web's `CARD_SCHEME_BADGES`. */
    private val CARD_SCHEME_BADGES: Map<CardBrand, Pair<String, String>> = mapOf(
        CardBrand.AMERICAN_EXPRESS to ("amex" to "American Express"),
        CardBrand.CARTE_BANCAIRE to ("cb" to "Cartes Bancaires"),
        CardBrand.VISA to ("visa" to "Visa"),
        CardBrand.MASTERCARD to ("mastercard" to "Mastercard"),
        CardBrand.MAESTRO to ("maestro" to "Maestro"),
        CardBrand.ONEY to ("oney" to "Oney"),
    )
}
