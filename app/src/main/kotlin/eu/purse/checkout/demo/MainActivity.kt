package eu.purse.checkout.demo

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * The demo's single Activity: three screens (setup → checkout → result, see [DemoStep]) rendered in
 * Compose off one [DemoController].
 *
 * A single `singleTask` Activity on purpose: Klarna's `KlarnaPaymentView` needs a deep link that
 * resumes the **same instance** hosting the live widget (the `*.klarna://return` intent-filter in
 * the manifest). `AppCompatActivity` because the partner SDKs inflate Views that resolve
 * AppCompat/Material theme attributes off this Activity's theme.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = DemoController(this)
        setContent { DemoApp(controller) }
    }
}
