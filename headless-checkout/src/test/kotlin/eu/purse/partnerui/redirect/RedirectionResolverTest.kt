package eu.purse.partnerui.redirect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectionResolverTest {

    private fun resolve(json: String, sessionReturnUrl: String? = null) =
        RedirectionResolver.resolve(JSONObject(json), "ingenico", "googlepay", sessionReturnUrl)

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
    fun `should_submit_widget alone resolves to null — no widget to resubmit here`() {
        assertNull(resolve("""{ "results": [], "redirection": { "should_submit_widget": true } }"""))
    }

    @Test
    fun `a global url pointing at the session's own return url resolves to null`() {
        // Real-world case: the widget's own confirmation page — e.g.
        // "https://…/widgets/confirmation?purse-redirection-data=<jwt>" — arrives as a plain global
        // `redirection.url`, not gated by should_submit_widget. Its JWT decodes to the very
        // `authorization_status` the SAME /validate call already returned in `global_status`, so it's
        // never a genuine PSP/bank redirect — just this same web-only confirmation hop.
        val action = resolve(
            """{ "results": [], "redirection": { "url": "https://merchant.example/confirmation?data=x" } }""",
            sessionReturnUrl = "https://merchant.example/confirmation",
        )
        assertNull(action)
    }

    @Test
    fun `a global url NOT matching the session's own return url still resolves`() {
        val action = resolve(
            """{ "results": [], "redirection": { "url": "https://acs.example/challenge" } }""",
            sessionReturnUrl = "https://merchant.example/confirmation",
        )
        assertEquals(RedirectAction.Get("https://acs.example/challenge"), action)
    }

    @Test
    fun `a purse-redirection-data url resolves to null even without a session return url`() {
        // The marker alone is enough — no sessionReturnUrl needed, unlike the test above.
        val action = resolve(
            """{ "results": [], "redirection": { "url": "https://localhost:8000/widgets/confirmation?purse-redirection-data=abc" } }""",
        )
        assertNull(action)
    }

    @Test
    fun `no redirection resolves to null`() {
        assertNull(resolve("""{ "results": [], "redirection": {} }"""))
    }

    @Test
    fun `extractWidgetConfirmationPaymentId reads payment_id off a real confirmation JWT`() {
        // Captured from an actual /validate response's global redirection.url. Its JWT payload
        // decodes to {"client_session_status":"SUBMITTED","client_session_id":"…",
        // "payment_id":"90e8c2a1-171c-4b2c-95f6-ad61a2d8362a","authorization_status":"AUTHORIZED"}.
        // A real signed token: its segments cannot be wrapped without changing the fixture.
        @Suppress("ktlint:standard:max-line-length")
        val jwt = "eyJraWQiOiJiNzY5ODA5YS01ZTIwLTkxOWEtYTQyNS04MWYzNzFhNzIxNTMiLCJhbGciOiJSUzI1NiJ9." +
            "eyJjbGllbnRfc2Vzc2lvbl9zdGF0dXMiOiJTVUJNSVRURUQiLCJjbGllbnRfc2Vzc2lvbl9pZCI6ImIxMmZiMTZlLTNkNTktNDNiZC1hMDA1LWQ4MzI0Mzg0MDYzYyIsInBheW1lbnRfaWQiOiI5MGU4YzJhMS0xNzFjLTRiMmMtOTVmNi1hZDYxYTJkODM2MmEiLCJhdXRob3JpemF0aW9uX3N0YXR1cyI6IkFVVEhPUklaRUQifQ." +
            "jORskhTGRsIRAfzD1A3K2-nhvdHxPmoXWDQb-dJuiFk2lS0uNkcIiLEif4Mzw8Ujo6VIICHyKVDrE4UxEj7_T1RIw9H4rPD5mQbzePvYV6IipwJLpgfaT-Uv-9QF6F8b9ZbIINsLZ_VCoHat1jmcAb_rCoeQ1n_yiuLIMvXQh7g_P0ilCyxM7Ep7jGNGicU6TH0QoIeR-ztoigKEX4tqd3-ZqnOLMD0lwoKSKI1Rx4m1gtIjcTwP1jS3tZ-qyyGbh3_pVoMTy5haV_uSw-aePpqT5gyaUMoWOqFajmCpHpzuDRTnZaRTqy_g3xtmmHJcSBN1bIyuyDuBYj3j2iJAUg"
        val validateResponse = JSONObject(
            """{ "results": [], "redirection": { "url": "https://localhost:8000/widgets/confirmation?purse-redirection-data=$jwt" } }""",
        )

        val paymentId = RedirectionResolver.extractWidgetConfirmationPaymentId(validateResponse, "ingenico", "googlepay")

        assertEquals("90e8c2a1-171c-4b2c-95f6-ad61a2d8362a", paymentId)
        // And the same response's redirect is still correctly suppressed by resolve().
        assertNull(RedirectionResolver.resolve(validateResponse, "ingenico", "googlepay"))
    }

    @Test
    fun `extractWidgetConfirmationPaymentId returns null when there's no confirmation redirect`() {
        val empty = JSONObject("""{ "results": [], "redirection": {} }""")
        assertNull(RedirectionResolver.extractWidgetConfirmationPaymentId(empty, "ingenico", "googlepay"))
    }
}
