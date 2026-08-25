package eu.purse.core.datetime

import eu.purse.core.api.PaymentApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ServerDatetimeCheckerTest {

    @Test
    fun `an unsynced checker falls back to the local clock`() {
        val localNow = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val checker = ServerDatetimeChecker(fetchServerTime = { throw PaymentApiException("down") }, clock = { localNow })
        checker.sync()

        assertFalse(checker.synced)
        assertTrue(checker.isExpiredForServer(Instant.parse("2026-01-01T11:59:59Z")))
        assertFalse(checker.isExpiredForServer(Instant.parse("2026-01-01T12:00:01Z")))
    }

    @Test
    fun `a device clock ahead of the server does not expire a still-valid session`() {
        // Device thinks it's 12:10, server says 12:00 — offset +10min.
        val localNow = Instant.parse("2026-01-01T12:10:00Z").toEpochMilli()
        val checker = ServerDatetimeChecker(
            fetchServerTime = { Instant.parse("2026-01-01T12:00:00Z") },
            clock = { localNow },
        )
        checker.sync()

        assertTrue(checker.synced)
        // Expires 12:05 server time: local clock alone would call this expired; the offset saves it.
        assertFalse(checker.isExpiredForServer(Instant.parse("2026-01-01T12:05:00Z")))
        assertTrue(checker.isExpiredForServer(Instant.parse("2026-01-01T11:55:00Z")))
    }

    @Test
    fun `a device clock behind the server expires a session the local clock would keep alive`() {
        // Device thinks it's 11:50, server says 12:00 — offset -10min.
        val localNow = Instant.parse("2026-01-01T11:50:00Z").toEpochMilli()
        val checker = ServerDatetimeChecker(
            fetchServerTime = { Instant.parse("2026-01-01T12:00:00Z") },
            clock = { localNow },
        )
        checker.sync()

        assertTrue(checker.isExpiredForServer(Instant.parse("2026-01-01T11:55:00Z")))
    }

    @Test
    fun `sync is one-shot - a second call does not refetch`() {
        var fetches = 0
        val checker = ServerDatetimeChecker(
            fetchServerTime = { fetches++; Instant.now() },
        )
        checker.sync()
        checker.sync()

        assertEquals(1, fetches)
    }
}
