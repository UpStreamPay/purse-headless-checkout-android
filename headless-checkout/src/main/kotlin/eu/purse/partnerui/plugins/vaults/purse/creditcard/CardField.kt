// Web: packages/partner-ui/src/commons/Form/BaseInput.element.ts (one input per hosted field, named through `aria-labelledby`)
// Same: the four hosted fields; each secure input is named by its label; `final-form`'s required/format error rules.
// Differs: a stable `testTag` per field — iOS `CardField.accessibilityIdentifier`, same `purse_card_*` values;
//          per-field state and error published on `CardCoordinator`, as iOS does (`fieldStates`, `errorMessage(for:)`).
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import androidx.compose.runtime.mutableStateMapOf
import eu.purse.partnerui.CardFormTexts
import eu.purse.securefields.events.FieldStatePayload
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

/**
 * What the card form knows about one field — iOS `CardFieldState`. [wasBlurred] turns `true` the
 * first time the field loses focus and stays `true`.
 */
data class CardFieldState(
    /** SecureFields accepts the content. `false` for an empty field. */
    val isValid: Boolean = false,
    val isFocused: Boolean = false,
    val hasContent: Boolean = false,
    val wasBlurred: Boolean = false,
)

/**
 * The card form's per-field state, reduced from SecureFields' change / focus / blur events, and the
 * error each field shows. Owned by [CardCoordinator], so it tracks a form laid out with bare
 * `VaultField`s as well as [PurseCardForm].
 */
internal class CardFieldStates(private val texts: ResolvedCardTexts) {
    private val states = mutableStateMapOf<CardField, CardFieldState>()

    /** Compose state: reading it in a composable recomposes on every change. */
    val current: Map<CardField, CardFieldState> get() = states

    fun onChange(payload: FieldStatePayload) = update(payload) {
        // SecureFields reports `error = "format"` alongside `valid = false` for content it rejects.
        copy(isValid = payload.valid == true && payload.error == null, hasContent = (payload.length ?: 0) > 0)
    }

    fun onFocus(payload: FieldStatePayload) = update(payload) { copy(isFocused = true) }

    fun onBlur(payload: FieldStatePayload) = update(payload) { copy(isFocused = false, wasBlurred = true) }

    fun errorMessage(field: CardField): String? = texts[field].error(states[field] ?: CardFieldState())

    private fun update(payload: FieldStatePayload, transform: CardFieldState.() -> CardFieldState) {
        val field = VaultFieldName.fromFrameName(payload.fieldName)?.let(CardField::of) ?: return
        states[field] = (states[field] ?: CardFieldState()).transform()
    }
}

/**
 * The error to show under a field in [state], or `null` (web's `final-form` rules). Never while the
 * shopper is typing in it: a format error appears on blur and clears once the content is valid; a
 * required error appears once an empty field has been left.
 */
internal fun ResolvedFieldTexts.error(state: CardFieldState): String? = when {
    state.hasContent && !state.isValid && !state.isFocused -> formatError
    !state.hasContent && state.wasBlurred -> requiredError
    else -> null
}

/** What goes under the field: the error, else the helper text while the field has focus. */
internal fun ResolvedFieldTexts.message(state: CardFieldState): String? =
    error(state) ?: helperText?.takeIf { state.isFocused && it.isNotBlank() }
