// Web: packages/headless-checkout/src/session/payment/mops/item-factory/primaries.ts (`simulateLoan`)
// Same: only a `simulable` method answers; `METHOD_IS_NOT_SIMULABLE` / `INVALID_LOAN_SIMULATION`.
// Differs: every API-level refusal (a 409 EMPTY_SIMULATION, say) surfaces as INVALID_LOAN_SIMULATION,
//          the API code kept on the cause; only a transport failure stays a raw PaymentApiException.
package eu.purse.checkout.simulation

import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.monitoring.CheckoutEventBus
import eu.purse.checkout.monitoring.CheckoutEventCode
import eu.purse.checkout.monitoring.monitoringPayload
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.core.api.PaymentApiClient
import eu.purse.core.api.PaymentApiException
import eu.purse.core.payment.LoanProduct
import eu.purse.core.payment.LoanSimulation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** BNPL loan simulation over the entity-scoped `POST /simulation/…` endpoints. */
internal class LoanSimulator(
    private val api: () -> PaymentApiClient,
    private val bus: CheckoutEventBus,
) {
    suspend fun simulate(method: PrimaryMethod, product: LoanProduct): LoanSimulation =
        call(method, product) { getSimulation(method.partner, method.method, product) }

    suspend fun simulateAll(method: PrimaryMethod, product: LoanProduct): List<LoanSimulation> =
        call(method, product) { getSimulationMulti(method.partner, method.method, product) }

    private suspend fun <T> call(method: PrimaryMethod, product: LoanProduct, block: PaymentApiClient.() -> T): T {
        if (!method.simulable) throw PurseCheckoutException.MethodNotSimulable(method.id)
        bus.post(
            CheckoutEventCode.HEADLESS_CHECKOUT_METHOD_LOAN_SIM,
            payload = monitoringPayload(
                "partner" to method.partner.wireValue,
                "method" to method.method.wireValue,
                "amount" to product.amount.toPlainString(),
                "currencyCode" to product.currencyCode,
            ),
        )
        return withContext(Dispatchers.IO) {
            try {
                api().block()
            } catch (e: PaymentApiException) {
                throw e.asLoanSimulationFailureOrSelf()
            }
        }
    }

    private fun PaymentApiException.asLoanSimulationFailureOrSelf(): Exception =
        if (errorCode == PaymentApiClient.INVALID_LOAN_SIMULATION || status != null) {
            PurseCheckoutException.LoanSimulationFailed(
                message ?: "invalid loan simulation${errorCode?.let { " ($it)" } ?: ""}",
                this,
            )
        } else {
            this
        }
}
