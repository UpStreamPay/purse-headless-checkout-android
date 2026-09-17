package eu.purse.checkout.demo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Who the screen belongs to. The demo tells a two-actor story: the **setup** page is Purse's own
 * control panel (Purse teal), the **checkout** and **result** pages are the merchant's store
 * ([DemoStore]), in the store's colours, with the partners' branded controls (Google Pay, PayPal,
 * Klarna) untouched inside it. Switching brand between the two is what makes the point that the
 * merchant owns the screen and Purse only the engine.
 */
enum class DemoBrand { Purse, Merchant }

/**
 * The demo's Material 3 theme, one colour scheme per [DemoBrand]. Nothing overrides typography: the
 * screens use the stock M3 roles (`headlineMedium` for amounts, `titleMedium` for section heads,
 * `bodyLarge` for copy). Raw JSON dumps are the one exception, see [monoStyle].
 */
@Composable
fun DemoTheme(brand: DemoBrand, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when (brand) {
        DemoBrand.Purse -> if (dark) PurseDark else PurseLight
        DemoBrand.Merchant -> if (dark) MerchantDark else MerchantLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// Purse's own palette (purse.eu): teal 00CEAC on ink 080810, pink B3E4 as the soft accent.
private val PurseTeal = Color(0xFF00CEAC)
private val PurseInk = Color(0xFF080810)

private val PurseLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF007A66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F5EC),
    onPrimaryContainer = Color(0xFF00382E),
    secondary = Color(0xFF4E5A57),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F5EC),
    onSecondaryContainer = Color(0xFF00382E),
    tertiary = Color(0xFFB0508F),
    surface = Color(0xFFFCFDFC),
    onSurface = PurseInk,
    surfaceVariant = Color(0xFFE6EDEA),
    onSurfaceVariant = Color(0xFF454D4A),
    background = Color(0xFFF3F6F5),
    onBackground = PurseInk,
    outline = Color(0xFF7E8886),
)

private val PurseDark: ColorScheme = darkColorScheme(
    primary = PurseTeal,
    onPrimary = PurseInk,
    primaryContainer = Color(0xFF0F2E29),
    onPrimaryContainer = Color(0xFFC9F5EC),
    secondary = Color(0xFFB4BEBB),
    onSecondary = PurseInk,
    secondaryContainer = Color(0xFF0F2E29),
    onSecondaryContainer = Color(0xFFC9F5EC),
    tertiary = Color(0xFFFFB3E4),
    surface = Color(0xFF0F0F1A),
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF1E1E2A),
    onSurfaceVariant = Color(0xFFB4B4BF),
    background = PurseInk,
    onBackground = Color(0xFFEDEDF2),
    outline = Color(0xFF6E6E7A),
)

// The merchant: a denim store, so an indigo that is clearly not Purse's and not any partner's.
private val MerchantLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF223B7A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4F7),
    onPrimaryContainer = Color(0xFF12224A),
    secondary = Color(0xFF585E6E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE4F7),
    onSecondaryContainer = Color(0xFF12224A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181F),
    surfaceVariant = Color(0xFFE9EBF1),
    onSurfaceVariant = Color(0xFF474B57),
    background = Color(0xFFF4F5F8),
    onBackground = Color(0xFF16181F),
    outline = Color(0xFF858A98),
)

private val MerchantDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFB3C3FF),
    onPrimary = Color(0xFF0E1C45),
    primaryContainer = Color(0xFF1E2E5C),
    onPrimaryContainer = Color(0xFFDDE4F7),
    secondary = Color(0xFFBEC3D2),
    onSecondary = Color(0xFF16181F),
    secondaryContainer = Color(0xFF1E2E5C),
    onSecondaryContainer = Color(0xFFDDE4F7),
    surface = Color(0xFF15171E),
    onSurface = Color(0xFFE9EAEF),
    surfaceVariant = Color(0xFF262932),
    onSurfaceVariant = Color(0xFFBDC1CC),
    background = Color(0xFF0E0F14),
    onBackground = Color(0xFFE9EAEF),
    outline = Color(0xFF7B8090),
)

/**
 * The three payment outcomes need a colour each, and M3's scheme only ships one of them (`error`) —
 * so success and "unconfirmed" are defined here, per mode, rather than reused off unrelated roles
 * (`tertiary`) that carry no such meaning. See [ResultScreen].
 */
object DemoStatusColors {
    val success: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6DDBA0) else Color(0xFF0F7A4C)
    val onSuccess: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF003820) else Color.White
    val warning: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF3C167) else Color(0xFF8A5A00)
    val onWarning: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3D2A00) else Color.White
}

/** For `widget.data` and the raw alfred/session JSON — the only places a monospace block earns its
 * keep, and all of them sit behind a developer disclosure the shopper never has to open. */
val monoStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
