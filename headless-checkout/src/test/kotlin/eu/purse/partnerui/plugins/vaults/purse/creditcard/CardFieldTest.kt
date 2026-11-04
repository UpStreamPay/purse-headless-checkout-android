package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.securefields.model.VaultFieldName
import org.junit.Assert.assertEquals
import org.junit.Test

class CardFieldTest {

    @Test
    fun `test tags are the purse_card ids iOS uses as accessibility identifiers`() {
        assertEquals(
            listOf("purse_card_number", "purse_card_exp_date", "purse_card_cvv", "purse_card_holder_name"),
            CardField.entries.map { it.testTag },
        )
    }

    @Test
    fun `every SecureFields field maps to its own card field`() {
        assertEquals(
            mapOf(
                VaultFieldName.CardNumber to CardField.CARD_NUMBER,
                VaultFieldName.ExpDate to CardField.EXP_DATE,
                VaultFieldName.Cvv to CardField.CVV,
                VaultFieldName.HolderName to CardField.HOLDER_NAME,
            ),
            VaultFieldName.entries.associateWith { CardField.of(it) },
        )
    }
}
