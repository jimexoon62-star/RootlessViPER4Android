package me.timschneeberger.rootlessjamesdsp.utils
import org.junit.Assert.*
import org.junit.Test
class RecoveryBudgetTest {
    @Test fun failedRecoveriesCannotLoop() {
        val budget = RecoveryBudget()
        assertTrue(budget.take())
        repeat(100) { assertFalse(budget.take()) }
    }
}
