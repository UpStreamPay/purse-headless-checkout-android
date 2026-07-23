package eu.purse.checkout.validation

import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.payment.split.PaymentSplit
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.payload.ValidateItemPayload
import eu.purse.core.payload.ValidationResult
import eu.purse.core.session.LegacyPaymentSession
import eu.purse.partnerui.PartnerUiContext
import eu.purse.partnerui.PluginRegistry

/**
 * The "create payment" pipeline — the Kotlin analog of web's `executePayment`
 * (`headless-checkout-validation.ts`):
 *
 * 1. resolve each split's `(partner, method)` to a [eu.purse.partnerui.PartnerUiPlugin];
 * 2. let each plugin optionally `validateUi`, then build its [ValidateItemPayload] via `preValidation`;
 * 3. POST them all to `/validate` in one call;
 * 4. dispatch the [ValidationResult] back to each plugin's `postValidation` (redirect / 3-D Secure).
 *
 * While no plugins are registered (see [PluginRegistry]), step 1 throws
 * [PurseCheckoutException.NoPluginRegistered] on the first split — the pipeline is fully wired; only
 * the plugin implementations are deferred.
 */
class PaymentValidator(private val api: PaymentApiClient) {

    private class Prepared(
        val plugin: eu.purse.partnerui.PartnerUiPlugin,
        val context: PartnerUiContext,
    )

    suspend fun execute(splits: List<PaymentSplit>, session: LegacyPaymentSession): ValidationResult {
        val prepared = mutableListOf<Prepared>()
        val payloads = mutableListOf<ValidateItemPayload>()

        for (split in splits) {
            val item = split.source
            val plugin = PluginRegistry.getPlugin(item.partner, item.method, session)
                ?: throw PurseCheckoutException.NoPluginRegistered(
                    "no :partner-ui plugin registered for ${item.id} — implement one to complete payment",
                )
            val context = PartnerUiContext(
                partner = item.partner,
                method = item.method,
                session = session,
                amount = split.amount,
                api = api,
            )
            if (!plugin.validateUi(context)) {
                throw PurseCheckoutException.ValidateFailed("client-side validation failed for ${item.id}")
            }
            payloads += plugin.preValidation(context)
            prepared += Prepared(plugin, context)
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
