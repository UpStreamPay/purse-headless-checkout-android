// Web: none — web navigates the page
// Same: —
// Differs: the exported entry point of the deep-link return; forwards only the URI, never extras.
package eu.purse.partnerui.redirect

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the `${applicationId}.redirect` deep link and hands it to [RedirectionCheckoutActivity],
 * which is not exported: another app can reach this Activity, but nothing it puts in the intent's
 * extras gets past it — only the VIEW action and the URI are forwarded, so it can never make the
 * WebView load a page or HTML of its choosing.
 */
internal class RedirectReturnActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // CLEAR_TOP + SINGLE_TOP: delivered to the running redirect's onNewIntent, which still checks
        // the action and this app's scheme.
        startActivity(
            Intent(this, RedirectionCheckoutActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(intent.data)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
