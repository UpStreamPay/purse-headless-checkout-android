package eu.purse.core.session

import java.util.Base64
import org.json.JSONObject

/**
 * Fixture matching the shape found in `usp-widget/e2e/tests/widget/mocks/session.ts` — a
 * `purse`-vendor CB entry (in scope), an `aci`/`pci_proxy` CB entry (out of scope but must not
 * break parsing), Google Pay under `adyen`, PayPal `wallet`, and Illicado `giftcard`. `illicado`
 * deliberately has no `position`, to exercise the "undefined sorts last" rule.
 */
internal object TestSessions {

    val samplePaymentSessionJson: String = """
        {
          "id": "session-123",
          "amount": 4.80,
          "currency_code": "EUR",
          "expiration_date": "2026-07-17T12:00:00Z",
          "success": "https://merchant.example/confirmation",
          "failure": "https://merchant.example/confirmation",
          "redirection": "https://merchant.example/fallback-redirect",
          "protocols": {
            "purse": {
              "creditcard": {
                "configurations": {
                  "stable": true,
                  "template": "primary",
                  "labels": ["purse", "creditcard", "primary"],
                  "position": 1,
                  "vault": {
                    "supported_brands": ["CARTE_BANCAIRE", "VISA", "MASTERCARD"],
                    "vendor": "purse",
                    "token_scope": "tenant",
                    "external_three_ds_enabled": true
                  },
                  "settings": {
                    "registration_mode": "ON_SESSION",
                    "save_token": true,
                    "payment_min_amount": 0.01,
                    "payment_max_amount": 99999,
                    "usage_per_session_limit": 1
                  }
                }
              }
            },
            "aci": {
              "creditcard": {
                "configurations": {
                  "stable": true,
                  "template": "primary",
                  "labels": ["aci", "creditcard", "primary"],
                  "position": 2,
                  "vault": {
                    "supported_brands": ["VISA", "MASTERCARD"],
                    "vendor": "pci_proxy"
                  },
                  "settings": { "registration_mode": "ON_SESSION" }
                }
              }
            },
            "adyen": {
              "googlepay": {
                "configurations": {
                  "stable": true,
                  "labels": ["adyen", "googlepay", "primary"],
                  "position": 3,
                  "settings": { "registration_mode": "OFF_SESSION" }
                }
              }
            },
            "paypal": {
              "wallet": {
                "configurations": {
                  "stable": true,
                  "labels": ["paypal", "wallet", "primary"],
                  "position": 4,
                  "settings": {
                    "registration_mode": "ON_SESSION",
                    "usage_per_session_limit": 1,
                    "merchant_specifics": {
                      "integration_mode": "popup",
                      "client_id": "paypal-client-abc"
                    }
                  }
                }
              }
            },
            "illicado": {
              "giftcard": {
                "configurations": {
                  "stable": true,
                  "template": "secondary",
                  "labels": ["illicado", "giftcard", "secondary"],
                  "vault": { "vendor": "none" },
                  "settings": {
                    "registration_mode": "OFF_SESSION",
                    "payment_min_amount": 0.01,
                    "payment_max_amount": 99999,
                    "usage_per_session_limit": 0
                  }
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

    fun widgetData(
        paymentSessionJson: String = samplePaymentSessionJson,
        apiKey: String = "key-123",
        apiUrl: String = "https://api.purse-sandbox.com/payment/v1",
        environment: String? = "sandbox",
        entityId: String = "entity-1",
    ): String {
        val root = JSONObject()
        root.put("api_key", apiKey)
        root.put("api_url", apiUrl)
        if (environment != null) root.put("environment", environment)
        root.put("entity_id", entityId)
        root.put("payment_session", JSONObject(paymentSessionJson))
        return Base64.getEncoder().encodeToString(root.toString().toByteArray())
    }
}
