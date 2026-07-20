package eu.purse.dropin_android.ui.components

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import eu.purse.dropin_android.R

/**
 * Generic, payment-agnostic view helpers shared by the Drop-in UI — the Android-Views equivalent
 * of web dropin's `ui/components` design-system layer. Nothing here knows about payment methods;
 * the `ui.primaries` / `ui.secondaries` renderers compose these into method-specific UI.
 */

/** [value] dp in px, for this context's density. */
internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Full-width [LinearLayout.LayoutParams] with vertical margins (px). */
internal fun Context.marginParams(
    top: Int = 0,
    bottom: Int = 0,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
        setMargins(0, top, 0, bottom)
    }

/** A section heading like "Payment method". */
internal fun Context.sectionTitle(text: String): View {
    val title = TextView(this)
    title.text = text
    title.setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
    title.layoutParams = marginParams(top = dp(16), bottom = dp(4))
    return title
}

/** Full-width hairline — used both between sections and between rows in a grouped list. */
internal fun Context.dividerLine(top: Int = dp(12), bottom: Int = dp(4)): View {
    val line = View(this)
    line.setBackgroundColor(getColor(R.color.divider))
    line.layoutParams = marginParams(top = top, bottom = bottom, height = dp(1))
    return line
}

/** Centered "or" between the xpay buttons and the remaining primaries. */
internal fun Context.orDivider(): View {
    val divider = TextView(this)
    divider.setText(R.string.divider_or)
    divider.gravity = Gravity.CENTER
    divider.textSize = 12f
    divider.layoutParams = marginParams(top = dp(8), bottom = dp(4))
    return divider
}

/** Placeholder shown when a session exposes no methods this POC can render. */
internal fun Context.emptyMethodsView(): View {
    val view = TextView(this)
    view.setText(R.string.method_no_methods)
    view.setPadding(0, dp(16), 0, 0)
    return view
}

/**
 * An empty, bordered [MaterialCardView] wrapping a vertical [LinearLayout] — the grouped-list
 * container the primaries render into. The list is the card's first (only) child.
 */
internal fun Context.listCard(): MaterialCardView {
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    val card = MaterialCardView(this)
    card.radius = dp(8).toFloat()
    card.cardElevation = 0f
    card.strokeWidth = dp(1)
    card.setStrokeColor(getColor(R.color.divider))
    card.layoutParams = marginParams(top = dp(8))
    card.addView(list)
    return card
}

/** A [TextWatcher] that only reacts to the final text — runs [action] after each change. */
internal fun afterTextChanged(action: () -> Unit): TextWatcher = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) = action()
}
