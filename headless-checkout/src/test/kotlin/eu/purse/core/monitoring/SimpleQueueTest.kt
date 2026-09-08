package eu.purse.core.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleQueueTest {

    @Test
    fun `flushes as soon as the batch size is reached`() {
        val batches = mutableListOf<List<String>>()
        val queue = SimpleQueue<String>(batchSize = 3, processor = { batches.add(it) }, flushAfterMs = 60_000)

        queue.add("a")
        queue.add("b")
        assertTrue(batches.isEmpty())
        queue.add("c")

        assertEquals(listOf(listOf("a", "b", "c")), batches)
        assertEquals(0, queue.currentQueueSize)
    }

    @Test
    fun `flush drains everything in batch-sized chunks`() {
        val batches = mutableListOf<List<String>>()
        val queue = SimpleQueue<String>(batchSize = 2, processor = { batches.add(it) }, flushAfterMs = 60_000)

        queue.add("a")
        // batchSize 2 → "a" alone stays queued until an explicit flush.
        assertEquals(1, queue.currentQueueSize)
        queue.flush()

        assertEquals(listOf(listOf("a")), batches)
    }

    @Test
    fun `the timer flushes a partial batch`() {
        val batches = mutableListOf<List<String>>()
        val queue = SimpleQueue<String>(batchSize = 16, processor = { batches.add(it) }, flushAfterMs = 50)

        queue.add("a")
        val deadline = System.currentTimeMillis() + 5_000
        while (batches.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals(listOf(listOf("a")), batches)
    }

    @Test
    fun `an oversized payload flushes early`() {
        val batches = mutableListOf<List<String>>()
        val queue = SimpleQueue<String>(
            batchSize = 16,
            processor = { batches.add(it) },
            flushAfterMs = 60_000,
            maxBytes = 10,
        )

        queue.add("aaaaaaaa") // 8 bytes queued
        queue.add("bbbbbbbb") // would exceed 10 — the first item flushes first
        assertEquals(listOf(listOf("aaaaaaaa")), batches)
    }

    @Test
    fun `a throwing processor never breaks the queue`() {
        var calls = 0
        val queue = SimpleQueue<String>(
            batchSize = 1,
            processor = {
                calls++
                throw IllegalStateException("boom")
            },
            flushAfterMs = 60_000,
        )

        queue.add("a")
        queue.add("b")

        assertEquals(2, calls)
        assertEquals(0, queue.currentQueueSize)
    }
}
