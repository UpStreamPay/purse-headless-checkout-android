// Web: packages/partner-ui/src/plugins/generics/googlepay (pay.js `createButton`)
// Same: official button, shown once `isReadyToPay`.
// Differs: Play Services `PayButton` in an `AndroidView`.
package eu.purse.partnerui.plugins.generics.googlepay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.wallet.button.ButtonConstants
import com.google.android.gms.wallet.button.ButtonOptions
import com.google.android.gms.wallet.button.PayButton

/**
 * The SDK-rendered Google Pay method — the Android analog of web's `getPaymentElement` for
 * `googlepay`, whose element *is* the official button with the whole flow wired inside. Renders
 * Google's own `PayButton` (brand-guideline compliant, showing the shopper's card art from the
 * prepared request's `allowedPaymentMethods`) and wires its tap to [GooglePayCoordinator.launchSheet].
 *
 * Renders **nothing** until [GooglePayCoordinator.prepare] has reported ready — exactly when web
 * would show the element — so a merchant places it unconditionally:
 *
 * ```kotlin
 * // onCreate: val googlePay = GooglePayCoordinator(this, listener)
 * // session ready: googlePay.prepare(checkout.googlePayHost(item))
 * GooglePayButton(googlePay)   // that's the whole integration
 * ```
 *
 * From the tap on, everything is SDK-driven: sheet → approval commits the method as the split's
 * primary → `/validate` → 3-D Secure if any — outcomes arrive on the coordinator's listener.
 *
 * @param theme [ButtonConstants.ButtonTheme] — `DARK` (default) or `LIGHT`.
 * @param type [ButtonConstants.ButtonType] — `PAY` (default), `BUY`, `CHECKOUT`, `ORDER`, …
 * @param cornerRadius the button's corner radius.
 */
@Composable
fun GooglePayButton(
    coordinator: GooglePayCoordinator,
    modifier: Modifier = Modifier,
    theme: Int = ButtonConstants.ButtonTheme.DARK,
    type: Int = ButtonConstants.ButtonType.PAY,
    cornerRadius: Dp = 100.dp,
) {
    val allowedPaymentMethods = coordinator.allowedPaymentMethodsJson
    if (!coordinator.isReady || allowedPaymentMethods == null) return

    val cornerRadiusPx = with(LocalDensity.current) { cornerRadius.roundToPx() }
    // PayButton.initialize is one-shot — key the view on its configuration so a config change
    // rebuilds the button instead of re-initializing a live one.
    key(allowedPaymentMethods, theme, type, cornerRadiusPx) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                PayButton(context).apply {
                    initialize(
                        ButtonOptions.newBuilder()
                            .setButtonTheme(theme)
                            .setButtonType(type)
                            .setCornerRadius(cornerRadiusPx)
                            .setAllowedPaymentMethods(allowedPaymentMethods)
                            .build(),
                    )
                    setOnClickListener { coordinator.launchSheet() }
                }
            },
        )
    }
}
