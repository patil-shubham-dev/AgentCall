package com.agentcall.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mobile-side battery-optimization / autostart onboarding (ColorOS Doze
 * finding). Standard Android Doze gives data pushes to a woken process, but
 * OEM battery managers (ColorOS Startup Manager, Background Freeze, Sleep
 * Standby, Xiaomi Autostart, ...) defer FCM entirely when the app is killed
 * and the screen is off. There is no code fix for that — the app's job is to
 * walk the user through the one-time exemption.
 *
 * BATTERY-AUDIT NOTE (intentional reliability/battery tradeoff): this
 * exemption also removes Doze throttling for everything else the app does,
 * which AMPLIFIES any background-work bug — an uncapped background poll or
 * a held wake lock drains hardest for exactly the users who completed this
 * flow (2026-08 audit findings H1/H2/M1, fixed in the same pass). Any future
 * background loop must be lifecycle-gated and bounded by design, not left
 * to the OS to suppress.
 *
 * This manager stores the one-shot "shown" flag and a purely local audit log
 * of exemption checks. Nothing here ever leaves the device.
 */
@Singleton
class BatteryOptimizationManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("battery_optimization", Context.MODE_PRIVATE)

    /** The one-time home banner has been shown/dismissed for this install. */
    var onboardingShown: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING_SHOWN, value) }

    /** Last known exemption state, null until first checked. */
    var lastGranted: Boolean?
        get() = if (prefs.contains(KEY_LAST_GRANTED)) prefs.getBoolean(KEY_LAST_GRANTED, false) else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_GRANTED) else putBoolean(KEY_LAST_GRANTED, value)
        }

    /** True when the one-shot onboarding banner should still appear. */
    fun shouldShowBanner(): Boolean = !onboardingShown

    /** Is this app exempt from Android's battery optimization right now? */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Append a timestamped entry to the local audit log (capped at the most
     * recent [LOG_CAP] entries). [granted] is the exemption result where one
     * was measured; null marks an informational event like "opened OEM
     * settings". Local-only by design — never sent to the backend.
     */
    fun logExemptionCheck(granted: Boolean?, source: String) {
        val entry = LogEntry(Instant.now(), granted, source).render()
        val existing = prefs.getStringSet(KEY_LOG, emptySet()).orEmpty()
        val next = (existing + entry).sortedDescending().take(LOG_CAP).toSet()
        prefs.edit { putStringSet(KEY_LOG, next) }
        lastGranted = granted
    }

    /** Most-recent-first copy of the local log. */
    fun recentLogs(): List<String> =
        prefs.getStringSet(KEY_LOG, emptySet()).orEmpty().sortedDescending()

    /** Debug hook for the forced-fallback on-device test: when set, the OEM
     *  deep-link branch is skipped so the app-details fallback is exercised. */
    @Volatile
    var debugForceDeepLinkFailure: Boolean = false

    /** OEM-specific guidance for this device, or the generic fallback. */
    fun oemInfo(): OemInfo = OemMatcher.match(Build.MANUFACTURER)

    data class LogEntry(
        val at: Instant,
        val granted: Boolean?,
        val source: String,
    ) {
        /** ISO-8601 | granted=true/false/n/a | source — sorted lexically below. */
        fun render(): String = "$at|${when (granted) {
            true -> "granted=true"
            false -> "granted=false"
            null -> "granted=n/a"
        }}|$source"
    }

    companion object {
        const val KEY_ONBOARDING_SHOWN = "onboarding_shown"
        const val KEY_LAST_GRANTED = "last_granted"
        const val KEY_LOG = "local_log"
        const val LOG_CAP = 50
    }
}

/** A device's manufacturer guidance: display name, instructions, the
 *  dontkillmyapp.com URL, and ordered OEM deep-link candidates (component
 *  strings "package/activity") to try before the app-details fallback. */
data class OemInfo(
    val slug: String,
    val displayName: String,
    val instructions: String,
    val supportUrl: String,
    val deepLinkCandidates: List<String>,
)

object OemMatcher {

    /** Pure manufacturer -> guidance mapping, unit-testable without Android. */
    fun match(manufacturer: String): OemInfo {
        val m = manufacturer.trim().lowercase()
        val xiaomi = OemInfo(
            slug = "xiaomi",
            displayName = "Xiaomi / Redmi / POCO (MIUI/HyperOS)",
            instructions = "In the Xiaomi settings: open Security > App permissions > Autostart and " +
                "enable AgentCall; under Battery > Battery Saver set AgentCall to \"No restrictions\"; " +
                "and lock the app in the recents screen so it isn't swept away.",
            supportUrl = "https://dontkillmyapp.com/xiaomi",
            deepLinkCandidates = listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        val coloros = OemInfo(
            slug = "oppo",
            displayName = "Oppo / Realme / OnePlus (ColorOS/OxygenOS)",
            instructions = "In the ColorOS settings: open App Management > AgentCall and enable " +
                "Auto-start; turn off Background Freeze; and disable Sleep Standby optimization so " +
                "killed apps still receive calls.",
            supportUrl = "https://dontkillmyapp.com/oppo",
            deepLinkCandidates = listOf(
                "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oneplus.security/com.oneplus.security.chainwizard.ChainWizardActivity",
            ),
        )
        val vivo = OemInfo(
            slug = "vivo",
            displayName = "Vivo / iQOO (Funtouch/OriginOS)",
            instructions = "In the Vivo settings: open Battery > Background power consumption and set " +
                "AgentCall to \"Allow background high battery usage\"; then open Settings > More " +
                "settings > Autostart and enable AgentCall.",
            supportUrl = "https://dontkillmyapp.com/vivo",
            deepLinkCandidates = listOf(
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        )
        val samsung = OemInfo(
            slug = "samsung",
            displayName = "Samsung (One UI)",
            instructions = "In the Samsung settings: open Battery > Background usage limits and set " +
                "AgentCall to \"Unrestricted\"; also allow the app in Settings > Apps > AgentCall > " +
                "Battery > Background activity.",
            supportUrl = "https://dontkillmyapp.com/samsung",
            deepLinkCandidates = emptyList(),
        )
        val huawei = OemInfo(
            slug = "huawei",
            displayName = "Huawei / Honor (EMUI/Magic UI)",
            instructions = "In the Huawei/Honor settings: open App Launch and set AgentCall to \"Manage " +
                "manually\" with all toggles on; then disable Battery optimization for AgentCall so it " +
                "can wake for calls.",
            supportUrl = "https://dontkillmyapp.com/huawei",
            deepLinkCandidates = listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.startupapp.StartupAppListActivity",
            ),
        )
        val generic = OemInfo(
            slug = "",
            displayName = m.ifBlank { "this phone" },
            instructions = "Phone manufacturers add their own background-management layers on top of " +
                "Android. Make sure AgentCall is allowed to auto-start, run in the background, and is " +
                "excluded from any aggressive battery saver so calls can wake the app.",
            supportUrl = "https://dontkillmyapp.com",
            deepLinkCandidates = emptyList(),
        )

        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> xiaomi
            m.contains("oppo") || m.contains("realme") -> coloros
            m.contains("oneplus") -> coloros.copy(
                displayName = "OnePlus (OxygenOS)",
                instructions = "In the OnePlus settings: open Battery > Battery optimization and set " +
                    "AgentCall to \"Don't optimize\"; enable Autostart; and lock the app in recents.",
            )
            m.contains("vivo") || m.contains("iqoo") -> vivo
            m.contains("samsung") -> samsung
            m.contains("huawei") || m.contains("honor") -> huawei
            else -> generic
        }
    }
}