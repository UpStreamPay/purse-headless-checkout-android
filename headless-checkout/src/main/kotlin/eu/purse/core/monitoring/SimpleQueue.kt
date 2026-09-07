// Web: packages/common queue behind the event bus
// Same: batch and flush.
// Differs: nothing.
package eu.purse.core.monitoring

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A batching queue — the Kotlin port of web common's `SimpleQueue` (`SimpleQueue.ts`), used to
 * batch monitoring events toward the logging API:
 *
 * - flushes as soon as [batchSize] items are queued;
 * - otherwise flushes [flushAfterMs] after the first queued item (one timer at a time);
 * - flushes early when the queued payload would exceed [maxBytes];
 * - [flush] drains everything now (web's `purge`), for teardown / app-background.
 *
 * Processor errors are swallowed per batch (a failed log batch must never break a payment). The
 * timer runs on a shared daemon thread; [processor] must itself be non-blocking (the monitoring
 * client posts from its own worker).
 */
class SimpleQueue<T>(
    private val batchSize: Int,
    @Volatile var processor: (List<T>) -> Unit,
    private val flushAfterMs: Long = 10_000,
    private val maxBytes: Int? = null,
    private val byteSizer: (T) -> Int = { it.toString().toByteArray().size },
) {
    init {
        require(batchSize > 0) { "SimpleQueue batch size must be positive" }
    }

    private val items = ArrayDeque<T>()
    private var queuedBytes = 0
    private var pendingFlush: ScheduledFuture<*>? = null

    @Synchronized
    fun add(item: T) {
        val size = runCatching { byteSizer(item) }.getOrDefault(0)
        if (maxBytes != null && queuedBytes + size > maxBytes) flushLocked()
        items.addLast(item)
        queuedBytes += size
        if (items.size >= batchSize) {
            flushLocked()
            return
        }
        if (pendingFlush == null) {
            pendingFlush = SCHEDULER.schedule({ flush() }, flushAfterMs, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun flush() = flushLocked()

    @get:Synchronized
    val currentQueueSize: Int get() = items.size

    private fun flushLocked() {
        pendingFlush?.cancel(false)
        pendingFlush = null
        while (items.isNotEmpty()) {
            val batch = ArrayList<T>(minOf(batchSize, items.size))
            repeat(minOf(batchSize, items.size)) { batch.add(items.removeFirst()) }
            runCatching { processor(batch) }
        }
        queuedBytes = 0
    }

    private companion object {
        val SCHEDULER = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "purse-monitoring-queue").apply { isDaemon = true }
        }
    }
}
