// Web: packages/partner-ui/src/commons/PartnerUI/theming
// Same: scopes global / input / label / helperText with :focus :invalid :valid, `Theme.build()` fallback chain.
// Differs: values reach SecureFields' `VaultStyles` and `PurseCardForm`.
package eu.purse.partnerui

/**
 * Styling for the surfaces the SDK renders itself — the Android analog of web partner-ui's
 * `PartnerUITheme` (`commons/PartnerUI/theming/types.ts`), with the same **scopes** and the same
 * **fallback chain**.
 *
 * Scopes (web's `THEME_SCOPES`): [global] is the base every other scope falls back to; [input]
 * styles the secure card fields (SecureFields' `VaultStyles`); [label] the field labels;
 * [helperText] the help text under a field **and the error message** that replaces it when the
 * field is invalid. Web's `tooltip` scope has no Android surface and is not modelled.
 *
 * Variants (web's pseudo-classes) are separate properties: `inputFocus` (`:focus`), `inputInvalid`
 * / `labelInvalid` / `helperTextInvalid` / `globalInvalid` (`:invalid`), `inputValid` /
 * `labelValid` / `globalValid` (`:valid`).
 *
 * Every value is optional. [resolve] merges the merchant's theme over [Default] (web's
 * `Theme.defaultTheme`, same colours) and applies web's per-key fallback order:
 * **scope variant → scope → global variant → global**. So a merchant who only sets
 * `global = Text(color = "#123456")` recolours labels, helper texts and inputs at once, while
 * `helperTextInvalid = Text(color = "#B3261E")` only changes the error message colour.
 *
 * Values are CSS-like strings, exactly as web and SecureFields take them: colours `#rgb`,
 * `#rrggbb`, `#rrggbbaa` or `transparent`; sizes `"14px"` / `"14sp"` / `"14"` (all read as sp);
 * weights `"400"`, `"700"`, `"bold"`, `"normal"`; families `sans-serif`, `serif`, `monospace`
 * (anything else falls back to the platform default).
 */
data class PurseTheme(
    /** Base values every scope falls back to (web `global`): colour, font, and the `gap` between fields. */
    val global: Text? = null,
    val globalInvalid: Text? = null,
    val globalValid: Text? = null,
    /** The secure card fields themselves (web `input`) — mapped onto SecureFields' `VaultStyles`. */
    val input: FieldStyle? = null,
    val inputFocus: FieldStyle? = null,
    val inputInvalid: FieldStyle? = null,
    val inputValid: FieldStyle? = null,
    /** Field labels rendered above each secure field (web `label`). */
    val label: Text? = null,
    val labelInvalid: Text? = null,
    val labelValid: Text? = null,
    /** Help text under a field, and the error message shown in its place (web `helperText`). */
    val helperText: Text? = null,
    val helperTextInvalid: Text? = null,
    /** Spacing between fields (web `global.gap`), e.g. `"8px"`. */
    val gap: String? = null,
) {
    /** Typography for a text scope (web `ThemeScope<'label' | 'helperText' | 'global'>`). */
    data class Text(
        val color: String? = null,
        val fontFamily: String? = null,
        val fontSize: String? = null,
        val fontWeight: String? = null,
    )

    /** Styling for a secure input field, shaped to what SecureFields' `VaultStyles` can apply. */
    data class FieldStyle(
        val color: String? = null,
        val placeholderColor: String? = null,
        val backgroundColor: String? = null,
        val fontFamily: String? = null,
        val fontSize: String? = null,
        val lineHeight: String? = null,
        val fontWeight: String? = null,
    )

    /**
     * Every scope fully resolved — no `null` left for the keys the default theme defines. This is
     * what the SDK's own composables and the SecureFields style mapping consume.
     */
    data class Resolved(
        val input: FieldStyle,
        val inputFocus: FieldStyle,
        val inputInvalid: FieldStyle,
        val inputValid: FieldStyle,
        val label: Text,
        val labelInvalid: Text,
        val labelValid: Text,
        val helperText: Text,
        val helperTextInvalid: Text,
        val gap: String,
    )

    /**
     * Merge this theme over [Default] and apply the fallback chain — web's `Theme.build()`:
     * a key is read from the scope variant, then the scope, then the global variant, then global.
     */
    fun resolve(): Resolved {
        val merged = mergeOver(Default)
        val global = merged.global ?: Text()
        val globalInvalid = merged.globalInvalid ?: Text()
        val globalValid = merged.globalValid ?: Text()

        fun text(scope: Text?, variant: Text? = null, globalVariant: Text? = null): Text = Text(
            color = variant?.color ?: scope?.color ?: globalVariant?.color ?: global.color,
            fontFamily = variant?.fontFamily ?: scope?.fontFamily ?: globalVariant?.fontFamily ?: global.fontFamily,
            fontSize = variant?.fontSize ?: scope?.fontSize ?: globalVariant?.fontSize ?: global.fontSize,
            fontWeight = variant?.fontWeight ?: scope?.fontWeight ?: globalVariant?.fontWeight ?: global.fontWeight,
        )

        fun field(scope: FieldStyle?, variant: FieldStyle? = null, globalVariant: Text? = null): FieldStyle = FieldStyle(
            color = variant?.color ?: scope?.color ?: globalVariant?.color ?: global.color,
            placeholderColor = variant?.placeholderColor ?: scope?.placeholderColor,
            backgroundColor = variant?.backgroundColor ?: scope?.backgroundColor,
            fontFamily = variant?.fontFamily ?: scope?.fontFamily ?: globalVariant?.fontFamily ?: global.fontFamily,
            fontSize = variant?.fontSize ?: scope?.fontSize ?: globalVariant?.fontSize ?: global.fontSize,
            lineHeight = variant?.lineHeight ?: scope?.lineHeight,
            fontWeight = variant?.fontWeight ?: scope?.fontWeight ?: globalVariant?.fontWeight ?: global.fontWeight,
        )

        return Resolved(
            input = field(merged.input),
            inputFocus = field(merged.input, merged.inputFocus),
            inputInvalid = field(merged.input, merged.inputInvalid, globalInvalid),
            inputValid = field(merged.input, merged.inputValid, globalValid),
            label = text(merged.label),
            labelInvalid = text(merged.label, merged.labelInvalid, globalInvalid),
            labelValid = text(merged.label, merged.labelValid, globalValid),
            helperText = text(merged.helperText),
            helperTextInvalid = text(merged.helperText, merged.helperTextInvalid, globalInvalid),
            gap = merged.gap ?: Default.gap ?: "8px",
        )
    }

    /** Key-by-key deep merge — `this` wins wherever it sets a value, [base] fills the rest. */
    internal fun mergeOver(base: PurseTheme): PurseTheme = PurseTheme(
        global = global.over(base.global),
        globalInvalid = globalInvalid.over(base.globalInvalid),
        globalValid = globalValid.over(base.globalValid),
        input = input.over(base.input),
        inputFocus = inputFocus.over(base.inputFocus),
        inputInvalid = inputInvalid.over(base.inputInvalid),
        inputValid = inputValid.over(base.inputValid),
        label = label.over(base.label),
        labelInvalid = labelInvalid.over(base.labelInvalid),
        labelValid = labelValid.over(base.labelValid),
        helperText = helperText.over(base.helperText),
        helperTextInvalid = helperTextInvalid.over(base.helperTextInvalid),
        gap = gap ?: base.gap,
    )

    companion object {
        /**
         * Web's `Theme.defaultTheme` (`commons/PartnerUI/theming/index.ts`), trimmed to the keys
         * Android can apply: `#1f2933` text, `#ba2525` invalid, `#0f8613` valid, `#616e7c`
         * placeholders, 14px / 400 / sans-serif, 8px gap.
         */
        val Default: PurseTheme = PurseTheme(
            global = Text(color = "#1f2933", fontFamily = "sans-serif", fontSize = "14px", fontWeight = "400"),
            globalInvalid = Text(color = "#ba2525"),
            globalValid = Text(color = "#0f8613"),
            input = FieldStyle(placeholderColor = "#616e7c", backgroundColor = "transparent"),
            helperText = Text(fontSize = "12px"),
            gap = "8px",
        )

        private fun Text?.over(base: Text?): Text? = when {
            this == null -> base
            base == null -> this
            else -> Text(
                color = color ?: base.color,
                fontFamily = fontFamily ?: base.fontFamily,
                fontSize = fontSize ?: base.fontSize,
                fontWeight = fontWeight ?: base.fontWeight,
            )
        }

        private fun FieldStyle?.over(base: FieldStyle?): FieldStyle? = when {
            this == null -> base
            base == null -> this
            else -> FieldStyle(
                color = color ?: base.color,
                placeholderColor = placeholderColor ?: base.placeholderColor,
                backgroundColor = backgroundColor ?: base.backgroundColor,
                fontFamily = fontFamily ?: base.fontFamily,
                fontSize = fontSize ?: base.fontSize,
                lineHeight = lineHeight ?: base.lineHeight,
                fontWeight = fontWeight ?: base.fontWeight,
            )
        }
    }
}

/**
 * Readers for the CSS-like strings a [PurseTheme] carries — pure Kotlin so the theme can be
 * unit-tested without Compose; the card form maps the results onto Compose types.
 */
object ThemeValues {

    /**
     * `#rgb`, `#rrggbb`, `#rrggbbaa` (CSS order) or `transparent` → packed ARGB, or `null` when
     * unreadable (the caller keeps its default).
     */
    fun colorArgb(value: String?): Long? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v == "transparent") return 0x00000000L
        if (!v.startsWith("#")) return null
        val hex = v.drop(1)
        if (!hex.all { it.isDigit() || it in 'a'..'f' }) return null
        return when (hex.length) {
            3 -> {
                val r = hex[0].toString().repeat(2); val g = hex[1].toString().repeat(2); val b = hex[2].toString().repeat(2)
                ("ff$r$g$b").toLong(16)
            }
            6 -> ("ff$hex").toLong(16)
            8 -> {
                // CSS #rrggbbaa → Android/Compose ARGB
                val rgb = hex.take(6); val a = hex.drop(6)
                ("$a$rgb").toLong(16)
            }
            else -> null
        }
    }

    /** `"14px"`, `"14sp"`, `"14dp"`, `"14"` → 14f (all read as scale-independent points), else `null`. */
    fun sizeSp(value: String?): Float? {
        val v = value?.trim()?.lowercase() ?: return null
        val number = v.removeSuffix("px").removeSuffix("sp").removeSuffix("dp").removeSuffix("pt").trim()
        return number.toFloatOrNull()?.takeIf { it > 0f }
    }

    /** `"400"`, `"700"`, `"bold"`, `"normal"`, `"medium"`, … → a 100–900 weight, else `null`. */
    fun fontWeight(value: String?): Int? {
        val v = value?.trim()?.lowercase() ?: return null
        return when (v) {
            "thin" -> 100
            "extralight", "extra-light", "ultralight" -> 200
            "light" -> 300
            "normal", "regular" -> 400
            "medium" -> 500
            "semibold", "semi-bold", "demibold" -> 600
            "bold" -> 700
            "extrabold", "extra-bold", "ultrabold" -> 800
            "black", "heavy" -> 900
            else -> v.toIntOrNull()?.takeIf { it in 100..900 }
        }
    }

    /** Generic families → a stable token the Compose side maps; unknown names → `null` (platform default). */
    fun fontFamily(value: String?): Family? {
        val first = value?.split(',')?.firstOrNull()?.trim()?.trim('"', '\'')?.lowercase() ?: return null
        return when (first) {
            "sans-serif", "system-ui", "arial", "helvetica", "roboto" -> Family.SANS_SERIF
            "serif", "georgia", "times new roman" -> Family.SERIF
            "monospace", "courier new", "menlo", "consolas" -> Family.MONOSPACE
            "cursive" -> Family.CURSIVE
            else -> null
        }
    }

    enum class Family { SANS_SERIF, SERIF, MONOSPACE, CURSIVE }
}
