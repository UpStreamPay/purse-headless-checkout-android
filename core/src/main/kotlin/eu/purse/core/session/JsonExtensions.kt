// Web: none
// Same: —
// Differs: null-safe org.json helpers.
package eu.purse.core.session

import org.json.JSONObject
import java.math.BigDecimal

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

/**
 * Reads a JSON number field as [BigDecimal] via its string form, rather than
 * `JSONObject.getBigDecimal` (not available on Android's bundled `org.json`). Amounts in a
 * session (e.g. `payment_min_amount: 0.01`) are decimal major-currency-unit values, not
 * integers — see docs/architecture/headless-checkout.md's amount-units note.
 */
internal fun JSONObject.optBigDecimalOrNull(name: String): BigDecimal? =
    if (has(name) && !isNull(name)) BigDecimal(get(name).toString()) else null

internal fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) getInt(name) else null

internal fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).map { array.getString(it) }
}
