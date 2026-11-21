package com.nuvio.tv.core.profile

import android.content.Context

/** How long Nuvio may stay off screen before it asks who is watching again. */
object ProfileSelectionTimeout {
    const val DISABLED_MINUTES = 0

    val OPTIONS_MINUTES = listOf(DISABLED_MINUTES, 10, 30, 60, 240, 720, 1440)

    fun hasExpired(timeoutMinutes: Int, backgroundedAtMs: Long?, nowMs: Long): Boolean {
        if (timeoutMinutes <= 0 || backgroundedAtMs == null) return false
        val awayMs = nowMs - backgroundedAtMs
        // A clock that moved backwards (a reboot before the time synced) cannot say how long the
        // app was away, so ask again rather than keep the previous viewer's profile.
        return awayMs < 0 || awayMs >= timeoutMinutes * 60_000L
    }
}

/**
 * Holds the timeout setting and the time the app left the screen.
 *
 * SharedPreferences rather than DataStore: a launch is checked before the activity restores its
 * saved state, and the time written on the way out has to survive the process being killed next.
 */
class ProfileSelectionTimeoutStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("profile_selection_timeout", Context.MODE_PRIVATE)

    var timeoutMinutes: Int
        get() = prefs.getInt(KEY_TIMEOUT_MINUTES, ProfileSelectionTimeout.DISABLED_MINUTES)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_MINUTES, value).apply()

    /** The app left the screen: the timeout runs from now. */
    fun markBackgrounded(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_BACKGROUNDED_AT_MS, nowMs).commit()
    }

    /** The app is on screen, or an external player is showing its playback: nothing to time. */
    fun clearBackgrounded() {
        if (prefs.contains(KEY_BACKGROUNDED_AT_MS)) {
            prefs.edit().remove(KEY_BACKGROUNDED_AT_MS).apply()
        }
    }

    fun hasExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        ProfileSelectionTimeout.hasExpired(
            timeoutMinutes = timeoutMinutes,
            backgroundedAtMs = prefs.getLong(KEY_BACKGROUNDED_AT_MS, -1L).takeIf { it >= 0L },
            nowMs = nowMs
        )

    private companion object {
        const val KEY_TIMEOUT_MINUTES = "timeout_minutes"
        const val KEY_BACKGROUNDED_AT_MS = "backgrounded_at_ms"
    }
}
