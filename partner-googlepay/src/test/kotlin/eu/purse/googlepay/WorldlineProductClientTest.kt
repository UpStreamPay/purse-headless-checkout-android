package eu.purse.googlepay

import org.junit.Assert.assertEquals
import org.junit.Test

class WorldlineProductClientTest {

    private val base = GooglePayConfig(
        gateway = GooglePayMapper.INGENICO_DEFAULT_GATEWAY,
        gatewayMerchantId = "GMID",
        merchantId = "MID",
        merchantName = "Acme",
        allowedCardNetworks = listOf("MASTERCARD", "VISA"),
    )

    @Test
    fun `product-320 data refines networks and gateway`() {
        val body = """
            { "paymentProduct320SpecificData": { "networks": ["VISA","MASTERCARD","AMEX"], "gateway": "worldline" } }
        """.trimIndent()

        val refined = WorldlineProductClient.parseProduct320(body, base)

        assertEquals(listOf("VISA", "MASTERCARD", "AMEX"), refined.allowedCardNetworks)
        assertEquals("worldline", refined.gateway)
    }

    @Test
    fun `a body without product-320 data keeps the defaults`() {
        val refined = WorldlineProductClient.parseProduct320("{}", base)
        assertEquals(base, refined)
    }

    @Test
    fun `empty networks keep the defaults but gateway still refines`() {
        val body = """{ "paymentProduct320SpecificData": { "networks": [], "gateway": "worldline" } }"""
        val refined = WorldlineProductClient.parseProduct320(body, base)
        assertEquals(base.allowedCardNetworks, refined.allowedCardNetworks)
        assertEquals("worldline", refined.gateway)
    }
}
