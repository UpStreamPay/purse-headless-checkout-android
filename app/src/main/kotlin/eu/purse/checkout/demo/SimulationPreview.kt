package eu.purse.checkout.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.purse.checkout.PurseCheckoutException
import eu.purse.checkout.PurseHeadlessCheckout
import eu.purse.checkout.payment.item.PrimaryMethod
import eu.purse.core.api.PaymentApiException
import eu.purse.core.payment.LoanProduct
import eu.purse.core.payment.LoanSimulation
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * A BNPL instalment schedule, rendered by the **host app** — this demo's analog of the web dropin's
 * `SimulationPreview.svelte`. The SDK's part stops at the data: `checkout.simulateLoan(method,
 * product)` is web's `method.simulateLoan(product)`, and it answers with a [LoanSimulation] (counts
 * and amounts, no dates). What to show, in which words and currency format, is the merchant's
 * decision — so none of this lives in `:headless-checkout`.
 *
 * Mounted by `PrimaryMethodRow` for a selected method flagged `simulable`, next to whatever else the
 * row renders (a redirection notice, say — the web dropin does the same, `RedirectionText` then
 * `SimulationPreview`). Re-quotes whenever the remaining amount moves, since the schedule is for what
 * this method would finance; a refusal (`INVALID_LOAN_SIMULATION`, or a network failure) shows as a
 * one-line notice rather than an error dialog — a quote is a nicety, paying still goes through
 * `setPrimary` + `submitPayment`.
 */
@Composable
fun SimulationPreview(checkout: PurseHeadlessCheckout, method: PrimaryMethod) {
    val remaining by checkout.remainingAmountToPay.collectAsState()
    var state by remember(method.id) { mutableStateOf<SimulationState>(SimulationState.Loading) }

    LaunchedEffect(method.id, remaining) {
        state = SimulationState.Loading
        state = try {
            SimulationState.Ready(checkout.simulateLoan(method, LoanProduct(remaining, checkout.currency)))
        } catch (e: PurseCheckoutException) {
            // METHOD_IS_NOT_SIMULABLE can't happen behind the `simulable` flag; INVALID_LOAN_SIMULATION
            // means the partner refused this amount (or answered empty) — nothing to schedule.
            SimulationState.Unavailable(e.message)
        } catch (e: PaymentApiException) {
            SimulationState.Unavailable(e.message)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val current = state) {
            SimulationState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.simulation_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            is SimulationState.Unavailable -> Text(
                text = stringResource(R.string.simulation_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is SimulationState.Ready -> Schedule(current.simulation, checkout.currency)
        }
    }
}

/**
 * The schedule laid out as the web dropin does: the first instalment "today", then one line per
 * further instalment — the recurring amount, except the last one which is the final amount —
 * followed by the cost of the credit, the total and the partner's legal wording when it sends any.
 */
@Composable
private fun Schedule(simulation: LoanSimulation, currency: String) {
    val format = remember(currency) { amountFormat(currency) }

    Text(text = stringResource(R.string.simulation_title), style = MaterialTheme.typography.titleSmall)
    HorizontalDivider()
    ScheduleLine(
        label = stringResource(R.string.simulation_instalment, 1) + " · " + stringResource(R.string.simulation_today),
        amount = format.format(simulation.initialInstallmentAmount),
    )
    // `installmentCount` counts the instalments after the first one above; the last is the final amount.
    val lastNumber = simulation.installmentCount + 1
    for (number in 2..lastNumber) {
        val isLast = number == lastNumber
        ScheduleLine(
            label = stringResource(R.string.simulation_instalment, number),
            amount = format.format(if (isLast) simulation.finalInstallmentAmount else simulation.installmentAmount),
        )
    }
    HorizontalDivider()
    ScheduleLine(label = stringResource(R.string.simulation_cost), amount = format.format(simulation.totalCost))
    ScheduleLine(
        label = stringResource(R.string.simulation_total),
        amount = format.format(simulation.totalAmount),
        emphasis = true,
    )
    simulation.aprc?.let { aprc ->
        Text(
            text = stringResource(R.string.simulation_aprc, aprc.stripTrailingZeros().toPlainString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    simulation.legalText?.let { legal ->
        Text(
            text = legal,
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ScheduleLine(label: String, amount: String, emphasis: Boolean = false) {
    val weight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
        Text(text = amount, style = monoStyle, fontWeight = weight)
    }
}

/** `12,50 €`-style formatting for [currency] in the device locale; falls back to the bare code. */
private fun amountFormat(currency: String): NumberFormat =
    NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        runCatching { this.currency = Currency.getInstance(currency) }
    }

private sealed interface SimulationState {
    data object Loading : SimulationState
    data class Ready(val simulation: LoanSimulation) : SimulationState
    data class Unavailable(val reason: String?) : SimulationState
}
