package eu.purse.partnerui.plugins.vaults.purse.creditcard

import eu.purse.partnerui.PurseTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [toVaultStyles] — the resolved theme handed to SecureFields, whose `Color.parseColor` paints
 * opaque black on any colour it cannot read (SDK-12578).
 */
class CardVaultStylesTest {

    @Test
    fun `default theme sends a transparent background parseColor can read`() {
        val styles = PurseTheme().resolve().toVaultStyles()
        assertEquals("#00000000", styles.backgroundColor)
        assertEquals("#FF1F2933", styles.color)
        assertEquals("#FF616E7C", styles.placeholderColor)
    }

    @Test
    fun `every variant's colours are normalised too`() {
        val styles = PurseTheme(
            inputFocus = PurseTheme.FieldStyle(color = "#00f", backgroundColor = "#ffffff80"),
        ).resolve().toVaultStyles()
        assertEquals("#FF0000FF", styles.focus?.color)
        assertEquals("#80FFFFFF", styles.focus?.backgroundColor)
        assertEquals("#00000000", styles.invalid?.backgroundColor)
        assertEquals("#FFBA2525", styles.invalid?.color)
        assertEquals("#FF0F8613", styles.valid?.color)
    }

    @Test
    fun `a colour only parseColor knows is forwarded as is`() {
        val styles = PurseTheme(input = PurseTheme.FieldStyle(backgroundColor = "white")).resolve().toVaultStyles()
        assertEquals("white", styles.backgroundColor)
    }

    @Test
    fun `non-colour keys travel unchanged`() {
        val styles = PurseTheme().resolve().toVaultStyles()
        assertEquals("14px", styles.fontSize)
        assertEquals("400", styles.fontWeight)
        assertEquals("sans-serif", styles.fontFamily)
    }
}
