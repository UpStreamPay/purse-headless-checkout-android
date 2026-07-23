package eu.purse.dropin.partnerui.generic.redirection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormRedirectHtmlBuilderTest {

    @Test
    fun `parseFormFields reads a flat JSON object`() {
        val fields = FormRedirectHtmlBuilder.parseFormFields("""{"field":"value","other":"thing"}""")

        assertEquals(mapOf("field" to "value", "other" to "thing"), fields)
    }

    @Test
    fun `parseFormFields flattens one level of nested JSON objects into key subKey names`() {
        val fields = FormRedirectHtmlBuilder.parseFormFields("""{"card":{"number":"4111","brand":"VISA"}}""")

        assertEquals(mapOf("card[number]" to "4111", "card[brand]" to "VISA"), fields)
    }

    @Test
    fun `parseFormFields falls back to query-string parsing for non-JSON input`() {
        val fields = FormRedirectHtmlBuilder.parseFormFields("field=value&other=thing")

        assertEquals(mapOf("field" to "value", "other" to "thing"), fields)
    }

    @Test
    fun `parseFormFields url-decodes query-string values`() {
        val fields = FormRedirectHtmlBuilder.parseFormFields("redirect=https%3A%2F%2Fexample.com%2Freturn")

        assertEquals(mapOf("redirect" to "https://example.com/return"), fields)
    }

    @Test
    fun `buildAutoSubmitFormHtml embeds a hidden input per field and auto-submits`() {
        val html = FormRedirectHtmlBuilder.buildAutoSubmitFormHtml(
            "https://3ds.example/step",
            """{"field":"value"}""",
        )

        assertTrue(html.contains("action=\"https://3ds.example/step\""))
        assertTrue(html.contains("method=\"POST\""))
        assertTrue(html.contains("name=\"field\" value=\"value\""))
        assertTrue(html.contains("document.forms[0].submit()"))
    }

    @Test
    fun `buildAutoSubmitFormHtml html-escapes field values`() {
        val html = FormRedirectHtmlBuilder.buildAutoSubmitFormHtml(
            "https://3ds.example/step",
            """{"field":"<script>\"evil\"</script>"}""",
        )

        assertTrue(html.contains("&lt;script&gt;&quot;evil&quot;&lt;/script&gt;"))
        assertTrue(!html.contains("<script>"))
    }
}
