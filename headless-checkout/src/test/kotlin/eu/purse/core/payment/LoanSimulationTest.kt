package eu.purse.core.payment

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoanSimulationTest {

    // Real `POST /simulation/{entity}/oney/cb3x` answer (SANDBOX, 480 € order), plus a legal text and
    // a rate so every field is mapped.
    private val pluginResult = JSONObject(
        """
        {
          "instalment_number": 2,
          "initial_instalment_amount": 167.63,
          "instalment_amount": 160.00,
          "final_instalment_amount": 160.00,
          "total_cost": 7.63,
          "effective_annual_percentage_rate": 21.9,
          "legal_text": "Un crédit vous engage",
          "preferred": 1
        }
        """.trimIndent(),
    )

    @Test
    fun `maps plugin_result and sums every instalment`() {
        val simulation = LoanSimulation.fromResponse(JSONObject().put("plugin_result", pluginResult))!!

        assertEquals(BigDecimal("167.63"), simulation.initialInstallmentAmount)
        assertEquals(BigDecimal("160.00"), simulation.installmentAmount)
        assertEquals(BigDecimal("160.00"), simulation.finalInstallmentAmount)
        assertEquals(2, simulation.installmentCount)
        assertEquals(BigDecimal("7.63"), simulation.totalCost)
        // 167.63 + 160.00 + 160.00 = 487.63: three instalments, i.e. the 480 financed + 7.63 of cost.
        assertEquals(BigDecimal("487.63"), simulation.totalAmount)
        assertEquals("Un crédit vous engage", simulation.legalText)
        assertEquals(BigDecimal("21.9"), simulation.aprc)
    }

    @Test
    fun `a longer schedule adds one recurring instalment per count beyond the final one`() {
        val result = JSONObject(pluginResult.toString()).put("instalment_number", 3)

        // 4×: 167.63 + 2 × 160.00 + 160.00.
        assertEquals(BigDecimal("647.63"), LoanSimulation.fromPluginResult(result).totalAmount)
    }

    @Test
    fun `annual_nominal_rate wins over the effective rate for aprc`() {
        val result = JSONObject(pluginResult.toString()).put("annual_nominal_rate", 19.5)

        assertEquals(BigDecimal("19.5"), LoanSimulation.fromPluginResult(result).aprc)
    }

    @Test
    fun `missing optional fields map to null and absent amounts to zero`() {
        val simulation = LoanSimulation.fromPluginResult(JSONObject().put("instalment_number", 4))

        assertNull(simulation.legalText)
        assertNull(simulation.aprc)
        assertEquals(BigDecimal.ZERO, simulation.totalCost)
        assertEquals(BigDecimal("0.00"), simulation.totalAmount)
    }

    @Test
    fun `a response without plugin_result is not a simulation`() {
        assertNull(LoanSimulation.fromResponse(JSONObject().put("status", "KO")))
    }

    @Test
    fun `LoanProduct serializes to the API body`() {
        val json = LoanProduct(BigDecimal("200.00"), "EUR").toJson()

        assertEquals("EUR", json.getString("currency_code"))
        assertEquals(0, BigDecimal("200").compareTo(BigDecimal(json.get("amount").toString())))
    }
}
