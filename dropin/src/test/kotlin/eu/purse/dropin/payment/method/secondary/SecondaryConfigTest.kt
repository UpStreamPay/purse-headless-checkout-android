package eu.purse.dropin.payment.method.secondary

import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.method.MethodConfiguration
import eu.purse.dropin.payment.method.MethodSettings
import eu.purse.dropin.payment.method.RegistrationMode
import eu.purse.dropin.payment.method.SecondaryMethod
import eu.purse.dropin.payment.partner.Partner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SecondaryConfigTest {

    @Test
    fun `illicado accepts a valid pan for both issuer prefixes`() {
        assertTrue(SecondaryConfig.Illicado.validatePan("9250004780123456789"))
        assertTrue(SecondaryConfig.Illicado.validatePan("9250004100123456789"))
    }

    @Test
    fun `illicado rejects pans with the wrong prefix, length, or non-digits`() {
        assertFalse(SecondaryConfig.Illicado.validatePan("9250004790123456789")) // wrong prefix
        assertFalse(SecondaryConfig.Illicado.validatePan("925000478012345678"))  // 18 digits
        assertFalse(SecondaryConfig.Illicado.validatePan("92500047801234567890")) // 20 digits
        assertFalse(SecondaryConfig.Illicado.validatePan("925000478012345678x")) // non-digit
    }

    @Test
    fun `illicado always requires a 3-digit cvv`() {
        assertTrue(SecondaryConfig.Illicado.requiresCvv(""))
        assertTrue(SecondaryConfig.Illicado.requiresCvv("9250004780123456789"))
        assertTrue(SecondaryConfig.Illicado.validateCvv("123"))
        assertFalse(SecondaryConfig.Illicado.validateCvv("12"))
        assertFalse(SecondaryConfig.Illicado.validateCvv("1234"))
        assertFalse(SecondaryConfig.Illicado.validateCvv("12a"))
    }

    @Test
    fun `forMethod resolves Illicado gift cards and nothing else`() {
        assertEquals(SecondaryConfig.Illicado, SecondaryConfig.forMethod(giftCard(Partner.Illicado, Method.GiftCard)))
        assertNull(SecondaryConfig.forMethod(giftCard(Partner.Paypal, Method.GiftCard)))
        assertNull(SecondaryConfig.forMethod(giftCard(Partner.Illicado, Method.CreditCard)))
    }

    private fun giftCard(partner: Partner, method: Method): DropinPaymentMethod = SecondaryMethod(
        id = "${partner.wireValue}-${method.wireValue}",
        partner = partner,
        method = method,
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
                paymentMinAmount = BigDecimal.ZERO,
                paymentMaxAmount = null,
                usagePerSessionLimit = null,
                integrationMode = null,
            ),
        ),
        disabled = null,
    )
}
