package eu.purse.checkout

import org.json.JSONObject
import java.util.Base64

/**
 * A base64 `widget.data` fixture for the headless-checkout tests — a purse-vendor CB primary (with
 * a card vault), a PayPal wallet primary carrying `merchant_specifics.integration_mode: popup`, an
 * Adyen Google Pay primary, and an Illicado gift-card secondary. Amount 4.80 EUR.
 */
internal object CheckoutTestSessions {

    private val samplePaymentSessionJson = """
        {
          "id": "session-123",
          "amount": 4.80,
          "currency_code": "EUR",
          "expiration_date": "2999-01-01T00:00:00Z",
          "protocols": {
            "purse": {
              "creditcard": {
                "configurations": {
                  "stable": true, "template": "primary",
                  "labels": ["purse", "creditcard", "primary"], "position": 1,
                  "vault": {
                    "supported_brands": ["CARTE_BANCAIRE", "VISA", "MASTERCARD"],
                    "vendor": "purse", "token_scope": "tenant", "external_three_ds_enabled": true
                  },
                  "settings": {
                    "registration_mode": "ON_SESSION", "payment_min_amount": 0.01,
                    "save_token": true, "save_token_mode": "PREFER"
                  }
                },
                "integrated_tokens": [
                  {
                    "id": "tok-1", "value": "tok-value-1", "uniqueness_token": "uniq-1",
                    "expiration_date": "2027-08-31T00:00:00Z", "favorite": true, "name": "my visa",
                    "source": "MERCHANT", "created_at": "2026-01-01T00:00:00Z",
                    "description": {
                      "brand_name": "VISA", "display_token": "VISA •••• 4242",
                      "holder_name": "J DOE", "masked_pan": "424242xxxxxx4242"
                    },
                    "network_token": { "available": true }
                  }
                ]
              }
            },
            "paypal": {
              "wallet": {
                "configurations": {
                  "stable": true, "labels": ["paypal", "wallet", "primary"], "position": 2,
                  "settings": {
                    "registration_mode": "ON_SESSION",
                    "merchant_specifics": { "integration_mode": "popup", "client_id": "paypal-client-abc" }
                  }
                }
              }
            },
            "adyen": {
              "googlepay": {
                "configurations": {
                  "stable": true, "labels": ["adyen", "googlepay", "primary"], "position": 3,
                  "settings": { "registration_mode": "OFF_SESSION" }
                }
              }
            },
            "illicado": {
              "giftcard": {
                "configurations": {
                  "stable": true, "template": "secondary",
                  "labels": ["illicado", "giftcard", "secondary"],
                  "vault": { "vendor": "none" },
                  "settings": { "registration_mode": "OFF_SESSION", "payment_min_amount": 0.01 }
                }
              }
            }
          },
          "vault": {
            "vendor_front_data": {
              "purse": { "tenant_id": "tenant-abc" },
              "pci_proxy": { "merchant_id": "merchant-xyz" }
            }
          }
        }
    """.trimIndent()

    fun widgetData(paymentSessionJson: String = samplePaymentSessionJson): String {
        val root = JSONObject()
            .put("api_key", "key-123")
            .put("api_url", "https://api.purse-sandbox.com/payment/v1")
            .put("environment", "sandbox")
            .put("entity_id", "entity-1")
            .put("payment_session", JSONObject(paymentSessionJson))
        return Base64.getEncoder().encodeToString(root.toString().toByteArray())
    }
}
