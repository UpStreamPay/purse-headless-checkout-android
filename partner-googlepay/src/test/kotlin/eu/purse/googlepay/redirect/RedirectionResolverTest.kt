package eu.purse.googlepay.redirect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectionResolverTest {

    private fun resolve(json: String) =
        RedirectionResolver.resolve(JSONObject(json), "ingenico", "googlepay", sessionFallbackUrl = "https://fallback")

    @Test
    fun `a global url resolves to a GET`() {
        val action = resolve("""{ "results": [], "redirection": { "url": "https://3ds" } }""")
        assertEquals(RedirectAction.Get("https://3ds"), action)
    }

    @Test
    fun `a global form resolves to POST html`() {
        val action = resolve("""{ "results": [], "redirection": { "form": "<form></form>" } }""")
        assertTrue(action is RedirectAction.PostHtml)
    }

    @Test
    fun `per-item redirection_json + url resolves to a POST form`() {
        val action = resolve(
            """
            { "results": [ { "partner": "ingenico", "method": "googlepay",
                "plugin_result": { "logs": { "redirection_json": "{\"a\":\"1\"}", "redirection_url": "https://acs" } } } ],
              "redirection": {} }
            """.trimIndent(),
        )
        assertEquals(RedirectAction.PostForm("https://acs", "{\"a\":\"1\"}"), action)
    }

    @Test
    fun `should_submit_widget falls back to the session redirection`() {
        val action = resolve("""{ "results": [], "redirection": { "should_submit_widget": true } }""")
        assertEquals(RedirectAction.Get("https://fallback"), action)
    }

    @Test
    fun `no redirection resolves to null`() {
        assertNull(resolve("""{ "results": [], "redirection": {} }"""))
    }
}
