package eu.purse.checkout.demo

import java.math.BigDecimal
import org.json.JSONObject

/** The fictional store the checkout belongs to. The basket itself comes from alfred's order. */
object DemoStore {
    const val NAME = "Denim & Co."
    const val TAGLINE = "Since 1989 · Lille"
}

/**
 * What the checkout shows above the payment methods: the basket, the delivery choice and the
 * customer — read off the legacy order alfred mints the session from, so the screen and the session
 * describe the same purchase. A session pasted by hand has no order; [placeholder] then shows one
 * line for the session's own amount.
 */
data class OrderSummary(
    val reference: String,
    val currency: String,
    val total: BigDecimal,
    val lines: List<Line>,
    val delivery: String,
    val customerName: String?,
    val customerEmail: String?,
    val address: String?,
) {
    data class Line(val name: String, val brand: String?, val quantity: Int, val unitPrice: BigDecimal, val amount: BigDecimal)

    val subtotal: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, line -> acc + line.amount }

    companion object {
        /** Parses alfred's `{ amount, order: { reference, currency_code, customer, shipments[] } }`. */
        fun from(legacyOrder: JSONObject): OrderSummary {
            val order = legacyOrder.optJSONObject("order") ?: legacyOrder
            val shipments = order.optJSONArray("shipments")
            val lines = mutableListOf<Line>()
            var delivery: String? = null
            var address: String? = null
            if (shipments != null) {
                for (i in 0 until shipments.length()) {
                    val shipment = shipments.getJSONObject(i)
                    delivery = delivery ?: deliveryLabel(shipment.optString("delivery_type_code"))
                    address = address ?: shipment.optJSONObject("shipping_address")?.let(::addressLine)
                    val items = shipment.optJSONArray("item_lines") ?: continue
                    for (j in 0 until items.length()) {
                        val item = items.getJSONObject(j)
                        lines += Line(
                            name = item.optString("name", "Item"),
                            brand = item.optString("brand").takeIf { it.isNotBlank() },
                            quantity = item.optInt("quantity", 1),
                            unitPrice = item.optDouble("price", 0.0).toBigDecimal(),
                            amount = item.optDouble("amount", 0.0).toBigDecimal(),
                        )
                    }
                }
            }
            val customer = order.optJSONObject("customer")
            val billing = customer?.optJSONObject("billing_address")
            val name = listOfNotNull(
                customer?.optString("first_name")?.takeIf { it.isNotBlank() },
                customer?.optString("last_name")?.takeIf { it.isNotBlank() },
            ).joinToString(" ").takeIf { it.isNotBlank() }
            return OrderSummary(
                reference = order.optString("reference").ifBlank { "—" },
                currency = order.optString("currency_code").ifBlank { "EUR" },
                total = order.optDouble("amount", legacyOrder.optDouble("amount", 0.0)).toBigDecimal(),
                lines = lines,
                delivery = delivery ?: "Delivery",
                customerName = name,
                customerEmail = billing?.optString("email")?.takeIf { it.isNotBlank() },
                address = address ?: billing?.let(::addressLine),
            )
        }

        fun placeholder(total: BigDecimal, currency: String): OrderSummary = OrderSummary(
            reference = "—",
            currency = currency,
            total = total,
            lines = listOf(Line(name = "Your order", brand = null, quantity = 1, unitPrice = total, amount = total)),
            delivery = "Delivery",
            customerName = null,
            customerEmail = null,
            address = null,
        )

        private fun deliveryLabel(code: String): String = when (code) {
            "external_pickup", "pickup" -> "Pickup point"
            "home", "home_delivery" -> "Home delivery"
            "store", "store_pickup" -> "Collect in store"
            else -> "Delivery"
        }

        private fun addressLine(address: JSONObject): String? {
            val street = address.optJSONArray("address_lines")?.optString(0)?.takeIf { it.isNotBlank() }
            val city = listOfNotNull(
                address.optString("postal_code").takeIf { it.isNotBlank() },
                address.optString("city").takeIf { it.isNotBlank() }?.lowercase()?.replaceFirstChar { it.uppercase() },
            ).joinToString(" ")
            return listOfNotNull(street, city.takeIf { it.isNotBlank() }).joinToString(", ").takeIf { it.isNotBlank() }
        }
    }
}
