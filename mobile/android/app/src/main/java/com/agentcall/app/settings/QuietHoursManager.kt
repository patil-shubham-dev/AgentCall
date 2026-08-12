package com.agentcall.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.agentcall.app.call.agentSlug
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mobile-side quiet hours / DND (backlog item 6).
 *
 * Rules are stored as wall-clock minutes since local midnight (the backlog's
 * documented convention: \"minutes since local midnight\" per profile). A rule
 * where start > end wraps across midnight (e.g. 22:00 -> 07:00). Per-agent
 * rules override the global rule. Everything lives in SharedPreferences, so
 * quiet hours survive app restarts — and because the backend is stateless
 * (in-memory), nothing is ever sent server-side. The AI learns about quiet
 * hours only through the decline/cancel note on an actual call; it is never
 * assumed and never pushed.
 *
 * DST caveat (documented in IMPROVEMENT_BACKLOG.md): rules are interpreted in
 * the device's local time, so a DST shift moves the effective window by the
 * offset change. Accepted for v1.
 */
@Singleton
class QuietHoursManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quiet_hours", Context.MODE_PRIVATE)

    var globalEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_GLOBAL_ENABLED, value) }

    var allowVibrations: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_VIBRATIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_VIBRATIONS, value) }

    val globalStartMinutes: Int
        get() = prefs.getInt(KEY_GLOBAL_START, DEFAULT_START_MINUTES)

    val globalEndMinutes: Int
        get() = prefs.getInt(KEY_GLOBAL_END, DEFAULT_END_MINUTES)

    fun setGlobalRange(startMinutes: Int, endMinutes: Int) {
        prefs.edit {
            putInt(KEY_GLOBAL_START, startMinutes.coerceIn(0, MINUTES_PER_DAY - 1))
            putInt(KEY_GLOBAL_END, endMinutes.coerceIn(0, MINUTES_PER_DAY - 1))
        }
    }

    /** Per-agent override. Presence of the keys means the agent has a rule. */
    fun agentRange(agentId: String): Pair<Int, Int>? {
        val id = agentId.agentSlug()
        val startKey = agentKey(id, KEY_AGENT_START)
        val endKey = agentKey(id, KEY_AGENT_END)
        if (!prefs.contains(startKey) || !prefs.contains(endKey)) return null
        return prefs.getInt(startKey, 0) to prefs.getInt(endKey, 0)
    }

    fun setAgentRange(agentId: String, startMinutes: Int, endMinutes: Int) {
        val id = agentId.agentSlug()
        prefs.edit {
            putInt(agentKey(id, KEY_AGENT_START), startMinutes.coerceIn(0, MINUTES_PER_DAY - 1))
            putInt(agentKey(id, KEY_AGENT_END), endMinutes.coerceIn(0, MINUTES_PER_DAY - 1))
        }
    }

    fun clearAgentRange(agentId: String) {
        val id = agentId.agentSlug()
        prefs.edit {
            remove(agentKey(id, KEY_AGENT_START))
            remove(agentKey(id, KEY_AGENT_END))
        }
    }

    /**
     * Effective rule for an agent: per-agent range if configured, else the
     * global range when global quiet hours are enabled.
     */
    fun activeRange(agentId: String): Pair<Int, Int>? {
        agentRange(agentId)?.let { return it }
        return if (globalEnabled) globalStartMinutes to globalEndMinutes else null
    }

    /** Is it currently quiet for this agent? */
    fun isQuietNow(agentId: String, now: LocalTime = LocalTime.now()): Boolean {
        val (start, end) = activeRange(agentId) ?: return false
        return minutesInRange(now.toSecondOfDay() / 60, start, end)
    }

    fun isQuietBetween(agentId: String, minutes: Int): Boolean {
        val (start, end) = activeRange(agentId) ?: return false
        return minutesInRange(minutes, start, end)
    }

    private fun minutesInRange(minutes: Int, start: Int, end: Int): Boolean =
        isMinutesInRange(minutes, start, end)

    private fun agentKey(agentId: String, suffix: String) = "agent:$agentId:$suffix"

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_START_MINUTES = 22 * 60 // 22:00
        const val DEFAULT_END_MINUTES = 7 * 60 // 07:00

        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        private const val KEY_GLOBAL_START = "global_start_min"
        private const val KEY_GLOBAL_END = "global_end_min"
        private const val KEY_ALLOW_VIBRATIONS = "allow_vibrations"
        private const val KEY_AGENT_START = "start_min"
        private const val KEY_AGENT_END = "end_min"

        fun minutesToLabel(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return String.format("%02d:%02d", h, m)
        }

        /**
         * Pure window check (unit-testable): start < end is a same-day window,
         * start > end wraps across midnight, start == end is disabled.
         */
        fun isMinutesInRange(minutes: Int, start: Int, end: Int): Boolean {
            return if (start == end) {
                // A zero-length window is never "quiet" — treat as disabled.
                false
            } else if (start < end) {
                minutes in start until end
            } else {
                // Overnight window: wraps past midnight.
                minutes >= start || minutes < end
            }
        }
    }
}
