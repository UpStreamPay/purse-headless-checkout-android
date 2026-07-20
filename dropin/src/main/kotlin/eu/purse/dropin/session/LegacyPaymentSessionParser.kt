package eu.purse.dropin.session

import eu.purse.dropin.vault.CardBrand
import eu.purse.dropin.payment.method.IntegrationMode
import eu.purse.dropin.payment.method.Method
import eu.purse.dropin.payment.method.MethodConfiguration
import eu.purse.dropin.payment.method.MethodSettings
import eu.purse.dropin.payment.partner.Partner
import eu.purse.dropin.vault.PciProxyVaultInfo
import eu.purse.dropin.vault.PurseVaultInfo
import eu.purse.dropin.payment.method.RegistrationMode
import eu.purse.dropin.vault.SessionVault
import eu.purse.dropin.payment.method.VaultInfo
import eu.purse.dropin.vault.VaultVendor
import eu.purse.dropin.vault.VendorFrontData
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Parses `payment_session` into [LegacyPaymentSession]. The only structural validation performed
 * — `id` and `expiration_date` must be present — mirrors web's `checkSessionFormat`
 * intentionally; see docs/architecture/session-and-protocols.md#validation-rules for why nothing
 * stricter is enforced here.
 */
internal object LegacyPaymentSessionParser {

    fun parse(json: JSONObject): LegacyPaymentSession {
        val id = json.optStringOrNull("id")
            ?: throw DropinSessionException.SessionMalformed("id is missing from the session")

        val expirationDateRaw = json.optStringOrNull("expiration_date")
            ?: throw DropinSessionException.SessionMalformed("expiration_date is missing from the session")
        val expirationDate = try {
            Instant.parse(expirationDateRaw)
        } catch (e: DateTimeParseException) {
            throw DropinSessionException.SessionMalformed(
                "expiration_date is not a valid ISO-8601 instant: $expirationDateRaw",
            )
        }

        return LegacyPaymentSession(
            id = id,
            amount = json.optBigDecimalOrNull("amount"),
            informationRequest = if (json.has("information_request")) json.getBoolean("information_request") else null,
            currencyCode = json.optString("currency_code"),
            expirationDate = expirationDate,
            success = json.optStringOrNull("success"),
            failure = json.optStringOrNull("failure"),
            redirection = json.optStringOrNull("redirection"),
            protocols = parseProtocols(json.optJSONObject("protocols")),
            vault = json.optJSONObject("vault")?.let(::parseSessionVault),
        )
    }

    private fun parseProtocols(json: JSONObject?): Map<Partner, Map<Method, MethodEntry>> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<Partner, Map<Method, MethodEntry>>()
        for (partnerKey in json.keys()) {
            val methodsJson = json.optJSONObject(partnerKey) ?: continue
            val methods = mutableMapOf<Method, MethodEntry>()
            for (methodKey in methodsJson.keys()) {
                val entryJson = methodsJson.optJSONObject(methodKey) ?: continue
                val configJson = entryJson.optJSONObject("configurations") ?: continue
                methods[Method.fromWire(methodKey)] = MethodEntry(parseMethodConfiguration(configJson))
            }
            result[Partner.fromWire(partnerKey)] = methods
        }
        return result
    }

    private fun parseMethodConfiguration(json: JSONObject): MethodConfiguration {
        val vaultJson = json.optJSONObject("vault")
        val vault = vaultJson?.let {
            VaultInfo(
                supportedBrands = it.optStringList("supported_brands").map(CardBrand::fromWire),
                vendor = VaultVendor.fromWire(it.optStringOrNull("vendor")),
                tokenScope = it.optStringOrNull("token_scope"),
                externalThreeDsEnabled = it.optBoolean("external_three_ds_enabled", false),
            )
        }

        val settingsJson = json.optJSONObject("settings")
        val merchantSpecificsJson = settingsJson?.optJSONObject("merchant_specifics")
        val settings = MethodSettings(
            registrationMode = RegistrationMode.fromWire(settingsJson?.optStringOrNull("registration_mode")),
            saveToken = settingsJson?.optBoolean("save_token", false) ?: false,
            saveTokenMode = settingsJson?.optStringOrNull("save_token_mode"),
            directDebit = settingsJson?.optBoolean("direct_debit", false) ?: false,
            paymentMinAmount = settingsJson?.optBigDecimalOrNull("payment_min_amount"),
            paymentMaxAmount = settingsJson?.optBigDecimalOrNull("payment_max_amount"),
            usagePerSessionLimit = settingsJson?.optIntOrNull("usage_per_session_limit"),
            integrationMode = IntegrationMode.fromWire(merchantSpecificsJson?.optStringOrNull("integration_mode")),
            merchantClientId = merchantSpecificsJson?.optStringOrNull("client_id"),
        )

        return MethodConfiguration(
            stable = json.optBoolean("stable", false),
            template = json.optStringOrNull("template"),
            labels = json.optStringList("labels"),
            position = json.optIntOrNull("position"),
            vault = vault,
            settings = settings,
        )
    }

    private fun parseSessionVault(json: JSONObject): SessionVault {
        val vendorFrontDataJson = json.optJSONObject("vendor_front_data")
        val purseJson = vendorFrontDataJson?.optJSONObject("purse")
        val pciProxyJson = vendorFrontDataJson?.optJSONObject("pci_proxy")
        return SessionVault(
            vendorFrontData = VendorFrontData(
                purse = purseJson?.optStringOrNull("tenant_id")?.let(::PurseVaultInfo),
                pciProxy = pciProxyJson?.optStringOrNull("merchant_id")?.let(::PciProxyVaultInfo),
            ),
        )
    }
}
