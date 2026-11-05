package eu.purse.core.api

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rename and favorite must go out as a real `PATCH`. The Wallet API answers the old
 * `POST` + `X-HTTP-Method-Override: PATCH` with an empty 401 (SDK-12573).
 */
class WalletApiClientTest {

    /**
     * Records what the client sends and answers with [status] / [responseBody]. It accepts `PATCH`
     * like Android's platform connection (`com.android.okhttp`) does. The JDK's own
     * `HttpURLConnection` refuses it, which is why the JVM tests can't go over the wire.
     */
    private class RecordingConnection(
        url: URL,
        private val status: Int,
        private val responseBody: String,
    ) : HttpURLConnection(url) {
        private val sent = ByteArrayOutputStream()
        var disconnected = false
            private set

        val sentBody: String get() = sent.toString(Charsets.UTF_8.name())

        override fun setRequestMethod(method: String) {
            this.method = method
        }

        override fun getOutputStream(): OutputStream = sent

        override fun getResponseCode(): Int = status

        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBody.toByteArray())

        override fun getErrorStream(): InputStream = ByteArrayInputStream(responseBody.toByteArray())

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false
    }

    private val connections = mutableListOf<RecordingConnection>()

    private fun client(status: Int = 200, responseBody: String = "") = WalletApiClient(
        apiKey = "key-123",
        walletApiRoot = "https://api.example.test/wallet/v2/",
        ownerReference = "owner-1",
        walletSessionId = "ws-1",
        merchantId = "merchant-1",
        openConnection = { url -> RecordingConnection(url, status, responseBody).also { connections += it } },
    )

    private fun RecordingConnection.assertWalletHeaders() {
        assertEquals("key-123", getRequestProperty("x-api-key"))
        assertEquals("merchant-1", getRequestProperty("x-merchant-id"))
        assertNull("no method override header", getRequestProperty("X-HTTP-Method-Override"))
        assertEquals(15_000, connectTimeout)
        assertEquals(15_000, readTimeout)
        assertTrue(disconnected)
    }

    @Test
    fun `editTokenName sends a real PATCH with the JSON body`() {
        client().editTokenName("tok-1", "My card", reason = "rename")

        val connection = connections.single()
        assertEquals("PATCH", connection.requestMethod)
        assertEquals("https://api.example.test/wallet/v2/owner-1/sessions/ws-1/tokens/tok-1/name", connection.url.toString())
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        val body = JSONObject(connection.sentBody)
        assertEquals("My card", body.getString("name"))
        assertEquals("rename", body.getString("reason"))
        connection.assertWalletHeaders()
    }

    @Test
    fun `setAsFavorite sends a real PATCH on the singular token route`() {
        client().setAsFavorite("tok-1", favorite = true)

        val connection = connections.single()
        assertEquals("PATCH", connection.requestMethod)
        assertEquals(
            "https://api.example.test/wallet/v2/owner-1/sessions/ws-1/token/tok-1/favorite?favorite=true",
            connection.url.toString(),
        )
        assertEquals("", connection.sentBody)
        connection.assertWalletHeaders()
    }

    @Test
    fun `deleteToken sends DELETE`() {
        client().deleteToken("tok-1")

        val connection = connections.single()
        assertEquals("DELETE", connection.requestMethod)
        assertEquals("https://api.example.test/wallet/v2/owner-1/sessions/ws-1/tokens/tok-1", connection.url.toString())
        connection.assertWalletHeaders()
    }

    @Test
    fun `getTokens parses the token rows`() {
        val tokens = client(responseBody = """[{"id":"tok-1","name":"Visa","favorite":true},{"id":"tok-2"}]""")
            .getTokens()

        assertEquals("GET", connections.single().requestMethod)
        assertEquals(
            listOf(
                WalletApiClient.WalletToken("tok-1", "Visa", true),
                WalletApiClient.WalletToken("tok-2", null, null),
            ),
            tokens,
        )
    }

    @Test
    fun `a failed PATCH surfaces the HTTP status`() {
        val error = assertThrows(PaymentApiException::class.java) {
            client(status = 404, responseBody = """{"code":"TOKEN_NOT_FOUND"}""").editTokenName("tok-1", "My card")
        }

        assertEquals(404, error.status)
        assertEquals("/tokens/tok-1/name", error.path)
        assertTrue(error.message!!.contains("PATCH"))
        assertTrue(error.message!!.contains("TOKEN_NOT_FOUND"))
        assertEquals("TOKEN_NOT_FOUND", error.errorCode)
    }

    @Test
    fun `a failed call keeps a non-JSON error body out of the exception`() {
        val error = assertThrows(PaymentApiException::class.java) {
            client(status = 502, responseBody = "<html>gateway SECRET-4242</html>").editTokenName("tok-1", "My card")
        }

        assertEquals(502, error.status)
        assertNull(error.errorCode)
        assertFalse(error.message!!.contains("SECRET-4242"))
    }
}
