// Web: none
// Same: —
// Differs: org.json helpers.
package eu.purse.partnerui.plugins.generics.googlepay

import org.json.JSONObject

/** Shared by [GooglePayMapper] and the per-partner config parsers under `partners/`. */
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

internal fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it, null) }
}
