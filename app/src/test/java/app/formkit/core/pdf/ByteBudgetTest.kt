package app.formkit.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ByteBudgetTest {

    @Test
    fun `page budgets never add up to more than the limit minus overhead`() {
        val weights = listOf(12_345L, 1L, 98_765L, 4_321L)
        val floors = listOf(900L, 800L, 1_200L, 700L)

        val budgets = ByteBudget.split(100_000, weights, floors)!!

        assertTrue(budgets.sum() <= 100_000 - ByteBudget.overhead(4))
        budgets.zip(floors).forEach { (budget, floor) -> assertTrue(budget >= floor) }
    }

    @Test
    fun `busier pages get more bytes`() {
        val budgets = ByteBudget.split(50_000, weights = listOf(1_000L, 3_000L), floors = listOf(700L, 700L))!!

        val extra = 50_000 - ByteBudget.overhead(2) - 1_400
        assertTrue("page 1 got ${budgets[0]}", abs(700 + extra / 4 - budgets[0]) <= 1)
        assertTrue("page 2 got ${budgets[1]}", abs(700 + extra * 3 / 4 - budgets[1]) <= 1)
    }

    @Test
    fun `floors below a JPEG's headers are raised`() {
        val budgets = ByteBudget.split(10_000, weights = listOf(1L, 1L), floors = listOf(10L, 10L))!!

        budgets.forEach { assertTrue(it >= ByteBudget.MIN_PAGE_BYTES) }
        assertEquals(ByteBudget.overhead(2) + 2 * ByteBudget.MIN_PAGE_BYTES, ByteBudget.smallestTotal(listOf(10L, 10L)))
    }

    @Test
    fun `no split when the floors alone don't fit`() {
        val floors = listOf(5_000L, 5_000L, 5_000L)

        assertNull(ByteBudget.split(ByteBudget.smallestTotal(floors) - 1, listOf(1L, 1L, 1L), floors))
        assertTrue(ByteBudget.split(ByteBudget.smallestTotal(floors), listOf(1L, 1L, 1L), floors) != null)
    }

    @Test
    fun `a smaller scale shrinks every page's share`() {
        val weights = listOf(2_000L, 2_000L)
        val floors = listOf(700L, 700L)

        val full = ByteBudget.split(80_000, weights, floors)!!
        val shrunk = ByteBudget.split(80_000, weights, floors, scale = 0.8)!!

        full.zip(shrunk).forEach { (before, after) -> assertTrue(after < before) }
        assertTrue(shrunk.sum() <= ((80_000 - ByteBudget.overhead(2)) * 0.8).toLong())
    }
}
