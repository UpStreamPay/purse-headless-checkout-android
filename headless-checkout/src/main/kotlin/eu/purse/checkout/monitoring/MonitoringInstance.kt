// Web: `window.__PURSE_INSTANCE_ID__` (one id per page load)
// Same: one id stamped on every monitoring message.
// Differs: one id per checkout, saved with the checkout's state so a restore continues the instance.
package eu.purse.checkout.monitoring

import java.util.UUID

/**
 * What identifies one checkout in Purse's monitoring: its [id] (the envelope's `instanceId`) and
 * whether this object was rebuilt by `restore` ([restored] — `HEADLESS_CHECKOUT_CREATE` says so).
 */
internal class MonitoringInstance(
    val id: String = UUID.randomUUID().toString(),
    val restored: Boolean = false,
)
