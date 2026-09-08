// Web: packages/sdk API fetch wrapper (PARTNER_API_CALL_START/END events)
// Same: one start and one end event per call.
// Differs: a process-wide listener the checkout installs.
package eu.purse.core.monitoring

/**
 * Process-wide seam for timing Payment API calls — how `:headless-checkout`'s analytics observe
 * `:core`'s transport without a reverse dependency. Web times the same boundary via its
 * monitoring proxy (`PARTNER_API_CALL_START`/`_END` with `durationMs` + `success`).
 */
object ApiCallMonitor {

    interface Listener {
        fun onCallStart(method: String, path: String)
        fun onCallEnd(method: String, path: String, durationMs: Long, success: Boolean)
    }

    @Volatile
    var listener: Listener? = null

    internal inline fun <T> time(method: String, path: String, call: () -> T): T {
        val current = listener ?: return call()
        runCatching { current.onCallStart(method, path) }
        val startedAt = System.nanoTime()
        var success = false
        try {
            val result = call()
            success = true
            return result
        } finally {
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            runCatching { current.onCallEnd(method, path, durationMs, success) }
        }
    }
}
