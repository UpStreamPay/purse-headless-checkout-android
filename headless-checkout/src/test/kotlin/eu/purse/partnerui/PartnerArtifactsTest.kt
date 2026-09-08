package eu.purse.partnerui

import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartnerArtifactsTest {

    @Test
    fun `each optional partner maps to its artifact`() {
        assertEquals("eu.purse:headless-checkout-googlepay", PartnerArtifacts.forMethod(Partner.Other("adyen"), Method.GooglePay))
        assertEquals("eu.purse:headless-checkout-klarna", PartnerArtifacts.forMethod(Partner.Klarna, Method.Installments))
        assertEquals("eu.purse:headless-checkout-paypal", PartnerArtifacts.forMethod(Partner.Paypal, Method.Wallet))
    }

    @Test
    fun `methods the engine ships itself or unknown pairs map to nothing`() {
        assertNull(PartnerArtifacts.forMethod(Partner.Other("aci"), Method.CreditCard))
        assertNull(PartnerArtifacts.forMethod(Partner.Paypal, Method.Installments))
    }

    @Test
    fun `the message names the dependency when there is one`() {
        assertEquals(
            "no plugin registered for klarna/installments — " +
                "add the `eu.purse:headless-checkout-klarna` dependency to offer this method",
            PartnerArtifacts.missingPluginMessage(Partner.Klarna, Method.Installments),
        )
        val card = PartnerArtifacts.missingPluginMessage(Partner.Other("aci"), Method.CreditCard)
        assertEquals("no plugin registered for aci/creditcard", card)
    }
}
