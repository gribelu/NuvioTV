package com.nuvio.tv.core.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSelectionTimeoutTest {

    private val minuteMs = 60_000L
    private val backgroundedAt = 1_000_000_000L

    private fun expiredAfter(timeoutMinutes: Int, awayMs: Long) =
        ProfileSelectionTimeout.hasExpired(
            timeoutMinutes = timeoutMinutes,
            backgroundedAtMs = backgroundedAt,
            nowMs = backgroundedAt + awayMs
        )

    @Test
    fun `a disabled timeout never expires`() {
        assertFalse(expiredAfter(ProfileSelectionTimeout.DISABLED_MINUTES, awayMs = 7 * 24 * 60 * minuteMs))
    }

    @Test
    fun `a short trip to another app keeps the profile`() {
        assertFalse(expiredAfter(timeoutMinutes = 30, awayMs = 10 * minuteMs))
        assertFalse(expiredAfter(timeoutMinutes = 30, awayMs = 30 * minuteMs - 1))
    }

    @Test
    fun `being away for the timeout or longer expires`() {
        assertTrue(expiredAfter(timeoutMinutes = 30, awayMs = 30 * minuteMs))
        assertTrue(expiredAfter(timeoutMinutes = 1440, awayMs = 36 * 60 * minuteMs))
    }

    @Test
    fun `nothing expires while the app was never backgrounded`() {
        assertFalse(
            ProfileSelectionTimeout.hasExpired(timeoutMinutes = 10, backgroundedAtMs = null, nowMs = backgroundedAt)
        )
    }

    @Test
    fun `a clock that moved backwards expires`() {
        assertTrue(expiredAfter(timeoutMinutes = 1440, awayMs = -minuteMs))
    }

    @Test
    fun `every offered option fits the millisecond arithmetic`() {
        ProfileSelectionTimeout.OPTIONS_MINUTES.filter { it > 0 }.forEach { minutes ->
            assertFalse(expiredAfter(minutes, awayMs = minutes * minuteMs - 1))
            assertTrue(expiredAfter(minutes, awayMs = minutes * minuteMs))
        }
    }
}
