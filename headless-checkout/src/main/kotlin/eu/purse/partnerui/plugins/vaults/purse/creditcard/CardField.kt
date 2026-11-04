// Web: packages/partner-ui/src/commons/Form/BaseInput.element.ts (one input per hosted field, named through `aria-labelledby`)
// Same: the four hosted fields; each secure input is named by its label.
// Differs: a stable `testTag` per field — iOS `CardField.accessibilityIdentifier`, same `purse_card_*` values.
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.partnerui.CardFormTexts
import eu.purse.securefields.model.VaultFieldName

/** One field of the card form, named as in [CardFormTexts]. */
enum class CardField(
    /**
     * The stable UI-test handle [PurseCardForm] sets on the field (`Modifier.testTag`) — the values
     * iOS uses as `accessibilityIdentifier`. For UiAutomator / Appium, expose test tags as resource
     * ids with `Modifier.semantics { testTagsAsResourceId = true }` on an ancestor.
     */
    val testTag: String,
) {
    CARD_NUMBER("purse_card_number"),
    EXP_DATE("purse_card_exp_date"),
    CVV("purse_card_cvv"),
    HOLDER_NAME("purse_card_holder_name"),
    ;

    internal companion object {
        /** SecureFields' field names → ours: its `CardNumber` is our [CARD_NUMBER], and so on. */
        fun of(name: VaultFieldName): CardField = when (name) {
            VaultFieldName.CardNumber -> CARD_NUMBER
            VaultFieldName.ExpDate -> EXP_DATE
            VaultFieldName.Cvv -> CVV
            VaultFieldName.HolderName -> HOLDER_NAME
        }
    }
}
