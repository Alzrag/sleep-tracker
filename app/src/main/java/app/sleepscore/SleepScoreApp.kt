package app.sleepscore

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.sleepscore.data.Graph
import app.sleepscore.engine.SleepAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SleepScoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    "tracking",
                    getString(R.string.channel_tracking),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel("reveal", "Morning Pokémon", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        // Keep only the last 60 days of history.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            Graph.repo.pruneOld()
            // After an update that improves the analysis, re-score the nights already saved.
            if (Graph.prefs.analyzerVersion < SleepAnalyzer.VERSION) {
                Graph.repo.reanalyzeAll()
                Graph.prefs.analyzerVersion = SleepAnalyzer.VERSION
            }
        }
    }
}
