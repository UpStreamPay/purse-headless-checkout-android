// Web: packages/headless-checkout `mapPluginResultToSimulation`
// Same: field names; counts and amounts only (no per-instalment dates on web either).
// Differs: `totalAmount` = initial + (n − 2) recurring + final. Web adds (n − 1) recurring on top of
//          initial and final, which counts one instalment twice when `instalment_number` is the total.
package eu.purse.core.payment

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

/**
 * What a BNPL partner needs to quote an instalment schedule — web sdk's `LoanProduct`
 * (`sdk/src/API/types.ts`): the amount to finance and its ISO-4217 currency.
 *
 * Serialized as `{ "amount": <number>, "currency_code": "EUR" }` for
 * `POST /simulation/{entityId}/{partner}/{method}`.
 */
data class LoanProduct(
    /** Amount to finance, in major currency units. */
    val amount: BigDecimal,
    /** ISO-4217 code, e.g. `"EUR"`. */
    val currencyCode: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("amount", amount)
        .put("currency_code", currencyCode)
}

/**
 * One BNPL instalment schedule — web sdk's `LoanSimulation`, mapped from the simulation API's
 * `plugin_result` exactly as `mapPluginResultToSimulation` does. Amounts are in major currency
 * units.
 *
 * Like web, the schedule carries **counts and amounts only**: the API returns no per-instalment
 * dates, so none are exposed here.
 */
data class LoanSimulation(
    /** First instalment — `initial_instalment_amount`. */
    val initialInstallmentAmount: BigDecimal,
    /** Last instalment — `final_instalment_amount`. */
    val finalInstallmentAmount: BigDecimal,
    /** Each recurring instalment in between — `instalment_amount`. */
    val installmentAmount: BigDecimal,
    /** Number of instalments — `instalment_number`. */
    val installmentCount: Int,
    /** Cost of the loan, excluding the financed amount — `total_cost`. */
    val totalCost: BigDecimal,
    /**
     * Everything the shopper pays: `(count - 1) × installmentAmount + initial + final`, rounded to
     * two decimals — web's `totalAmount` arithmetic.
     */
    val totalAmount: BigDecimal,
    /** Legal wording some partners require next to the offer — `legal_text`. */
    val legalText: String?,
    /** Annual percentage rate — `annual_nominal_rate`, else `effective_annual_percentage_rate`. */
    val aprc: BigDecimal?,
) {
    companion object {
        /**
         * The simulation carried by a `/simulation` response, or `null` when it has no
         * `plugin_result` (web throws `INVALID_LOAN_SIMULATION` for that; the API client does the
         * same, and the multi variant skips such candidates).
         */
        fun fromResponse(response: JSONObject): LoanSimulation? =
            response.optJSONObject("plugin_result")?.let(::fromPluginResult)

        /** Map a `plugin_result` block — web's `mapPluginResultToSimulation`. */
        fun fromPluginResult(result: JSONObject): LoanSimulation {
            val initial = result.decimal("initial_instalment_amount")
            val final = result.decimal("final_instalment_amount")
            val recurring = result.decimal("instalment_amount")
            val count = result.optInt("instalment_number", 0)
            // `instalment_number` counts every instalment: the initial one, the recurring ones and
            // the final one. A single instalment is just the initial amount.
            val total = when {
                count <= 1 -> initial
                else -> initial.add(recurring.multiply(BigDecimal(count - 2))).add(final)
            }.setScale(2, RoundingMode.HALF_UP)
            return LoanSimulation(
                initialInstallmentAmount = initial,
                finalInstallmentAmount = final,
                installmentAmount = recurring,
                installmentCount = count,
                totalCost = result.decimal("total_cost"),
                totalAmount = total,
                legalText = result.optString("legal_text").ifBlank { null },
                aprc = result.decimalOrNull("annual_nominal_rate")
                    ?: result.decimalOrNull("effective_annual_percentage_rate"),
            )
        }

        private fun JSONObject.decimalOrNull(key: String): BigDecimal? =
            if (has(key) && !isNull(key)) runCatching { BigDecimal(get(key).toString()) }.getOrNull() else null

        private fun JSONObject.decimal(key: String): BigDecimal = decimalOrNull(key) ?: BigDecimal.ZERO
    }
}
