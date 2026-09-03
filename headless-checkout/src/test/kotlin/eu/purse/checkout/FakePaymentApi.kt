package eu.purse.checkout

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny in-process Payment API for the JVM tests — the SDK's clients are plain
 * `HttpURLConnection`, so a loopback `HttpServer` (bundled with the JDK, no dependency) is enough
 * to script `/init` / `/validate` answers and to capture what was sent. Route handlers are keyed by
 * path suffix (`"/init"`, `"/validate"`, `""` for the session GET); an unrouted path answers 404.
 *
 * `apiRoot` plugs straight into `CheckoutTestSessions.widgetData(apiUrl = …)` or a
 * `PaymentApiClient`. Always [close] it (or use [use]).
 */
internal class FakePaymentApi : AutoCloseable {

    class Request(val method: String, val path: String, val body: String) {
        val json: JSONObject get() = JSONObject(body)
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val routes = mutableMapOf<String, (Request) -> Pair<Int, String>>()

    /** Every request received, in order. */
    val requests = CopyOnWriteArrayList<Request>()

    val apiRoot: String get() = "http://127.0.0.1:${server.address.port}/payment/v1"

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val path = exchange.requestURI.path
            val request = Request(exchange.requestMethod, path, body)
            requests += request
            val (status, answer) = routes[routeOf(path)]?.invoke(request) ?: (404 to """{"error":"NOT_FOUND"}""")
            val bytes = answer.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    /** Script the answer for [route] — `"init"`, `"validate"`, or `""` for the session GET. */
    fun on(route: String, handler: (Request) -> Pair<Int, String>): FakePaymentApi {
        routes[route] = handler
        return this
    }

    /** Shortcut: a fixed 200 answer for [route]. */
    fun respond(route: String, body: String): FakePaymentApi = on(route) { 200 to body }

    fun initRequests(): List<Request> = requests.filter { it.path.endsWith("/init") }

    /** Block until [count] requests hit [route] (or the timeout elapses) — for background calls. */
    fun awaitRequests(route: String, count: Int = 1, timeoutMs: Long = 5_000): List<Request> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val matching = requests.filter { routeOf(it.path) == route }
            if (matching.size >= count || System.currentTimeMillis() >= deadline) return matching
            Thread.sleep(20)
        }
    }

    /** `/payment/v1/{entity}/sessions/{session}/init` → `"init"`; the bare session GET → `""`. */
    private fun routeOf(path: String): String =
        path.split("/sessions/", limit = 2).getOrNull(1)?.substringAfter("/", "").orEmpty()

    override fun close() = server.stop(0)

    companion object {
        /** A successful generic-card `/init` answer with [balance] — the shape `parseCardToken` reads. */
        fun successfulCardInit(balance: String, maskedCard: String = "925000••••7890", expiry: String = "2027-12"): String =
            """
            {
              "status": { "state": "SUCCESS" },
              "plugin_result": {
                "payment_data": { "cards": [ { "balance": $balance, "expiry_date": "$expiry" } ] },
                "logs": { "masked_card": "$maskedCard" }
              }
            }
            """.trimIndent()

        fun failedInit(description: String = "card refused", errorCode: String? = null): String =
            JSONObject()
                .put("status", JSONObject().put("state", "FAILURE").put("description", description))
                .also { if (errorCode != null) it.put("error", errorCode) }
                .toString()
    }
}
