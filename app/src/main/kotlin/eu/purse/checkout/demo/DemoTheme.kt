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

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4B3FD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E0FF),
    onPrimaryContainer = Color(0xFF190066),
    secondary = Color(0xFF5B5D72),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE5E1EC),
    background = Color(0xFFF7F5FB),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC2C0FF),
    onPrimary = Color(0xFF23008F),
    primaryContainer = Color(0xFF3A2DB0),
    onPrimaryContainer = Color(0xFFE3E0FF),
    secondary = Color(0xFFC4C5DD),
    surface = Color(0xFF131317),
    surfaceVariant = Color(0xFF474551),
    background = Color(0xFF0E0E12),
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
