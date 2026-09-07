// Web: packages/partner-ui/src/plugins/partners/paypal/wallet (JS SDK button)
// Same: branded button.
// Differs: `com.paypal.android:payment-buttons` in an `AndroidView`.
package eu.purse.partnerui.plugins.partners.paypal.wallet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.paypal.android.paymentbuttons.PayPalButton

/**
 * The SDK-rendered PayPal method — PayPal's own branded `PayPalButton` (brand-guideline compliant,
 * from `com.paypal.android:payment-buttons`) wired to [PaypalCoordinator.submit]. Renders nothing
 * until [PaypalCoordinator.prepare] ran, and from the tap on everything is SDK-driven: `/init` →
 * PayPal approval → the approval commits the method as the split's primary → `/validate` —
 * outcomes arrive on the coordinator's listener.
 */
@Composable
fun PaypalButton(
    coordinator: PaypalCoordinator,
    modifier: Modifier = Modifier,
) {
    if (!coordinator.isReady) return
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PayPalButton(context).apply {
                setOnClickListener { coordinator.submit() }
            }
        },
    )
}
