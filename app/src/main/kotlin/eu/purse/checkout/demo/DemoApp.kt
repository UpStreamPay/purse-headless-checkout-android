package eu.purse.checkout.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
 * The three screens, one at a time, plus the snackbar the transient notices go to (a canceled
 * payment, a redirect being launched, a coordinator error that isn't a payment failure — see
 * `DemoController.fail`). Terminal outcomes don't come through here: they get screen 3 to themselves.
 */
@Composable
fun DemoApp(controller: DemoController) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(controller.snackbar) {
        val message = controller.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        controller.snackbar = null
    }

    BackHandler(enabled = controller.step != DemoStep.Session) { controller.back() }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = controller.step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "step",
        ) { step ->
            when (step) {
                DemoStep.Session -> SessionScreen(controller)
                DemoStep.Pay -> PayScreen(controller)
                DemoStep.Result -> ResultScreen(controller)
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}
