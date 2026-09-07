package eu.purse.core.assets

import eu.purse.core.environment.PurseEnvironment
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.vault.CardBrand
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ports the web `@upstreampay/assets` doc examples and fallbacks. */
class PurseAssetsTest {

    @Test
    fun `resolves partner badges - the web doc examples`() {
        assertEquals(
            "https://cdn.purse-secure.com/assets/img/badges/paypal.svg",
            PurseAssets.resolveIconUrl(Partner.Paypal, Method.Wallet, false, PurseEnvironment.PRODUCTION),
        )
        assertEquals(
            "https://cdn.purse-sandbox.com/assets/img/badges/googlepay.svg",
            PurseAssets.resolveIconUrl(Partner.Adyen, Method.GooglePay, false, PurseEnvironment.SANDBOX),
        )
    }

    @Test
    fun `a per-method map falls back to its default entry`() {
        assertEquals(
            "https://cdn.purse-sandbox.com/assets/img/badges/creditcard.svg",
            PurseAssets.resolveIconUrl(Partner.Adyen, Method.CreditCard, false, PurseEnvironment.TEST),
        )
    }

    @Test
    fun `an unknown partner falls back per primary or secondary`() {
        val unknown = Partner.Other("nonexistent")
        assertEquals(
            "https://cdn.purse-sandbox.com/assets/img/badges/creditcard.svg",
            PurseAssets.resolveIconUrl(unknown, Method.CreditCard, false, PurseEnvironment.SANDBOX),
        )
        assertEquals(
            "https://cdn.purse-sandbox.com/assets/img/badges/giftcard2.svg",
            PurseAssets.resolveIconUrl(unknown, Method.GiftCard, true, PurseEnvironment.SANDBOX),
        )
    }

    @Test
    fun `card scheme assets keep order and skip unknown schemes`() {
        val assets = PurseAssets.buildCardSchemeAssets(
            listOf(CardBrand.CARTE_BANCAIRE, CardBrand.UNKNOWN, CardBrand.VISA),
            PurseEnvironment.PRODUCTION,
        )

        assertEquals(2, assets.size)
        assertEquals("Cartes Bancaires", assets[0].label)
        assertEquals("https://cdn.purse-secure.com/assets/img/badges/cb.svg", assets[0].url)
        assertEquals("Visa", assets[1].label)
    }
}
