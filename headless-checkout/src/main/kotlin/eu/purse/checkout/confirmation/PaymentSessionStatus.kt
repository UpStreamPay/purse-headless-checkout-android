package eu.purse.checkout.confirmation

import org.json.JSONArray

/**
 * One operation row from `GET /{entity}/sessions/{session}` — an **operation, not a payment**: a
 * single auto-captured payment legitimately shows one `AUTHORIZE` and one `CAPTURE` row on Payment
 * API v2, while v1 aggregates them (see docs/plugins/README.md, "Reading sessionTransactions()").
 */
data class TransactionOperation(
    val partner: String,
    val method: String,
    /** `status.action` — `INIT` / `APPLY` / `AUTHORIZE` / `CAPTURE` / `VOID` / `REFUND`. */
    val action: String,
    /** `status.state` — `SUCCESS` / `ERROR` / `UNCERTAIN` / `FAILED` / `WAITING`. */
    val state: String,
    val code: String?,
    val id: String?,
    /** Links a v2 `CAPTURE` row back to its `AUTHORIZE` row. */
    val parentTransactionId: String?,
    val partnerReference: String?,
)

/**
 * What the session's own transactions say about the payment — the settled answer a coordinator's
 * "the shopper came back from the redirect" deliberately is not.
 */
sealed class PaymentSessionStatus {
    abstract val operations: List<TransactionOperation>

    /** A successful `AUTHORIZE` or `CAPTURE` exists — the payment went through. */
    data class Confirmed(override val operations: List<TransactionOperation>) : PaymentSessionStatus()

    /** Money movement failed (`AUTHORIZE`/`CAPTURE` in `ERROR`/`FAILED`) with no success in sight. */
    data class Failed(override val operations: List<TransactionOperation>) : PaymentSessionStatus()

    /** Nothing settled yet (only inits, or `WAITING`/`UNCERTAIN` states). */
    data class Pending(override val operations: List<TransactionOperation>) : PaymentSessionStatus()

    companion object {
        private val MONEY_ACTIONS = setOf("AUTHORIZE", "CAPTURE")
        private val FAILED_STATES = setOf("ERROR", "FAILED")

        /** Classify a raw `sessionTransactions()` response. */
        fun from(transactions: JSONArray): PaymentSessionStatus {
            val operations = parse(transactions)
            val money = operations.filter { it.action in MONEY_ACTIONS }
            return when {
                money.any { it.state == "SUCCESS" } -> Confirmed(operations)
                money.isNotEmpty() && money.all { it.state in FAILED_STATES } -> Failed(operations)
                else -> Pending(operations)
            }
        }

        fun parse(transactions: JSONArray): List<TransactionOperation> =
            (0 until transactions.length()).mapNotNull { index ->
                val row = transactions.optJSONObject(index) ?: return@mapNotNull null
                val status = row.optJSONObject("status") ?: return@mapNotNull null
                TransactionOperation(
                    partner = row.optString("partner"),
                    method = row.optString("method"),
                    action = status.optString("action"),
                    state = status.optString("state"),
                    code = status.optString("code").ifBlank { null },
                    id = row.optString("id").ifBlank { null },
                    parentTransactionId = row.optString("parent_transaction_id").ifBlank { null },
                    partnerReference = row.optJSONObject("plugin_result")
                        ?.optString("partner_reference")?.ifBlank { null },
                )
            }
    }
}
