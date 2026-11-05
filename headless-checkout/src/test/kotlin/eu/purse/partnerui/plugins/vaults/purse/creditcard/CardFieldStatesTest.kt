package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.partnerui.CardFormTexts
import eu.purse.securefields.events.FieldStatePayload
import eu.purse.securefields.model.VaultFieldName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardFieldStatesTest {

    private val texts = resolveCardTexts(
        CardFormTexts(
            cardNumber = CardFormTexts.FieldTexts(
                requiredError = "Card number required.",
                formatError = "Invalid card number.",
                helperText = "Only a token is stored.",
            ),
            cvv = CardFormTexts.FieldTexts(requiredError = "Security code required.", formatError = "Invalid security code."),
        ),
    ) { "default#$it" }

    private val states = CardFieldStates(texts)

    private fun payload(field: VaultFieldName, valid: Boolean? = null, length: Int? = null) = FieldStatePayload(
        fieldName = field.frameName,
        valid = valid,
        length = length,
        touched = null,
        error = if (valid == false && (length ?: 0) > 0) "format" else null,
    )

    @Test
    fun `a field nothing happened to has no state and no error`() {
        assertNull(states.current[CardField.CARD_NUMBER])
        assertNull(states.errorMessage(CardField.CARD_NUMBER))
    }

    @Test
    fun `change, focus and blur reduce into the field's state`() {
        states.onFocus(payload(VaultFieldName.CardNumber))
        states.onChange(payload(VaultFieldName.CardNumber, valid = false, length = 4))

        assertEquals(
            CardFieldState(isValid = false, isFocused = true, hasContent = true, wasBlurred = false),
            states.current[CardField.CARD_NUMBER],
        )

        states.onBlur(payload(VaultFieldName.CardNumber))
        states.onFocus(payload(VaultFieldName.CardNumber))
        states.onChange(payload(VaultFieldName.CardNumber, valid = true, length = 16))

        assertEquals(
            CardFieldState(isValid = true, isFocused = true, hasContent = true, wasBlurred = true),
            states.current[CardField.CARD_NUMBER],
        )
        assertNull(states.current[CardField.CVV])
    }

    @Test
    fun `no error while the shopper is typing in the field`() {
        states.onFocus(payload(VaultFieldName.CardNumber))
        states.onChange(payload(VaultFieldName.CardNumber, valid = false, length = 4))

        assertNull(states.errorMessage(CardField.CARD_NUMBER))
    }

    @Test
    fun `rejected content shows the format error once the field is left`() {
        states.onFocus(payload(VaultFieldName.CardNumber))
        states.onChange(payload(VaultFieldName.CardNumber, valid = false, length = 4))
        states.onBlur(payload(VaultFieldName.CardNumber))

        assertEquals("Invalid card number.", states.errorMessage(CardField.CARD_NUMBER))
    }

    @Test
    fun `the format error clears once the content is valid`() {
        states.onChange(payload(VaultFieldName.CardNumber, valid = false, length = 4))
        states.onBlur(payload(VaultFieldName.CardNumber))
        states.onChange(payload(VaultFieldName.CardNumber, valid = true, length = 16))

        assertNull(states.errorMessage(CardField.CARD_NUMBER))
    }

    @Test
    fun `an empty field shows the required error once left, not before`() {
        states.onFocus(payload(VaultFieldName.Cvv))
        assertNull(states.errorMessage(CardField.CVV))

        states.onBlur(payload(VaultFieldName.Cvv))
        assertEquals("Security code required.", states.errorMessage(CardField.CVV))
    }

    @Test
    fun `a field emptied after being left shows the required error`() {
        states.onChange(payload(VaultFieldName.Cvv, valid = true, length = 3))
        states.onBlur(payload(VaultFieldName.Cvv))
        states.onChange(payload(VaultFieldName.Cvv, valid = false, length = 0))

        assertEquals("Security code required.", states.errorMessage(CardField.CVV))
    }

    @Test
    fun `an unknown field name is ignored`() {
        states.onBlur(FieldStatePayload(fieldName = "iban", valid = null, length = null, touched = null))

        assertEquals(emptyMap<CardField, CardFieldState>(), states.current)
    }

    @Test
    fun `the helper text shows while focused, the error replaces it`() {
        val focused = CardFieldState(isFocused = true)
        assertEquals("Only a token is stored.", texts.cardNumber.message(focused))
        assertNull(texts.cardNumber.message(CardFieldState()))
        assertEquals("Invalid card number.", texts.cardNumber.message(CardFieldState(hasContent = true, wasBlurred = true)))
    }
}
