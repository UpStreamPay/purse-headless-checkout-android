// Web: packages/partner-ui/src/plugins/generics/hostedfields lit form (labels, tooltips, errors)
// Same: label above, help text under, error in place of help text.
// Differs: Compose around SecureFields' `VaultField`s.
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.purse.partnerui.CardFormTexts
import eu.purse.partnerui.PurseTheme
import eu.purse.partnerui.ThemeValues
import eu.purse.securefields.VaultField
import eu.purse.securefields.VaultFieldController

/**
 * The SDK's card form — SecureFields' secure inputs wrapped in the **label, help text and error
 * message** web's hosted form renders around each field (`commons/Form/BaseInput`), all styled from
 * the merchant's [PurseTheme] (`label` / `helperText` / `input` scopes, `:invalid` variants) with
 * web's defaults for anything not provided.
 *
 * Renders whatever [CardCoordinator.mode] calls for: the four fields of a fresh card, or the lone
 * CVV of a saved card that still needs one. Field copy comes from `PluginOptions.cardFormTexts`
 * ([CardFormTexts]), falling back to the SDK's `purse_card_*` resources in the checkout's
 * `PluginOptions.locale` (the device locale when it has none).
 *
 * Error semantics mirror web's `final-form` rules: a field the shopper left **empty after
 * touching it** shows its `requiredError`; content SecureFields rejects shows its `formatError`;
 * otherwise the field's `helperText` (if any) is shown while the field has focus. The state and
 * error it draws are [CardCoordinator.fieldStates] and [CardCoordinator.errorMessage], for a host
 * that lays the fields out itself.
 *
 * Each secure input carries its label as `contentDescription` and [CardField.testTag]
 * (`purse_card_number`, `purse_card_exp_date`, `purse_card_cvv`, `purse_card_holder_name`) as its
 * test tag.
 *
 * This is what `PaymentElementUi` renders for a card element. Call it directly only when you host
 * a [CardCoordinator] yourself; SecureFields' bare `VaultForm(coordinator.sdk)` remains available
 * when you want no labels or messages at all.
 */
@Composable
fun PurseCardForm(coordinator: CardCoordinator, modifier: Modifier = Modifier) {
    val theme = remember(coordinator) { coordinator.theme }
    val texts = remember(coordinator) { coordinator.texts }
    val states = coordinator.fieldStates

    val fields = when (coordinator.mode) {
        CardCoordinator.Mode.FRESH_CARD -> listOf(
            CardField.CARD_NUMBER to coordinator.sdk.cardNumber,
            CardField.EXP_DATE to coordinator.sdk.expDate,
            CardField.CVV to coordinator.sdk.cvv,
            CardField.HOLDER_NAME to coordinator.sdk.holderName,
        )
        CardCoordinator.Mode.TOKEN_CVV -> listOf(CardField.CVV to coordinator.sdk.cvv)
        CardCoordinator.Mode.TOKEN_NO_CVV -> emptyList()
    }
    if (fields.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(theme.gapDp())) {
        fields.forEach { (field, controller) ->
            ThemedField(
                field = field,
                controller = controller,
                state = states[field] ?: CardFieldState(),
                texts = texts[field],
                theme = theme,
            )
        }
    }
}

@Composable
private fun ThemedField(
    field: CardField,
    controller: VaultFieldController,
    state: CardFieldState,
    texts: ResolvedFieldTexts,
    theme: PurseTheme.Resolved,
) {
    val error = texts.error(state)
    val invalid = error != null
    Column(Modifier.fillMaxWidth()) {
        texts.label?.let { label ->
            BasicText(
                text = label,
                style = (if (invalid) theme.labelInvalid else theme.label).toTextStyle(),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        FieldFrame(theme, focused = state.isFocused, invalid = invalid) {
            // Named by its label through the input's own `contentDescription` (see
            // `vaultFieldConfig`); the tag is the stable UI-test handle.
            VaultField(controller, Modifier.fillMaxWidth().height(FIELD_HEIGHT_DP.dp).testTag(field.testTag))
        }
        val helper = texts.message(state)
        if (helper != null) {
            BasicText(
                text = helper,
                style = (if (invalid) theme.helperTextInvalid else theme.helperText).toTextStyle(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The box around a secure field — web's `BaseInput` border, radius and padding, which SecureFields'
 * bare `EditText` does not draw itself. The fill is the theme's `input.backgroundColor` (its `:focus`
 * variant while focused); the 1dp border is the input text colour at low alpha at rest, the label
 * colour while focused, and the `:invalid` helper colour when the field is in error, so the three
 * states read without the merchant declaring border colours the theme has no scope for.
 */
@Composable
private fun FieldFrame(
    theme: PurseTheme.Resolved,
    focused: Boolean,
    invalid: Boolean,
    content: @Composable () -> Unit,
) {
    val input = if (focused) theme.inputFocus else theme.input
    val fill = ThemeValues.colorArgb(input.backgroundColor)?.let { Color(it.toInt()) } ?: Color.Transparent
    val ink = ThemeValues.colorArgb(input.color)?.let { Color(it.toInt()) } ?: Color.Black
    val border = when {
        invalid -> ThemeValues.colorArgb(theme.helperTextInvalid.color)?.let { Color(it.toInt()) } ?: ink
        focused -> ThemeValues.colorArgb(theme.label.color)?.let { Color(it.toInt()) } ?: ink
        else -> ink.copy(alpha = REST_BORDER_ALPHA)
    }
    val shape = RoundedCornerShape(FIELD_RADIUS_DP.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .border(if (focused || invalid) 2.dp else 1.dp, border, shape)
            .padding(horizontal = FIELD_PADDING_DP.dp),
    ) {
        content()
    }
}

private const val FIELD_HEIGHT_DP = 52
private const val FIELD_RADIUS_DP = 12
private const val FIELD_PADDING_DP = 12
private const val REST_BORDER_ALPHA = 0.22f

/** [CardFormTexts.FieldTexts] with every `null` filled from the SDK's localized resources. */
internal data class ResolvedFieldTexts(
    val label: String?,
    val placeholder: String,
    val helperText: String?,
    val requiredError: String,
    val formatError: String,
)

/** All four fields' copy, resolved once per coordinator (see [rememberCardCoordinator]). */
internal data class ResolvedCardTexts(
    val cardNumber: ResolvedFieldTexts,
    val expDate: ResolvedFieldTexts,
    val cvv: ResolvedFieldTexts,
    val holderName: ResolvedFieldTexts,
) {
    operator fun get(field: CardField): ResolvedFieldTexts = when (field) {
        CardField.CARD_NUMBER -> cardNumber
        CardField.EXP_DATE -> expDate
        CardField.CVV -> cvv
        CardField.HOLDER_NAME -> holderName
    }
}

// --- theme → Compose -----------------------------------------------------------------------------

internal fun PurseTheme.Text.toTextStyle(): TextStyle = TextStyle(
    color = ThemeValues.colorArgb(color)?.let { Color(it.toInt()) } ?: Color.Unspecified,
    fontSize = ThemeValues.sizeSp(fontSize)?.sp ?: 14.sp,
    fontWeight = ThemeValues.fontWeight(fontWeight)?.let { FontWeight(it) } ?: FontWeight.Normal,
    fontFamily = when (ThemeValues.fontFamily(fontFamily)) {
        ThemeValues.Family.SANS_SERIF -> FontFamily.SansSerif
        ThemeValues.Family.SERIF -> FontFamily.Serif
        ThemeValues.Family.MONOSPACE -> FontFamily.Monospace
        ThemeValues.Family.CURSIVE -> FontFamily.Cursive
        null -> FontFamily.Default
    },
)

internal fun PurseTheme.Resolved.gapDp() = (ThemeValues.sizeSp(gap) ?: 8f).dp
