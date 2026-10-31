package app.sleepscore

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.sleepscore.data.Graph
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
        }
        // Keep only the last 60 days of history.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { Graph.repo.pruneOld() }
    }
}
