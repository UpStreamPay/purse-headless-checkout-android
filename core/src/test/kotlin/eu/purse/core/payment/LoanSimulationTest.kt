package eu.purse.core.payment

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class LoanSimulationTest {

    private val pluginResult = JSONObject(
        """
        {
          "initial_instalment_amount": 70.34,
          "instalment_amount": 66.67,
          "final_instalment_amount": 66.66,
          "instalment_number": 3,
          "total_cost": 3.67,
          "effective_annual_percentage_rate": 21.9,
          "legal_text": "Un crédit vous engage",
          "preferred": 1
        }
        """.trimIndent(),
    )

    @Test
    fun `maps plugin_result exactly like web's mapPluginResultToSimulation`() {
        val simulation = LoanSimulation.fromResponse(JSONObject().put("plugin_result", pluginResult))!!

        assertEquals(BigDecimal("70.34"), simulation.initialInstallmentAmount)
        assertEquals(BigDecimal("66.67"), simulation.installmentAmount)
        assertEquals(BigDecimal("66.66"), simulation.finalInstallmentAmount)
        assertEquals(3, simulation.installmentCount)
        assertEquals(BigDecimal("3.67"), simulation.totalCost)
        // (3 - 1) × 66.67 + 70.34 + 66.66 = 270.34
        assertEquals(BigDecimal("270.34"), simulation.totalAmount)
        assertEquals("Un crédit vous engage", simulation.legalText)
        assertEquals(BigDecimal("21.9"), simulation.aprc)
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
