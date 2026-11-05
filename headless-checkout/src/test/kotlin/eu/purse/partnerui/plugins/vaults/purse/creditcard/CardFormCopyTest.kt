package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.checkout.R
import eu.purse.partnerui.CardFormTexts
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardFormCopyTest {

    private val frenchDevice = Locale.FRANCE

    @Test
    fun `the checkout locale wins over the device's`() {
        assertEquals(Locale.UK, cardFormLocale("en-GB", frenchDevice))
    }

    @Test
    fun `an underscore-separated checkout locale is read as BCP-47`() {
        assertEquals(Locale.UK, cardFormLocale("en_GB", frenchDevice))
    }

    @Test
    fun `a bare language is enough`() {
        assertEquals("en", cardFormLocale("en", frenchDevice).language)
    }

    @Test
    fun `no checkout locale follows the device`() {
        assertEquals(frenchDevice, cardFormLocale(null, frenchDevice))
        assertEquals(frenchDevice, cardFormLocale("  ", frenchDevice))
    }

    @Test
    fun `a checkout locale naming no language follows the device`() {
        assertEquals(frenchDevice, cardFormLocale("!!", frenchDevice))
    }

    @Test
    fun `defaults come from the lookup, merchant copy first`() {
        val texts = resolveCardTexts(
            CardFormTexts(cardNumber = CardFormTexts.FieldTexts(label = "N° de carte", formatError = "Numéro faux.")),
            ::lookup,
        )

        assertEquals("N° de carte", texts.cardNumber.label)
        assertEquals("Numéro faux.", texts.cardNumber.formatError)
        assertEquals(lookup(R.string.purse_card_number_placeholder), texts.cardNumber.placeholder)
        assertEquals(lookup(R.string.purse_card_number_required), texts.cardNumber.requiredError)
        assertEquals(lookup(R.string.purse_card_cvv_label), texts.cvv.label)
        assertEquals(lookup(R.string.purse_card_exp_date_invalid), texts.expDate.formatError)
    }

    @Test
    fun `a blank default helper text means none`() {
        val texts = resolveCardTexts(CardFormTexts()) { if (it == R.string.purse_card_holder_name_helper) "" else lookup(it) }

        assertNull(texts.holderName.helperText)
        assertEquals(lookup(R.string.purse_card_cvv_helper), texts.cvv.helperText)
    }

    private fun lookup(id: Int) = "res#$id"
}
