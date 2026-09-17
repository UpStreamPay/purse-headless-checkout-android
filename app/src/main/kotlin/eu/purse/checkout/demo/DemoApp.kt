package eu.purse.checkout.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The three screens, one at a time, each in its owner's colours ([DemoBrand]: Purse for the setup
 * page, the merchant for the checkout and the result), plus the snackbar the transient notices go to
 * (a canceled payment, a redirect being launched, a coordinator error that isn't a payment failure —
 * see `DemoController.fail`). Terminal outcomes don't come through here: they get the result screen.
 */
@Composable
fun DemoApp(controller: DemoController) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(controller.snackbar) {
        val message = controller.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        controller.snackbar = null
    }

    BackHandler(enabled = controller.step != DemoStep.Setup) { controller.back() }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = controller.step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "step",
        ) { step ->
            val brand = if (step == DemoStep.Setup) DemoBrand.Purse else DemoBrand.Merchant
            DemoTheme(brand) {
                when (step) {
                    DemoStep.Setup -> SetupScreen(controller)
                    DemoStep.Checkout -> CheckoutScreen(controller)
                    DemoStep.Result -> ResultScreen(controller)
                }
            }
        }
        DemoTheme(if (controller.step == DemoStep.Setup) DemoBrand.Purse else DemoBrand.Merchant) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}
