// Web: packages/partner-ui hosted-form default wording, picked from its translation table by `Options.locale`
// Same: the checkout's locale picks the language; merchant `CardFormTexts` first, SDK defaults after.
// Differs: the defaults are the `purse_card_*` resources (`values/`, `values-fr/`), read through a
//          Configuration carrying the checkout locale — the device locale only when the checkout has none.
package eu.purse.partnerui.plugins.vaults.purse.creditcard

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import eu.purse.checkout.R
import eu.purse.partnerui.CardFormTexts
import eu.purse.partnerui.PluginOptions
import java.util.Locale

/**
 * [CardFormTexts] over the SDK's `purse_card_*` resources, read in the checkout's
 * [PluginOptions.locale] — iOS `CardFormCopy.resolved(locale:)`. A device in French paying through
 * an `en-GB` checkout gets English copy; a checkout with no locale follows the device.
 */
@Composable
internal fun rememberCardTexts(texts: CardFormTexts, locale: String?): ResolvedCardTexts {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = remember(context, configuration, locale) {
        val device = configuration.locales[0] ?: Locale.getDefault()
        val target = cardFormLocale(locale, device)
        if (target == device) {
            context.resources
        } else {
            context.createConfigurationContext(Configuration(configuration).apply { setLocale(target) }).resources
        }
    }
    return remember(resources, texts) { resolveCardTexts(texts) { resources.getString(it) } }
}

/**
 * The locale the default copy is read in: [checkoutLocale] (BCP-47, `_` tolerated) when it names a
 * language, else [device].
 */
internal fun cardFormLocale(checkoutLocale: String?, device: Locale): Locale =
    checkoutLocale?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { Locale.forLanguageTag(it.replace('_', '-')) }
        ?.takeIf { it.language.isNotEmpty() }
        ?: device

/** Every `null` in [texts] filled from [string] (a resource lookup) — the merchant's value first. */
internal fun resolveCardTexts(texts: CardFormTexts, string: (Int) -> String): ResolvedCardTexts {
    fun field(t: CardFormTexts.FieldTexts, label: Int, placeholder: Int, helper: Int, required: Int, invalid: Int) =
        ResolvedFieldTexts(
            label = t.label ?: string(label),
            placeholder = t.placeholder ?: string(placeholder),
            helperText = t.helperText ?: string(helper).ifBlank { null },
            requiredError = t.requiredError ?: string(required),
            formatError = t.formatError ?: string(invalid),
        )
    return ResolvedCardTexts(
        cardNumber = field(
            texts.cardNumber, R.string.purse_card_number_label, R.string.purse_card_number_placeholder,
            R.string.purse_card_number_helper, R.string.purse_card_number_required, R.string.purse_card_number_invalid,
        ),
        expDate = field(
            texts.expDate, R.string.purse_card_exp_date_label, R.string.purse_card_exp_date_placeholder,
            R.string.purse_card_exp_date_helper, R.string.purse_card_exp_date_required, R.string.purse_card_exp_date_invalid,
        ),
        cvv = field(
            texts.cvv, R.string.purse_card_cvv_label, R.string.purse_card_cvv_placeholder,
            R.string.purse_card_cvv_helper, R.string.purse_card_cvv_required, R.string.purse_card_cvv_invalid,
        ),
        holderName = field(
            texts.holderName, R.string.purse_card_holder_name_label, R.string.purse_card_holder_name_placeholder,
            R.string.purse_card_holder_name_helper, R.string.purse_card_holder_name_required,
            R.string.purse_card_holder_name_invalid,
        ),
    )
}
