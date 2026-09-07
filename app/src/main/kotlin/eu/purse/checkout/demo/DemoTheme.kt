package eu.purse.checkout.demo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * The demo's Material 3 theme. Deliberately plain: the stock M3 type scale is what fixes the old
 * layout's "everything is 12–13sp" problem, so nothing here overrides typography — screens just use
 * [MaterialTheme.typography] roles (`headlineMedium` for amounts, `titleMedium` for section heads,
 * `bodyLarge` for copy) instead of hand-picked sizes. Raw JSON dumps are the one exception, see
 * [monoStyle].
 */
@Composable
fun DemoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}

// Greyscale on purpose: the demo must not compete with the partners' own branded controls (Google
// Pay, PayPal, Klarna) or suggest a Purse colour to a merchant. Only the outcome colours below carry hue.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F1F1F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color(0xFF1F1F1F),
    secondary = Color(0xFF5F5F5F),
    onSecondary = Color.White,
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF4A4A4A),
    background = Color(0xFFF4F4F4),
    onBackground = Color(0xFF1A1A1A),
    outline = Color(0xFF8A8A8A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE6E6E6),
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = Color(0xFFB8B8B8),
    onSecondary = Color(0xFF1A1A1A),
    surface = Color(0xFF161616),
    onSurface = Color(0xFFEAEAEA),
    surfaceVariant = Color(0xFF2E2E2E),
    onSurfaceVariant = Color(0xFFBDBDBD),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFEAEAEA),
    outline = Color(0xFF7A7A7A),
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
 * keep, and all of them sit behind an expander the shopper never has to open. */
val monoStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
