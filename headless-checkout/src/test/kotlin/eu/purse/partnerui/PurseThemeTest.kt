package eu.purse.partnerui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PurseTheme.resolve] — web's `Theme.build()` fallback chain (scope variant → scope → global
 * variant → global) over web's default theme — and the CSS-value readers the card form relies on.
 */
class PurseThemeTest {

    @Test
    fun `empty theme resolves to web defaults everywhere`() {
        val r = PurseTheme().resolve()
        assertEquals("#1f2933", r.label.color)
        assertEquals("#1f2933", r.helperText.color)
        assertEquals("#1f2933", r.input.color)
        assertEquals("14px", r.label.fontSize)
        assertEquals("12px", r.helperText.fontSize)
        assertEquals("#616e7c", r.input.placeholderColor)
        assertEquals("transparent", r.input.backgroundColor)
        assertEquals("8px", r.gap)
    }

    @Test
    fun `invalid variants fall back to the global invalid colour`() {
        val r = PurseTheme().resolve()
        assertEquals("#ba2525", r.labelInvalid.color)
        assertEquals("#ba2525", r.helperTextInvalid.color)
        assertEquals("#ba2525", r.inputInvalid.color)
        assertEquals("#0f8613", r.labelValid.color)
        assertEquals("#0f8613", r.inputValid.color)
        // Non-colour keys still come from the scope / global, not from the variant.
        assertEquals("12px", r.helperTextInvalid.fontSize)
        assertEquals("14px", r.inputInvalid.fontSize)
    }

    @Test
    fun `a global override recolours every scope that does not set its own`() {
        val r = PurseTheme(global = PurseTheme.Text(color = "#123456", fontFamily = "serif")).resolve()
        assertEquals("#123456", r.label.color)
        assertEquals("#123456", r.helperText.color)
        assertEquals("#123456", r.input.color)
        assertEquals("serif", r.input.fontFamily)
        // Defaults the merchant did not touch survive the merge.
        assertEquals("14px", r.label.fontSize)
        assertEquals("#ba2525", r.labelInvalid.color)
    }

    @Test
    fun `a scope variant wins over the scope, which wins over global`() {
        val r = PurseTheme(
            global = PurseTheme.Text(color = "#000000"),
            helperText = PurseTheme.Text(color = "#111111", fontSize = "11px"),
            helperTextInvalid = PurseTheme.Text(color = "#B3261E"),
            input = PurseTheme.FieldStyle(color = "#222222"),
            inputFocus = PurseTheme.FieldStyle(color = "#0B57D0"),
        ).resolve()
        assertEquals("#111111", r.helperText.color)
        assertEquals("#B3261E", r.helperTextInvalid.color)
        assertEquals("11px", r.helperTextInvalid.fontSize) // inherited from the scope
        assertEquals("#000000", r.label.color) // no label scope → global
        assertEquals("#222222", r.input.color)
        assertEquals("#0B57D0", r.inputFocus.color)
        // No inputValid variant → the scope's own colour, ahead of the global :valid green.
        assertEquals("#222222", r.inputValid.color)
    }

    @Test
    fun `merge is key by key, not scope by scope`() {
        val merged = PurseTheme(input = PurseTheme.FieldStyle(fontSize = "16px")).mergeOver(PurseTheme.Default)
        assertEquals("16px", merged.input?.fontSize)
        assertEquals("#616e7c", merged.input?.placeholderColor) // default key kept alongside the override
    }

    @Test
    fun `colour reader handles css forms`() {
        assertEquals(0xFF1F2933L, ThemeValues.colorArgb("#1f2933"))
        assertEquals(0xFFFFFFFFL, ThemeValues.colorArgb("#fff"))
        assertEquals(0x80112233L, ThemeValues.colorArgb("#11223380")) // #rrggbbaa → aarrggbb
        assertEquals(0x00000000L, ThemeValues.colorArgb("transparent"))
        assertNull(ThemeValues.colorArgb("red"))
        assertNull(ThemeValues.colorArgb("#12"))
        assertNull(ThemeValues.colorArgb(null))
    }

    @Test
    fun `size and weight readers`() {
        assertEquals(14f, ThemeValues.sizeSp("14px"))
        assertEquals(16f, ThemeValues.sizeSp("16sp"))
        assertEquals(12f, ThemeValues.sizeSp("12"))
        assertNull(ThemeValues.sizeSp("large"))
        assertEquals(400, ThemeValues.fontWeight("400"))
        assertEquals(700, ThemeValues.fontWeight("bold"))
        assertEquals(500, ThemeValues.fontWeight("medium"))
        assertNull(ThemeValues.fontWeight("1000"))
        assertEquals(ThemeValues.Family.SANS_SERIF, ThemeValues.fontFamily("Arial, sans-serif"))
        assertEquals(ThemeValues.Family.MONOSPACE, ThemeValues.fontFamily("monospace"))
        assertNull(ThemeValues.fontFamily("Comic Sans MS"))
    }
}
