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

    /** A delegate that records, in order, the hook call, every `onRedirecting` report and the launch. */
    private class Recorder(
        private val answer: suspend (RedirectAction) -> Boolean,
        private val report: (RedirectAction, Boolean) -> Unit = { _, _ -> },
    ) : RedirectionDelegate {
        val steps = mutableListOf<String>()
        val reports = mutableListOf<Pair<RedirectAction, Boolean>>()

        override suspend fun delegateRedirection(action: RedirectAction): Boolean {
            steps += "hook"
            return answer(action)
        }

        override fun onRedirecting(action: RedirectAction, delegated: Boolean) {
            steps += "report"
            reports += action to delegated
            report(action, delegated)
        }

        val launch: (RedirectAction) -> Unit = { steps += "launch" }
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

    // --- SDK-12712: every redirect that leaves is reported once, with whether the merchant took it ---

    @Test
    fun `a redirect the SDK opens is reported as not delegated, after the hook and before the launch`() = runBlocking {
        val recorder = Recorder({ false })

        RedirectDispatcher.dispatch(action, recorder, recorder.launch)

        assertEquals(listOf("hook", "report", "launch"), recorder.steps)
        assertEquals(listOf(action to false), recorder.reports)
    }

    @Test
    fun `a redirect the merchant takes over is reported as delegated, and nothing is launched`() = runBlocking {
        val recorder = Recorder({ true })

        val outcome = RedirectDispatcher.dispatch(action, recorder, recorder.launch)

        assertEquals(RedirectDispatcher.Outcome.Delegated, outcome)
        assertEquals(listOf("hook", "report"), recorder.steps)
        assertEquals(listOf(action to true), recorder.reports)
    }

    @Test
    fun `a throwing hook opened nothing, so no redirect is reported`() = runBlocking {
        val recorder = Recorder({ throw IllegalStateException("no browser available") })

        val outcome = RedirectDispatcher.dispatch(action, recorder, recorder.launch)

        assertTrue(outcome is RedirectDispatcher.Outcome.HookFailed)
        assertEquals(listOf("hook"), recorder.steps)
    }

    @Test
    fun `a decline with no launcher wired opens nothing, so no redirect is reported`() = runBlocking {
        val recorder = Recorder({ false })

        RedirectDispatcher.dispatch(action, recorder, launch = null)

        assertTrue(recorder.reports.isEmpty())
    }

    @Test
    fun `a report that throws neither blocks the launch nor changes the outcome`() = runBlocking {
        val declining = Recorder({ false }, report = { _, _ -> error("monitoring down") })
        val handling = Recorder({ true }, report = { _, _ -> error("monitoring down") })

        assertEquals(RedirectDispatcher.Outcome.Launched, RedirectDispatcher.dispatch(action, declining, declining.launch))
        assertEquals(listOf("hook", "report", "launch"), declining.steps)
        assertEquals(RedirectDispatcher.Outcome.Delegated, RedirectDispatcher.dispatch(action, handling, handling.launch))
    }

    @Test
    fun `the hook and the launcher get the very same action, whatever the report`() = runBlocking {
        val forms = listOf(
            action,
            RedirectAction.PostForm("https://acs.example/challenge", "PaReq=abc&MD=1", returnUrlPrefix = "https://shop.example/return"),
            RedirectAction.PostHtml("<form action=\"https://acs.example\"></form>"),
        )
        forms.forEach { form ->
            var offered: RedirectAction? = null
            var launched: RedirectAction? = null
            RedirectDispatcher.dispatch(
                form,
                Recorder({
                    offered = it
                    false
                }),
            ) { launched = it }

            assertSame(form, offered)
            assertSame(form, launched)
        }
    }
}
