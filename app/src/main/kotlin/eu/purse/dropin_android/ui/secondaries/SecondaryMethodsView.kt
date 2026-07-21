package eu.purse.dropin_android.ui.secondaries

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import eu.purse.dropin.DropinAndroid
import eu.purse.dropin.payment.method.DisabledReason
import eu.purse.dropin.payment.method.DropinPaymentMethod
import eu.purse.dropin.payment.method.secondary.SecondaryConfig
import eu.purse.dropin_android.R
import eu.purse.dropin_android.databinding.ItemSecondaryAccordionBinding
import eu.purse.dropin_android.ui.components.afterTextChanged
import java.math.BigDecimal

/**
 * Renders the *secondary* (co-payment) methods, mirroring web dropin's `ui/secondaries`: each one is
 * a collapsible accordion with an inline gift-card entry form.
 *
 * The form's client-side validation (PAN / CVV rules) is driven by the SDK's [SecondaryConfig];
 * tokenizing an added card goes through [DropinAndroid.getSecondaryToken] (Payment API `/init`). A
 * successfully tokenized card's balance is reported through [onSecondaryApplied] so the host
 * Activity can fold it into its [eu.purse.dropin.payment.split.PaymentSplitter].
 */
internal class SecondaryMethodsView(
    private val activity: AppCompatActivity,
    private val onSecondaryApplied: (BigDecimal) -> Unit,
) {

    // Per-method hooks to re-apply a recomputed disabled state in place — the mirror of the primary
    // side. When the split fulfils the order, the engine marks the remaining secondaries
    // `PaymentFulfilled` (nothing left to cover), so we grey the accordion and block adding more.
    private val disabledUpdaters = linkedMapOf<String, (DisabledReason?) -> Unit>()

    /** Clear the per-method hooks before a fresh render pass. */
    fun reset() = disabledUpdaters.clear()

    /**
     * Re-apply the disabled state carried by each of [methods] to its rendered accordion — called
     * with `dropin.methodsForPrimaryReach(remaining)` after every applied secondary.
     */
    fun applyDisabledStates(methods: List<DropinPaymentMethod>) {
        methods.forEach { disabledUpdaters[it.id]?.invoke(it.disabled) }
    }

    fun accordion(
        method: DropinPaymentMethod,
        parent: ViewGroup,
        dropin: DropinAndroid,
    ): View {
        val item = ItemSecondaryAccordionBinding.inflate(activity.layoutInflater, parent, false)
        val partnerLabel = method.partner.wireValue.replaceFirstChar { it.uppercase() }
        item.secondaryTitle.text = activity.getString(R.string.secondary_giftcard_title, partnerLabel)
        item.secondaryContent.isVisible = false
        item.secondaryCaret.text = CARET_COLLAPSED
        item.secondaryHeader.setOnClickListener {
            val open = !item.secondaryContent.isVisible
            item.secondaryContent.isVisible = open
            item.secondaryCaret.text = if (open) CARET_EXPANDED else CARET_COLLAPSED
        }

        val config = SecondaryConfig.forMethod(method)
        if (config == null) {
            // No client-side config for this partner in the POC — show the header only. Still track
            // its disabled state so a fulfilled split can grey it out alongside the rest.
            item.secondaryForm.isVisible = false
            disabledUpdaters[method.id] = { disabled -> item.root.alpha = if (disabled != null) 0.6f else 1f }
            return item.root
        }

        fun pan() = item.giftcardPanInput.text?.toString().orEmpty().trim()
        fun cvv() = item.giftcardCvvInput.text?.toString().orEmpty().trim()

        // Set by [applyDisabledStates] once the split changes; while non-null this accordion can't
        // add another card (typically `PaymentFulfilled` — the order is already covered).
        var disabledReason: DisabledReason? = method.disabled

        fun refresh() {
            val withCvv = config.requiresCvv(pan())
            item.giftcardCvvLayout.isVisible = withCvv
            item.useCardButton.isEnabled = disabledReason == null &&
                config.validatePan(pan()) && (!withCvv || config.validateCvv(cvv()))
            item.giftcardPanLayout.error = null
        }

        val watcher = afterTextChanged { refresh() }
        item.giftcardPanInput.addTextChangedListener(watcher)
        item.giftcardCvvInput.addTextChangedListener(watcher)

        // A fulfilled split greys this accordion, collapses it, and blocks adding another card.
        disabledUpdaters[method.id] = { disabled ->
            disabledReason = disabled
            item.root.alpha = if (disabled != null) 0.6f else 1f
            if (disabled != null) {
                item.secondaryContent.isVisible = false
                item.secondaryCaret.text = CARET_COLLAPSED
            }
            refresh()
        }
        refresh()

        item.useCardButton.setOnClickListener {
            val pan = pan()
            val cvv = cvv()
            val withCvv = config.requiresCvv(pan)
            if (!config.validatePan(pan) || (withCvv && !config.validateCvv(cvv))) {
                item.giftcardPanLayout.error = activity.getString(R.string.giftcard_invalid)
                return@setOnClickListener
            }
            item.giftcardPanLayout.error = null
            item.useCardButton.isEnabled = false
            item.secondaryStatus.isVisible = true
            item.secondaryStatus.text = activity.getString(R.string.giftcard_applying)
            // Blocking /init call — off the main thread (see DropinAndroid.getSecondaryToken).
            Thread {
                try {
                    val token = dropin.getSecondaryToken(method, pan, cvv.ifEmpty { null })
                    activity.runOnUiThread {
                        val label = token.name.ifBlank { "•••• " + pan.takeLast(4) }
                        val balance = "${token.balance.toPlainString()} ${dropin.session.currency}"
                        item.secondaryStatus.text = activity.getString(
                            R.string.giftcard_added,
                            label,
                            balance,
                            token.expirationDate.ifBlank { "—" },
                        )
                        item.giftcardPanInput.text = null
                        item.giftcardCvvInput.text = null
                        refresh()
                        onSecondaryApplied(token.balance)
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        item.secondaryStatus.text = activity.getString(R.string.giftcard_error, e.message)
                        refresh()
                    }
                }
            }.start()
        }
        return item.root
    }

    companion object {
        private const val CARET_COLLAPSED = "▸"
        private const val CARET_EXPANDED = "▾"
    }
}
