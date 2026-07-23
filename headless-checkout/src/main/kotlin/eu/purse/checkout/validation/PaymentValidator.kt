package eu.purse.checkout.validation

import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.checkout.payment.secondary.SecondaryConfigs
import eu.purse.checkout.payment.secondary.SecondaryToken
import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.payload.ItemPayload
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payload.ValidationResult
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PartnerUiPlugin
import eu.purse.partnerui.PluginRegistry

/**
 * The "create payment" pipeline — the Kotlin analog of web's `executePayment`
 * (`headless-checkout-validation.ts`). For each split it builds a [ValidateItemPayload], POSTs them
 * all to `/validate` in one call, then dispatches the result back to any plugins:
 *
 * - **secondary** splits (an applied [SecondaryToken]) build their payload from the method's
 *   `SecondaryConfig` — no plugin involved, matching web's `buildSecondaryValidateItemPayload`;
 * - **primary** splits (a [PrimaryMethod]) resolve a `:partner-ui` plugin, optionally `validateUi`,
 *   then `preValidation` → payload, and `postValidation` after the call.
 *
 * While no primary plugins are registered, a primary split throws
 * [PurseCheckoutException.NoPluginRegistered] — secondaries submit fine on their own.
 */
class PaymentValidator(private val api: PaymentApiClient) {

    private class Prepared(val plugin: PartnerUiPlugin, val context: PartnerUiContext)

    suspend fun execute(splits: List<PaymentSplit>, session: LegacyPaymentSession): ValidationResult {
        val prepared = mutableListOf<Prepared>()
        val payloads = mutableListOf<ValidateItemPayload>()

        for (split in splits) {
            when (val source = split.source) {
                is SecondaryToken -> {
                    val config = SecondaryConfigs.forMethod(source.partner, source.method)
                        ?: throw PurseCheckoutException.NoSecondaryConfig("no secondary config for ${source.id}")
                    payloads += ItemPayload(
                        partner = source.partner,
                        method = source.method,
                        amount = split.amount,
                        paymentData = config.buildValidatePaymentData(source),
                    )
                }

                is PrimaryMethod -> {
                    val plugin = PluginRegistry.getPlugin(source.partner, source.method, session)
                        ?: throw PurseCheckoutException.NoPluginRegistered(
                            "no :partner-ui plugin registered for ${source.id} — implement one to complete payment",
                        )
                    val context = PartnerUiContext(
                        partner = source.partner,
                        method = source.method,
                        session = session,
                        amount = split.amount,
                        api = api,
                    )
                    if (!plugin.validateUi(context)) {
                        throw PurseCheckoutException.ValidateFailed("client-side validation failed for ${source.id}")
                    }
                    payloads += plugin.preValidation(context)
                    prepared += Prepared(plugin, context)
                }

                else -> throw PurseCheckoutException.ValidateFailed("unsupported split source ${source.id}")
            }
        }

        val result = try {
            api.validate(payloads)
        } catch (e: Exception) {
            throw PurseCheckoutException.ValidateFailed("validate call failed: ${e.message}", e)
        }

        prepared.forEach { it.plugin.postValidation(result, it.context) }
        return result
    }
}
