package app.formkit.core.monetization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterstitialPolicyTest {

    private val allowed = InterstitialContext(
        isPro = false,
        canRequestAds = true,
        completedOperations = 3,
        lastShownAtMillis = null,
        nowMillis = 1_000_000L,
    )

    @Test
    fun `the third operation of a free install may show one`() {
        assertTrue(InterstitialPolicy.mayShow(allowed))
    }

    @Test
    fun `never on a new install's first two operations`() {
        assertFalse(InterstitialPolicy.mayShow(allowed.copy(completedOperations = 1)))
        assertFalse(InterstitialPolicy.mayShow(allowed.copy(completedOperations = 2)))
    }

    @Test
    fun `never for Pro or without consent to request ads`() {
        assertFalse(InterstitialPolicy.mayShow(allowed.copy(isPro = true)))
        assertFalse(InterstitialPolicy.mayShow(allowed.copy(canRequestAds = false)))
    }

    @Test
    fun `at most one every 90 seconds`() {
        val now = allowed.nowMillis
        assertFalse(InterstitialPolicy.mayShow(allowed.copy(lastShownAtMillis = now - 89_999)))
        assertTrue(InterstitialPolicy.mayShow(allowed.copy(lastShownAtMillis = now - 90_000)))
    }

    @Test
    fun `a clock moved backwards still waits out the gap`() {
        val now = allowed.nowMillis
        assertFalse(InterstitialPolicy.mayShow(allowed.copy(lastShownAtMillis = now + 30_000)))
        assertTrue(InterstitialPolicy.mayShow(allowed.copy(lastShownAtMillis = now + 120_000)))
    }
}
