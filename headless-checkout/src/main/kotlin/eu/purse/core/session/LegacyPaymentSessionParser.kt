// Web: packages/model session decode
// Same: same JSON paths.
// Differs: hand-written parser over org.json.
package eu.purse.core.session

import eu.purse.core.payment.IntegratedToken
import eu.purse.core.payment.IntegrationMode
import eu.purse.core.payment.Method
import eu.purse.core.payment.MethodConfiguration
import eu.purse.core.payment.MethodSettings
import eu.purse.core.payment.Partner
import eu.purse.core.payment.RegistrationMode
import eu.purse.core.payment.TokenSource
import eu.purse.core.payment.VaultInfo
import eu.purse.core.vault.CardBrand
import eu.purse.core.vault.PciProxyVaultInfo
import eu.purse.core.vault.PurseVaultInfo
import eu.purse.core.vault.SessionVault
import eu.purse.core.vault.VaultVendor
import eu.purse.core.vault.VendorFrontData
import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONObject

/**
 * Parses `payment_session` into [LegacyPaymentSession]. The only structural validation performed
 * — `id` and `expiration_date` must be present — mirrors web's `checkSessionFormat`
 * intentionally; see docs/architecture/headless-checkout.md for why nothing
 * stricter is enforced here.
 */
internal object LegacyPaymentSessionParser {

    fun parse(json: JSONObject): LegacyPaymentSession {
        val id = json.optStringOrNull("id")
            ?: throw PurseSessionException.SessionMalformed("id is missing from the session")

        val expirationDateRaw = json.optStringOrNull("expiration_date")
            ?: throw PurseSessionException.SessionMalformed("expiration_date is missing from the session")
        val expirationDate = try {
            Instant.parse(expirationDateRaw)
        } catch (e: DateTimeParseException) {
            throw PurseSessionException.SessionMalformed(
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
                methods[Method.fromWire(methodKey)] = MethodEntry(
                    configurations = parseMethodConfiguration(configJson),
                    integratedTokens = parseIntegratedTokens(entryJson.optJSONArray("integrated_tokens")),
                )
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
            // Nullable on purpose — web's resolveSaveTokenMode treats an explicit `false`
            // differently from an absent value.
            saveToken = settingsJson?.takeIf { it.has("save_token") }?.optBoolean("save_token"),
            saveTokenMode = settingsJson?.optStringOrNull("save_token_mode"),
            directDebit = settingsJson?.optBoolean("direct_debit", false) ?: false,
            paymentMinAmount = settingsJson?.optBigDecimalOrNull("payment_min_amount"),
            paymentMaxAmount = settingsJson?.optBigDecimalOrNull("payment_max_amount"),
            usagePerSessionLimit = settingsJson?.optIntOrNull("usage_per_session_limit"),
            integrationMode = IntegrationMode.fromWire(merchantSpecificsJson?.optStringOrNull("integration_mode")),
            merchantClientId = merchantSpecificsJson?.optStringOrNull("client_id"),
            payTokenNoCvv = merchantSpecificsJson?.optBoolean("pay_token_no_cvv", false) ?: false,
            networkToken = settingsJson?.optBoolean("network_token", false) ?: false,
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

    private fun parseIntegratedTokens(json: org.json.JSONArray?): List<IntegratedToken> {
        if (json == null) return emptyList()
        val tokens = mutableListOf<IntegratedToken>()
        for (i in 0 until json.length()) {
            val tokenJson = json.optJSONObject(i) ?: continue
            val id = tokenJson.optStringOrNull("id") ?: continue
            val description = tokenJson.optJSONObject("description")
            tokens += IntegratedToken(
                id = id,
                value = tokenJson.optStringOrNull("value") ?: continue,
                uniquenessToken = tokenJson.optStringOrNull("uniqueness_token"),
                pinCode = tokenJson.optStringOrNull("pin_code"),
                expirationDate = tokenJson.optStringOrNull("expiration_date"),
                favorite = tokenJson.optBoolean("favorite", false),
                name = tokenJson.optStringOrNull("name"),
                source = TokenSource.fromWire(tokenJson.optStringOrNull("source")),
                createdAt = tokenJson.optStringOrNull("created_at"),
                brandName = description?.optStringOrNull("brand_name"),
                displayToken = description?.optStringOrNull("display_token"),
                holderName = description?.optStringOrNull("holder_name"),
                maskedPan = description?.optStringOrNull("masked_pan"),
                networkTokenAvailable = tokenJson.optJSONObject("network_token")
                    ?.optBoolean("available", false) ?: false,
            )
        }
        return tokens
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
