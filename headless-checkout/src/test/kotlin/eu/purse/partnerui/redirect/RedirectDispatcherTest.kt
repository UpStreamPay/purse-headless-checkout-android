package eu.purse.partnerui.redirect

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one "offer to the merchant, else launch" step every coordinator runs a resolved redirect
 * through — pins the hook contract: handled → no WebView; declined → WebView; threw → nothing.
 */
class RedirectDispatcherTest {

    private val action = RedirectAction.Get("https://acs.example/challenge", returnUrlPrefix = "https://shop.example/return")

    private fun delegate(answer: suspend (RedirectAction) -> Boolean) = object : RedirectionDelegate {
        override suspend fun delegateRedirection(action: RedirectAction) = answer(action)
    }

    @Test
    fun `a host without a hook declines, so the SDK launches its own redirect`() = runBlocking {
        var launched: RedirectAction? = null

        val outcome = RedirectDispatcher.dispatch(action, object : RedirectionDelegate {}) { launched = it }

        assertEquals(RedirectDispatcher.Outcome.Launched, outcome)
        assertSame(action, launched)
    }

    @Test
    fun `a merchant that handles the redirect keeps the SDK WebView closed`() = runBlocking {
        var launched: RedirectAction? = null
        var offered: RedirectAction? = null

        val outcome = RedirectDispatcher.dispatch(
            action,
            delegate {
                offered = it
                true
            }
        ) { launched = it }

        assertEquals(RedirectDispatcher.Outcome.Delegated, outcome)
        assertSame(action, offered)
        assertNull(launched)
    }

    @Test
    fun `a merchant that declines hands the very same action to the SDK launcher`() = runBlocking {
        var launched: RedirectAction? = null

        val outcome = RedirectDispatcher.dispatch(action, delegate { false }) { launched = it }

        assertEquals(RedirectDispatcher.Outcome.Launched, outcome)
        assertSame(action, launched)
    }

    @Test
    fun `a throwing merchant hook launches nothing and reports its error`() = runBlocking {
        var launched: RedirectAction? = null
        val failure = IllegalStateException("no browser available")

        val outcome = RedirectDispatcher.dispatch(action, delegate { throw failure }) { launched = it }

        assertTrue(outcome is RedirectDispatcher.Outcome.HookFailed)
        assertSame(failure, (outcome as RedirectDispatcher.Outcome.HookFailed).error)
        assertNull(launched)
    }

    @Test
    fun `declining with no launcher wired is still a launch outcome, not a failure`() = runBlocking {
        val outcome = RedirectDispatcher.dispatch(action, delegate { false }, launch = null)

        assertEquals(RedirectDispatcher.Outcome.Launched, outcome)
    }
}
