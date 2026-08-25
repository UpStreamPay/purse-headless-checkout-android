package eu.purse.checkout.monitoring

import eu.purse.core.monitoring.SimpleQueue
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/** Event severity — web common's `EventType` (`EventBus.ts`). */
enum class EventLevel { DEBUG, INFO, ERROR, SUCCESS, WARNING, PERFORMANCE, UI, NETWORK }

/**
 * One analytics event — web common's `Event`: code + level + free-form payload, stamped with the
 * client's ISO datetime (with UTC offset) at construction.
 */
class CheckoutMonitoringEvent(
    val code: CheckoutEventCode,
    val type: EventLevel = EventLevel.INFO,
    val payload: JSONObject? = null,
) {
    val dateString: String = OffsetDateTime.now().toString()

    fun toJson(): JSONObject = JSONObject()
        .put("code", code.name)
        .put("type", type.name)
        .put("dateString", dateString)
        .apply { payload?.let { put("payload", it) } }
}

/**
 * The singleton analytics bus — the Kotlin analog of web's `PurseHeadlessCheckoutEventBus`
 * (`monitoring/checkout-event-bus.ts`). Every [post] fans out to subscribers and lands in a
 * batching [SimpleQueue] (16 events / 10s / 64KB, matching web) toward the monitoring API.
 *
 * Merchant-facing [subscribe] defaults to the public scope — the same six codes web exposes to the
 * `headlessCheckout` scope; the full stream ([Scope.ALL]) is for the SDK's own diagnostics.
 */
class CheckoutEventBus private constructor() {

    enum class Scope { PUBLIC, ALL }

    private val queue = SimpleQueue<CheckoutMonitoringEvent>(
        batchSize = 16,
        processor = { },
        flushAfterMs = 10_000,
        maxBytes = 64_000,
        byteSizer = { it.toJson().toString().toByteArray().size },
    )

    private val listeners = CopyOnWriteArrayList<Pair<Scope, (CheckoutMonitoringEvent) -> Unit>>()

    /** Route flushed batches somewhere (the `MonitoringApiClient`) — web's `getInstance(sendLogs)`. */
    fun setLogProcessor(processLog: (List<CheckoutMonitoringEvent>) -> Unit) {
        queue.processor = processLog
    }

    fun post(
        code: CheckoutEventCode,
        type: EventLevel = EventLevel.INFO,
        payload: JSONObject? = null,
    ) {
        val event = CheckoutMonitoringEvent(code, type, payload)
        listeners.forEach { (scope, listener) ->
            if (scope == Scope.ALL || event.code in PUBLIC_CODES) {
                runCatching { listener(event) }
            }
        }
        queue.add(event)
    }

    /** Subscribe to events; returns the unsubscriber. [Scope.PUBLIC] mirrors web's public scope. */
    fun subscribe(scope: Scope = Scope.PUBLIC, listener: (CheckoutMonitoringEvent) -> Unit): () -> Unit {
        val entry = scope to listener
        listeners.add(entry)
        return { listeners.remove(entry) }
    }

    /** Drain the batch queue now — web's `purge()`, for teardown / app-background. */
    fun flush() = queue.flush()

    companion object {
        /** The codes web exposes to external subscribers (`EventScopes.headlessCheckout`). */
        val PUBLIC_CODES: Set<CheckoutEventCode> = setOf(
            CheckoutEventCode.HEADLESS_CHECKOUT_READY,
            CheckoutEventCode.HEADLESS_CHECKOUT_EXPIRED_SESSION,
            CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_FULFILLED,
            CheckoutEventCode.HEADLESS_CHECKOUT_OTHER_ERROR,
            CheckoutEventCode.HEADLESS_CHECKOUT_CLIENT_HOOK_ERROR,
            CheckoutEventCode.HEADLESS_CHECKOUT_PAYMENT_ERROR,
        )

        @Volatile
        private var instance: CheckoutEventBus? = null

        fun getInstance(): CheckoutEventBus =
            instance ?: synchronized(this) { instance ?: CheckoutEventBus().also { instance = it } }
    }
}
