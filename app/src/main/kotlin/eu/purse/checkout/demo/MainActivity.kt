package eu.purse.checkout.demo

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * The demo's single Activity: three screens (session → pay → result, see [DemoStep]) rendered in
 * Compose off one [DemoController].
 *
 * It stays a single Activity on purpose. Klarna's `KlarnaPaymentView` needs a deep link that resumes
 * the **same instance** hosting the live widget (hence `singleTask` plus the `*.klarna://return`
 * intent-filter in the manifest), and both `GooglePayCoordinator` and `KlarnaCoordinator` register
 * `ActivityResult` launchers, which must happen before the Activity is STARTED — so [DemoController],
 * which builds them, is constructed here in `onCreate` and holds the state all three screens read.
 *
 * `AppCompatActivity` rather than a bare `ComponentActivity`: the partner SDKs inflate their own
 * Views (Klarna's widget, SecureFields' form), which resolve AppCompat/Material theme attributes off
 * this Activity's theme.
 *
 * No plugin registration call — `PluginRegistry` auto-registers every built-in plugin, and the
 * session alone decides what's actually offered.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = DemoController(this)
        setContent {
            DemoTheme { DemoApp(controller) }
        }
    }
}
