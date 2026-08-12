package com.agentcall.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tracks whether the app has at least one resumed activity. Used to decide
 * whether a missed-call event deserves a notification (the phone is
 * backgrounded) or can rely on in-app surface alone (backlog item 1).
 *
 * Registered once from AgentCallApp.onCreate. Cheap and process-local; a
 * multi-process app would need a different mechanism, but this app is
 * single-process.
 */
object ForegroundTracker {

    @Volatile
    var isForeground: Boolean = false
        private set

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var resumedActivities = 0

            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                isForeground = resumedActivities > 0
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
                isForeground = resumedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
