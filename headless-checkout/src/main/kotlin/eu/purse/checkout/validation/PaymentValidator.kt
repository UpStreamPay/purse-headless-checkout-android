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

    suspend fun execute(
        splits: List<PaymentSplit>,
        session: LegacyPaymentSession,
        collectedByMethod: Map<String, Map<String, Any?>> = emptyMap(),
    ): ValidationResult {
        requireDistinctSources(splits)

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
                        collected = collectedByMethod[source.id] ?: emptyMap(),
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

    /**
     * One `/validate` item per split, and never the same split source twice — a duplicate would ask
     * the PSP to charge the same instrument twice in a single call.
     *
     * [PursePaymentSplitter][eu.purse.checkout.payment.split.PursePaymentSplitter] already makes that
     * structurally impossible (at most one primary, plus secondaries in a map keyed by token id), so
     * this is a tripwire, not a recovery path: it exists because [execute] accepts a plain
     * `List<PaymentSplit>` and so cannot rely on that guarantee holding forever.
     *
     * Deliberately keyed on **`source.id`**, not on `(partner, method)`. Two different gift cards from
     * the same partner (two `illicado-giftcard` tokens, say) legitimately contribute two items with
     * identical `partner`/`method` — that's a real co-payment shape web submits too, and deduplicating
     * on the pair would silently drop one card's slice and under-pay the order.
     *
     * @throws PurseCheckoutException.ValidateFailed naming the repeated source.
     */
    private fun requireDistinctSources(splits: List<PaymentSplit>) {
        val repeated = splits.groupingBy { it.source.id }.eachCount().filterValues { it > 1 }.keys
        if (repeated.isNotEmpty()) {
            throw PurseCheckoutException.ValidateFailed(
                "refusing to submit /validate with a repeated split source: ${repeated.joinToString()} — " +
                    "each source must contribute exactly one item",
            )
        }
    }
}
