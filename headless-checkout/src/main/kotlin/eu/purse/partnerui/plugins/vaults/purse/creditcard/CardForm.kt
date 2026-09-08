// Web: packages/partner-ui/src/plugins/generics/hostedfields lit form (labels, tooltips, errors)
// Same: label above, help text under, error in place of help text.
// Differs: Compose around SecureFields' `VaultField`s.
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.purse.partnerui.CardFormTexts
import eu.purse.partnerui.PurseTheme
import eu.purse.partnerui.ThemeValues
import eu.purse.securefields.SecureFieldsListener
import eu.purse.securefields.VaultField
import eu.purse.securefields.VaultFieldController
import eu.purse.securefields.events.FieldStatePayload
import eu.purse.securefields.model.VaultFieldName

/**
 * The SDK's card form — SecureFields' secure inputs wrapped in the **label, help text and error
 * message** web's hosted form renders around each field (`commons/Form/BaseInput`), all styled from
 * the merchant's [PurseTheme] (`label` / `helperText` / `input` scopes, `:invalid` variants) with
 * web's defaults for anything not provided.
 *
 * Renders whatever [CardCoordinator.mode] calls for: the four fields of a fresh card, or the lone
 * CVV of a saved card that still needs one. Field copy comes from `PluginOptions.cardFormTexts`
 * ([CardFormTexts]), falling back to the SDK's localized `purse_card_*` resources.
 *
 * Error semantics mirror web's `final-form` rules: a field the shopper left **empty after
 * touching it** shows its `requiredError`; content SecureFields rejects shows its `formatError`;
 * otherwise the field's `helperText` (if any) is shown while the field has focus.
 *
 * This is what `PaymentElementUi` renders for a card element. Call it directly only when you host
 * a [CardCoordinator] yourself; SecureFields' bare `VaultForm(coordinator.sdk)` remains available
 * when you want no labels or messages at all.
 */
@Composable
fun PurseCardForm(coordinator: CardCoordinator, modifier: Modifier = Modifier) {
    val theme = remember(coordinator) { coordinator.theme }
    val texts = remember(coordinator) { coordinator.texts }
    val states = remember(coordinator.sdk) { mutableStateMapOf<VaultFieldName, FieldUiState>() }

    DisposableEffect(coordinator.sdk) {
        val unregister = coordinator.sdk.addListener(object : SecureFieldsListener {
            override fun onChange(payload: FieldStatePayload) = states.update(payload) {
                copy(valid = payload.valid, length = payload.length ?: 0, error = payload.error)
            }
            override fun onFocus(payload: FieldStatePayload) = states.update(payload) { copy(focused = true, touched = true) }
            override fun onBlur(payload: FieldStatePayload) = states.update(payload) { copy(focused = false, blurred = true) }
        })
        onDispose { unregister() }
    }

    val fields = when (coordinator.mode) {
        CardCoordinator.Mode.FRESH_CARD -> listOf(
            VaultFieldName.CardNumber to coordinator.sdk.cardNumber,
            VaultFieldName.ExpDate to coordinator.sdk.expDate,
            VaultFieldName.Cvv to coordinator.sdk.cvv,
            VaultFieldName.HolderName to coordinator.sdk.holderName,
        )
        CardCoordinator.Mode.TOKEN_CVV -> listOf(VaultFieldName.Cvv to coordinator.sdk.cvv)
        CardCoordinator.Mode.TOKEN_NO_CVV -> emptyList()
    }
    if (fields.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(theme.gapDp())) {
        fields.forEach { (name, controller) ->
            ThemedField(
                controller = controller,
                state = states[name] ?: FieldUiState(),
                texts = texts.resolved(name),
                theme = theme,
            )
        }
    }
}

@Composable
private fun ThemedField(
    controller: VaultFieldController,
    state: FieldUiState,
    texts: ResolvedFieldTexts,
    theme: PurseTheme.Resolved,
) {
    val message = state.message(texts)
    val invalid = message != null
    Column(Modifier.fillMaxWidth()) {
        texts.label?.let { label ->
            BasicText(
                text = label,
                style = (if (invalid) theme.labelInvalid else theme.label).toTextStyle(),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        VaultField(controller, Modifier.fillMaxWidth().height(48.dp))
        val helper = message ?: texts.helperText?.takeIf { state.focused && it.isNotBlank() }
        if (helper != null) {
            BasicText(
                text = helper,
                style = (if (invalid) theme.helperTextInvalid else theme.helperText).toTextStyle(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Per-field UI state, reduced from SecureFields' change/focus/blur events. */
internal data class FieldUiState(
    val valid: Boolean? = null,
    val length: Int = 0,
    val error: String? = null,
    val focused: Boolean = false,
    val touched: Boolean = false,
    val blurred: Boolean = false,
) {
    /** The error to show, or `null` when the field is fine (web's required/format rules). */
    internal fun message(texts: ResolvedFieldTexts): String? = when {
        // SecureFields reports `error = "format"` for non-empty content it rejects.
        length > 0 && valid == false && !focused -> texts.formatError
        length > 0 && error != null && !focused -> texts.formatError
        // An empty field the shopper visited and left.
        length == 0 && blurred -> texts.requiredError
        else -> null
    }
}

private fun MutableMap<VaultFieldName, FieldUiState>.update(
    payload: FieldStatePayload,
    transform: FieldUiState.() -> FieldUiState,
) {
    val field = VaultFieldName.fromFrameName(payload.fieldName) ?: return
    this[field] = (this[field] ?: FieldUiState()).transform()
}

/** [CardFormTexts.FieldTexts] with every `null` filled from the SDK's localized resources. */
internal data class ResolvedFieldTexts(
    val label: String?,
    val placeholder: String,
    val helperText: String?,
    val requiredError: String,
    val formatError: String,
)

private fun ResolvedCardTexts.resolved(name: VaultFieldName): ResolvedFieldTexts = when (name) {
    VaultFieldName.CardNumber -> cardNumber
    VaultFieldName.ExpDate -> expDate
    VaultFieldName.Cvv -> cvv
    VaultFieldName.HolderName -> holderName
}

/** All four fields' copy, resolved once per coordinator (see [rememberCardCoordinator]). */
internal data class ResolvedCardTexts(
    val cardNumber: ResolvedFieldTexts,
    val expDate: ResolvedFieldTexts,
    val cvv: ResolvedFieldTexts,
    val holderName: ResolvedFieldTexts,
)

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
