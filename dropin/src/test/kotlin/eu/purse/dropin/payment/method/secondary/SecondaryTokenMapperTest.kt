package eu.purse.dropin.payment.method.secondary

import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.method.MethodConfiguration
import eu.purse.dropin.payment.method.MethodSettings
import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.payment.method.RegistrationMode
import eu.purse.dropin.payment.method.SecondaryMethod
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class SecondaryTokenMapperTest {

    @Test
    fun `buildInitPayload carries card, amount, partner and method`() {
        val payload = SecondaryTokenMapper.buildInitPayload(
            method = illicadoGiftCard(),
            pan = "9250004780123456789",
            cvv = "123",
            amount = BigDecimal("100.00"),
        )

        assertEquals("illicado", payload.getString("partner"))
        assertEquals("giftcard", payload.getString("method"))
        assertEquals("100.00", payload.get("amount").toString())
        val card = payload.getJSONObject("payment_data").getJSONObject("card")
        assertEquals("9250004780123456789", card.getString("number"))
        assertEquals("123", card.getString("csc"))
        assertEquals("none", payload.getJSONObject("payment_data").getString("vault_vendor"))
    }

    @Test
    fun `buildInitPayload omits csc when cvv is null`() {
        val payload = SecondaryTokenMapper.buildInitPayload(
            method = illicadoGiftCard(),
            pan = "9250004780123456789",
            cvv = null,
            amount = BigDecimal.ZERO,
        )
        val card = payload.getJSONObject("payment_data").getJSONObject("card")
        assertEquals(false, card.has("csc"))
    }

    @Test
    fun `parse extracts balance, masked name and expiry on success`() {
        val token = SecondaryTokenMapper.parse(initResponse(state = "SUCCESS", balance = "50.00"))

        assertEquals(Partner.Illicado, token.partner)
        assertEquals(Method.GiftCard, token.method)
        assertEquals(BigDecimal("50.00"), token.balance)
        assertEquals("•••• 4960", token.name)
        assertEquals("2030-12-31", token.expirationDate)
    }

    @Test
    fun `parse throws when init was not successful`() {
        assertThrows(PaymentApiException::class.java) {
            SecondaryTokenMapper.parse(initResponse(state = "ERROR", balance = "50.00"))
        }
    }

    @Test
    fun `parse throws when the card has no balance`() {
        assertThrows(PaymentApiException::class.java) {
            SecondaryTokenMapper.parse(initResponse(state = "SUCCESS", balance = "0"))
        }
    }

    private fun initResponse(state: String, balance: String): JSONObject = JSONObject(
        """
        {
          "partner": "illicado",
          "method": "giftcard",
          "status": { "state": "$state" },
          "plugin_result": {
            "payment_data": { "cards": [ { "balance": $balance, "expiry_date": "2030-12-31" } ] },
            "logs": { "masked_card": "•••• 4960" }
          }
        }
        """.trimIndent(),
    )

    private fun illicadoGiftCard(): DropinPaymentMethod = SecondaryMethod(
        id = "illicado-giftcard",
        partner = Partner.Illicado,
        method = Method.GiftCard,
        configuration = MethodConfiguration(
            stable = true,
            template = null,
            labels = listOf(MethodConfiguration.SECONDARY_LABEL),
            position = null,
            vault = null,
            settings = MethodSettings(
                registrationMode = RegistrationMode.UNKNOWN,
                saveToken = false,
                saveTokenMode = null,
                directDebit = false,
                paymentMinAmount = null,
                paymentMaxAmount = null,
                usagePerSessionLimit = null,
                integrationMode = null,
            ),
        ),
        disabled = null,
    )
}
