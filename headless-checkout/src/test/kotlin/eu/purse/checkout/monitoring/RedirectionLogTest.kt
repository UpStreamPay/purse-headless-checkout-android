package eu.purse.checkout.monitoring

import eu.purse.partnerui.redirect.RedirectAction
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `HEADLESS_CHECKOUT_REDIRECTION` (SDK-12712): Widget V3's payload, the destination hostname and
 * nothing else of the URL, and a post that can never throw at the redirect.
 */
class RedirectionLogTest {

    private val url = "https://ACS.Bank.example:8443/challenge/42?PaReq=secret-pareq&email=shopper%40example.com#step"

    @Test
    fun `a plain redirect logs its hostname as url`() {
        val payload = redirectionPayload(RedirectAction.Get(url), delegated = false)

        assertEquals("acs.bank.example", payload.getString("url"))
        assertFalse(payload.getBoolean("delegated"))
        assertEquals(setOf("url", "delegated"), payload.keys().asSequence().toSet())
    }

    @Test
    fun `a form built from a URL logs its hostname as action, never the form fields`() {
        val payload = redirectionPayload(RedirectAction.PostForm(url, "PaReq=secret-pareq&MD=md-1"), delegated = true)

        assertEquals("acs.bank.example", payload.getString("action"))
        assertEquals(true, payload.getBoolean("delegated"))
        assertEquals(setOf("action", "delegated"), payload.keys().asSequence().toSet())
    }

    @Test
    fun `an HTML form from the API logs string_form, never the HTML`() {
        val html = """<form action="https://acs.bank.example/challenge" method="POST"><input name="PaReq" value="secret-pareq"></form>"""

        val payload = redirectionPayload(RedirectAction.PostHtml(html), delegated = false)

        assertEquals(true, payload.getBoolean("string_form"))
        assertEquals(setOf("string_form", "delegated"), payload.keys().asSequence().toSet())
    }

    @Test
    fun `only the hostname leaves the device`() {
        val withUserInfo = "https://user:p4ss@psp.example/pay/ORDER-1?amount=4.80&email=shopper@example.com"
        val payloads = listOf(
            redirectionPayload(RedirectAction.Get(url), delegated = false),
            redirectionPayload(RedirectAction.PostForm(withUserInfo, "card=4111111111111111"), delegated = true),
            redirectionPayload(RedirectAction.Get(withUserInfo, returnUrlPrefix = "https://merchant.example/return"), false),
        ).joinToString { it.toString() }

        listOf("challenge", "PaReq", "secret", "shopper", "8443", "step", "user", "p4ss", "ORDER-1", "4.80", "4111", "merchant")
            .forEach { assertFalse("'$it' must not be logged: $payloads", payloads.contains(it)) }
    }

    @Test
    fun `a URL the SDK cannot read logs Widget V3's text`() {
        listOf("", "   ", "not a url", "/relative/path?x=1", "psp.example/pay", "https://", "https:///path", "mailto:shop@example.com")
            .forEach { assertEquals("for '$it'", "couldn't parse the url", redirectionHostname(it)) }
        assertEquals(
            "couldn't parse the url",
            redirectionPayload(RedirectAction.PostForm("::::", "a=b"), delegated = false).getString("action"),
        )
    }

    @Test
    fun `characters URI rejects after the host do not hide it`() {
        assertEquals("psp.example", redirectionHostname("https://psp.example/pay now?ref=a|b c"))
        assertEquals("psp.example", redirectionHostname("  https://psp.example  "))
        assertEquals("[2001:db8::1]", redirectionHostname("https://[2001:db8::1]:443/acs"))
    }

    @Test
    fun `postRedirection posts one INFO event with the payload`() {
        val events = CopyOnWriteArrayList<CheckoutMonitoringEvent>()
        val bus = CheckoutEventBus.getInstance()
        val unsubscribe = bus.subscribe(CheckoutEventBus.Scope.ALL) { events.add(it) }
        try {
            bus.postRedirection(RedirectAction.Get(url), delegated = true)
        } finally {
            unsubscribe()
        }

        val event = events.single { it.code == CheckoutEventCode.HEADLESS_CHECKOUT_REDIRECTION }
        assertEquals(EventLevel.INFO, event.type)
        val payload = event.payload!!
        assertEquals("acs.bank.example", payload.getString("url"))
        assertEquals(true, payload.getBoolean("delegated"))
    }

    @Test
    fun `a failing subscriber cannot make the post throw`() {
        val bus = CheckoutEventBus.getInstance()
        val unsubscribe = bus.subscribe(CheckoutEventBus.Scope.ALL) { error("subscriber blew up") }
        try {
            bus.postRedirection(RedirectAction.PostHtml("<form></form>"), delegated = false)
        } finally {
            unsubscribe()
        }
    }
}
