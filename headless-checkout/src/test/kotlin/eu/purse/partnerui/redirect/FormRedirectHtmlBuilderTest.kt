package eu.purse.partnerui.redirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormRedirectHtmlBuilderTest {

    @Test
    fun `parses a JSON object, flattening one level of nesting`() {
        val fields = FormRedirectHtmlBuilder.parseFormFields("""{ "a": "1", "b": { "c": "2" } }""")
        assertEquals("1", fields["a"])
        assertEquals("2", fields["b[c]"])
    }

    @Test
    fun `parses a url-encoded query string`() {
        val fields = FormRedirectHtmlBuilder.parseFormFields("a=1&b=two%20words")
        assertEquals("1", fields["a"])
        assertEquals("two words", fields["b"])
    }

    @Test
    fun `builds an auto-submitting form with the action and hidden inputs`() {
        val html = FormRedirectHtmlBuilder.buildAutoSubmitFormHtml("https://acs", """{ "a": "1" }""")
        assertTrue(html.contains("action=\"https://acs\""))
        assertTrue(html.contains("name=\"a\""))
        assertTrue(html.contains("value=\"1\""))
        assertTrue(html.contains("document.forms[0].submit()"))
    }
}
