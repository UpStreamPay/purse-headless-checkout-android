package eu.purse.partnerui.plugins.vaults.purse.creditcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardMapperTest {

    @Test
    fun `validate payment data carries the vault token at the top level, not nested under card`() {
        val data = CardMapper.buildValidatePaymentData(
            vaultVendorTokenId = "vault-token-abc",
            bin = "411111",
            selectedNetwork = "CARTE_BANCAIRE",
        )
        assertEquals("vault-token-abc", data.getString("vault_vendor_token_id"))
        assertEquals("purse", data.getString("vault_vendor"))
        assertEquals("411111", data.getJSONObject("card").getString("bin"))
        assertEquals("CARTE_BANCAIRE", data.getJSONObject("card").getString("selected_network"))
    }

    @Test
    fun `validate payment data omits blank bin and selected network`() {
        val data = CardMapper.buildValidatePaymentData("vault-token-abc", bin = null, selectedNetwork = "")
        val card = data.getJSONObject("card")
        assertTrue(!card.has("bin"))
        assertTrue(!card.has("selected_network"))
    }

    @Test
    fun `token payment data carries the saved instrument like web patchItemPayloadWithToken`() {
        val data = CardMapper.buildTokenPaymentData(
            eu.purse.partnerui.PayToken(
                value = "tok-value-1",
                expirationDate = "2027-08-31T00:00:00Z",
                holderName = "J DOE",
                selectedNetwork = "VISA",
            ),
        )

        assertEquals("tok-value-1", data.getString("token"))
        assertEquals("purse", data.getString("vault_vendor"))
        assertEquals("J DOE", data.getString("holder"))
        assertEquals("2027-08-31", data.getJSONObject("card").getString("expiry_date"))
        assertEquals("VISA", data.getJSONObject("card").getString("selected_network"))
        assertTrue("no fresh vault token when paying a saved one", !data.has("vault_vendor_token_id"))
    }

    @Test
    fun `token payment data pairs the CVV-only vault token with the saved token like web submitForResult`() {
        val data = CardMapper.buildTokenPaymentData(
            eu.purse.partnerui.PayToken(value = "tok-value-1", selectedNetwork = "VISA", requiresCvv = true),
            vaultVendorTokenId = "cvv-vault-token",
        )

        assertEquals("tok-value-1", data.getString("token"))
        assertEquals("cvv-vault-token", data.getString("vault_vendor_token_id"))
        assertEquals("purse", data.getString("vault_vendor"))
        assertEquals("VISA", data.getJSONObject("card").getString("selected_network"))
    }
}
