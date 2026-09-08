// Web: none — a browser tab keeps the SDK alive; Android may kill the process while the shopper is
//      in a bank app, PayPal or a 3-D Secure page.
// Same: n/a.
// Differs: the checkout's choices (primary, applied gift cards, save decisions, what the native steps
//          collected, whether a submit left) serialize to a small JSON the host stores in its saved
//          instance state and hands back to `PurseHeadlessCheckout.restore`.
package eu.purse.checkout.state

import android.os.Parcel
import android.os.Parcelable
import eu.purse.checkout.payment.secondary.SecondaryTakePolicy
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.core.payment.Method
import eu.purse.core.payment.Partner
import eu.purse.core.payment.SaveTokenMode
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/**
 * A snapshot of a `PurseHeadlessCheckout` — everything needed to pick a payment up where the
 * shopper left it after Android killed the process: the client session, the primary selection,
 * the applied gift cards and their slices, the save decisions, what the native steps collected,
 * and whether a submit had already left the device. Obtain one with
 * `PurseHeadlessCheckout.saveState()`, store it (`SavedStateHandle`, `onSaveInstanceState`,
 * `rememberSaveable`), hand it back to `PurseHeadlessCheckout.restore`.
 *
 * Contains no card data: a card form's input is never saved, by design.
 *
 * [Parcelable] for Android's saved-state APIs; [toJson] / [fromJson] for anything else.
 */
class CheckoutSavedState internal constructor(internal val json: JSONObject) : Parcelable {

    /** The snapshot as JSON — the same string [fromJson] accepts. */
    fun toJson(): String = json.toString()

    /** When the snapshot was taken, epoch milliseconds. */
    val savedAt: Long get() = json.optLong(SAVED_AT)

    /** `true` when a submit had already left the device: the restored session is spent, confirm the outcome. */
    val submitted: Boolean get() = json.optBoolean(SUBMITTED)

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(json.toString())
    }

    override fun equals(other: Any?): Boolean = other is CheckoutSavedState && other.json.toString() == json.toString()

    override fun hashCode(): Int = json.toString().hashCode()

    override fun toString(): String = "CheckoutSavedState(submitted=$submitted, savedAt=$savedAt)"

    companion object {
        internal const val VERSION = 1
        internal const val KEY_VERSION = "version"
        internal const val WIDGET_DATA = "widgetData"
        internal const val LOCKED_ENVIRONMENT = "lockedEnvironment"
        internal const val DISABLED_METHODS = "disabledMethods"
        internal const val PRIMARY_ID = "primaryId"
        internal const val SECONDARIES = "secondaries"
        internal const val REGISTRATION_BY_METHOD = "registrationByMethod"
        internal const val REGISTRATION_BY_SECONDARY_TOKEN = "registrationBySecondaryToken"
        internal const val COLLECTED = "collected"
        internal const val SUBMITTED = "submitted"
        internal const val SAVED_AT = "savedAt"

        /**
         * Parse a snapshot [toJson] produced.
         * @throws IllegalArgumentException when [json] is not one, or comes from a newer SDK.
         */
        fun fromJson(json: String): CheckoutSavedState {
            val parsed = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("not a CheckoutSavedState", it) }
            val version = parsed.optInt(KEY_VERSION, -1)
            require(version in 1..VERSION) { "unsupported CheckoutSavedState version $version" }
            require(parsed.has(WIDGET_DATA)) { "CheckoutSavedState carries no session" }
            return CheckoutSavedState(parsed)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<CheckoutSavedState> = object : Parcelable.Creator<CheckoutSavedState> {
            override fun createFromParcel(source: Parcel): CheckoutSavedState = fromJson(checkNotNull(source.readString()))

            override fun newArray(size: Int): Array<CheckoutSavedState?> = arrayOfNulls(size)
        }
    }
}

/** The applied gift card, with the slice it draws, as JSON — every field of the data class, so `/validate` can rebuild its item. */
internal fun SecondaryToken.toSavedJson(amount: BigDecimal): JSONObject = JSONObject()
    .put("id", id)
    .put("partner", partner.wireValue)
    .put("method", method.wireValue)
    .put("pan", pan)
    .put("cvv", cvv ?: JSONObject.NULL)
    .put("name", name)
    .put("balance", balance.toPlainString())
    .put("totalBalance", totalBalance.toPlainString())
    .put("expirationDate", expirationDate)
    .put("takePolicy", takePolicy.name)
    .put("limit", limit?.toPlainString() ?: JSONObject.NULL)
    .put("usageLimit", usageLimit ?: JSONObject.NULL)
    .put("saveTokenMode", saveTokenMode.name)
    .put("isSaved", isSaved)
    .put("amount", amount.toPlainString())

internal fun secondaryTokenFromSavedJson(json: JSONObject): Pair<SecondaryToken, BigDecimal> {
    val token = SecondaryToken(
        id = json.getString("id"),
        partner = Partner.fromWire(json.getString("partner")),
        method = Method.fromWire(json.getString("method")),
        pan = json.getString("pan"),
        cvv = json.optString("cvv").takeIf { !json.isNull("cvv") },
        name = json.optString("name"),
        balance = BigDecimal(json.getString("balance")),
        totalBalance = BigDecimal(json.optString("totalBalance", json.getString("balance"))),
        expirationDate = json.optString("expirationDate"),
        takePolicy = SecondaryTakePolicy.entries.firstOrNull { it.name == json.optString("takePolicy") } ?: SecondaryTakePolicy.NONE,
        limit = json.optString("limit").takeIf { !json.isNull("limit") && it.isNotBlank() }?.let(::BigDecimal),
        usageLimit = if (json.isNull("usageLimit")) null else json.optInt("usageLimit"),
        saveTokenMode = SaveTokenMode.entries.firstOrNull { it.name == json.optString("saveTokenMode") } ?: SaveTokenMode.NEVER,
        isSaved = json.optBoolean("isSaved", false),
    )
    return token to BigDecimal(json.getString("amount"))
}

/** Only what JSON can carry: a Klarna authorize callback or any other object is left out (the step re-prepares). */
internal fun Map<String, Any?>.toSavedJson(): JSONObject = JSONObject().also { out ->
    forEach { (key, value) ->
        when (value) {
            null -> out.put(key, JSONObject.NULL)
            is String, is Boolean, is Number, is JSONObject, is JSONArray -> out.put(key, value)
            else -> Unit
        }
    }
}

internal fun JSONObject.toCollectedMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    if (isNull(key)) null else get(key)
}
