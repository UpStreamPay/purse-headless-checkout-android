package eu.purse.checkout.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The frame all three screens share: a fixed header, a scrolling body, and an optional action pinned
 * to the bottom. Insets are handled once here (`safeDrawing` + `imePadding`, so a focused text field
 * lifts the whole frame above the keyboard) rather than per screen.
 */
@Composable
fun DemoScreen(
    step: Int,
    title: String,
    onBack: (() -> Unit)? = null,
    bottomAction: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            ScreenHeader(step, title, onBack)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
                Spacer(Modifier.height(8.dp))
            }
            bottomAction?.let {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { it() }
            }
        }
    }
}

@Composable
private fun ScreenHeader(step: Int, title: String, onBack: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Text(
                text = "←",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 16.dp),
            )
        }
        Column {
            Text(
                text = stringResource(R.string.step_label, step),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/**
 * The demo's one card shape — sections, method rows and result panels are all built from this.
 * [border] marks a selected payment method: selection is shown with an outline rather than a tinted
 * background, because a primary's body hosts a partner's *own* UI (Klarna's widget, SecureFields'
 * card form) whose colours this demo doesn't control and can't safely put on a coloured surface.
 */
@Composable
fun DemoCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color),
        border = border,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** Section heading — one line, `titleMedium`, no explanatory paragraph underneath. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

/**
 * A collapsed-by-default section. Everything that used to be permanently on screen as small
 * monospace text (`widget.data`, the session dump, raw alfred responses) lives behind one of these.
 */
@Composable
fun Disclosure(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

/**
 * A raw JSON / `widget.data` dump. Selectable (long-press to copy an id out of it) and horizontally
 * scrollable so a long line never forces the whole screen sideways; vertical growth is left to the
 * screen's own scroll.
 */
@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        SelectionContainer { Text(text = text, style = monoStyle) }
    }
}

/** A `label: value` line — the label small and muted, the value readable. */
@Composable
fun LabeledValue(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = 0.75f),
        )
        SelectionContainer {
            Text(
                text = value,
                style = if (mono) monoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize) else MaterialTheme.typography.bodyLarge,
                fontFamily = if (mono) FontFamily.Monospace else null,
            )
        }
    }
}

/** The one full-width call to action per screen — tall enough to hit without aiming, with its own
 * in-place spinner so a network step never leaves the screen looking idle. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.size(12.dp))
        }
        Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Secondary, outlined action (check balance, capture, get transactions). */
@Composable
fun SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Anything that went wrong, in the same shape wherever it appears. */
@Composable
fun ErrorCard(message: String) {
    DemoCard(color = MaterialTheme.colorScheme.errorContainer) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * "480.00 EUR". The SDK keeps amounts as [BigDecimal] with the currency beside them, but their scale
 * varies by where they came from (a session's own `480.0` vs the splitter's `480.00`) — padding to two
 * decimals is what stops the same amount reading two different ways on the same screen. A scale above
 * two is left alone rather than rounded away.
 */
fun amountText(amount: BigDecimal, currency: String): String {
    val scaled = if (amount.scale() < 2) amount.setScale(2, RoundingMode.HALF_UP) else amount
    return "${scaled.toPlainString()} $currency"
}

/** The scrolling record of every coordinator callback — the old always-visible output dump, now
 * opt-in. Kept because it's the fastest way to read a full `KlarnaPaymentsSDKError` detail dump. */
@Composable
fun EventLog(lines: List<String>) {
    if (lines.isEmpty()) return
    Disclosure(title = stringResource(R.string.section_event_log)) {
        MonoBlock(lines.joinToString("\n"))
    }
}
