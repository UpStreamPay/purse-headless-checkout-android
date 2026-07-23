package eu.purse.dropin.partnerui.redirect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedirectionResolverTest {

    @Test
    fun `no redirection object at all resolves to null`() {
        val response = JSONObject("""{ "results": [], "global_status": "SUCCESS" }""")

        val action = RedirectionResolver.resolve(response, "adyen", "googlepay", sessionFallbackUrl = null)

        assertNull(action)
    }

    @Test
    fun `per-item POST form takes priority over everything else`() {
        val response = JSONObject(
            """
            {
              "results": [
                {
                  "partner": "adyen", "method": "googlepay",
                  "plugin_result": { "logs": {
                    "redirection_url": "https://item.example/redirect",
                    "redirection_json": "{\"field\":\"value\"}"
                  } }
                }
              ],
              "redirection": { "url": "https://global.example/redirect" },
              "global_status": "SUCCESS"
            }
            """.trimIndent(),
        )

        val action = RedirectionResolver.resolve(response, "adyen", "googlepay", sessionFallbackUrl = null)

        assertEquals(RedirectAction.PostForm("https://item.example/redirect", "{\"field\":\"value\"}"), action)
    }

    @Test
    fun `global url with no per-item post params resolves to a GET`() {
        val response = JSONObject(
            """{ "results": [], "redirection": { "url": "https://global.example/redirect" }, "global_status": "SUCCESS" }""",
        )

        val action = RedirectionResolver.resolve(response, "adyen", "googlepay", sessionFallbackUrl = null)

        assertEquals(RedirectAction.Get("https://global.example/redirect"), action)
    }

    @Test
    fun `global url with per-item post params resolves to a POST using the global url`() {
        val response = JSONObject(
            """
            {
              "results": [
                {
                  "partner": "adyen", "method": "googlepay",
                  "plugin_result": { "logs": { "redirect_url_post_params": "field=value" } }
                }
              ],
              "redirection": { "url": "https://global.example/redirect" },
              "global_status": "SUCCESS"
            }
            """.trimIndent(),
        )

        val action = RedirectionResolver.resolve(response, "adyen", "googlepay", sessionFallbackUrl = null)

        assertEquals(RedirectAction.PostForm("https://global.example/redirect", "field=value"), action)
    }

    @Test
    fun `raw html form string resolves to PostHtml`() {
        val response = JSONObject(
            """{ "results": [], "redirection": { "form": "<form>...</form>" }, "global_status": "SUCCESS" }""",
        )

        val action = RedirectionResolver.resolve(response, "adyen", "googlepay", sessionFallbackUrl = null)

        assertEquals(RedirectAction.PostHtml("<form>...</form>"), action)
    }

    @Test
    fun `should_submit_widget with a session fallback resolves to a GET on the fallback`() {
        val response = JSONObject(
            """{ "results": [], "redirection": { "should_submit_widget": true }, "global_status": "SUCCESS" }""",
        )

        val action = RedirectionResolver.resolve(
            response,
            "adyen",
            "googlepay",
            sessionFallbackUrl = "https://session.example/fallback",
        )

        assertEquals(RedirectAction.Get("https://session.example/fallback"), action)
    }

    @Test
    fun `should_submit_widget with no session fallback resolves to null`() {
        val response = JSONObject(
            """{ "results": [], "redirection": { "should_submit_widget": true }, "global_status": "SUCCESS" }""",
        )

        val action = RedirectionResolver.resolve(response, "adyen", "googlepay", sessionFallbackUrl = null)

        assertNull(action)
    }

    @Test
    fun `a per-item entry for a different partner-method is ignored`() {
        val response = JSONObject(
            """
            {
              "results": [
                {
                  "partner": "paypal", "method": "wallet",
                  "plugin_result": { "logs": {
                    "redirection_url": "https://other.example/redirect",
                    "redirection_json": "{}"
                  } }
                }
              ],
              "redirection": { "url": "https://global.example/redirect" },
              "global_status": "SUCCESS"
            }
            """.trimIndent(),
        )

        val action = RedirectionResolver.resolve(response, "adyen", "googlepay", sessionFallbackUrl = null)

        assertEquals(RedirectAction.Get("https://global.example/redirect"), action)
    }
}
