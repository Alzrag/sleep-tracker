package app.sleepscore.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.sleepscore.R
import app.sleepscore.data.EpochRow
import app.sleepscore.data.Graph
import app.sleepscore.data.LiveReading
import app.sleepscore.data.TrackingState
import app.sleepscore.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Foreground service that listens passively while you sleep.
 *
 * Every minute it condenses what it saw into ONE row of numbers (movement, noise level, light,
 * screen time). Microphone audio is only ever turned into a loudness number in memory and is
 * thrown away immediately; no audio is written to disk or sent anywhere.
 */
class TrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "app.sleepscore.START"
        const val ACTION_STOP = "app.sleepscore.STOP"
        private const val NOTIF_ID = 1
        private const val MOVE_FLOOR = 0.04f              // per-sample jitter below this is sensor noise
        private const val MAX_SESSION_MS = 16L * 3600_000L // safety net if you forget to stop it
        private const val SAMPLE_RATE = 8000

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null

    // session state (touched on the handler thread)
    @Volatile private var tracking = false
    private var sessionId = -1L
    private var startMs = 0L
    private var minuteIndex = 0
    private var micOn = false

    // per-minute accumulators, guarded by [lock]
    private val lock = Any()
    private var hasLast = false
    private var lx = 0f; private var ly = 0f; private var lz = 0f
    private var moveAcc = 0f
    private var noiseSum = 0.0
    private var noiseN = 0
    private var noiseMax = 0f
    private var currentLux = -1f
    private var screenOnSince = -1L
    private var screenMs = 0L

    @Volatile private var micRunning = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> if (screenOnSince < 0) screenOnSince = now
                    Intent.ACTION_SCREEN_OFF -> if (screenOnSince >= 0) {
                        screenMs += now - screenOnSince
                        screenOnSince = -1L
                    }
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!tracking) return
            val now = System.currentTimeMillis()
            if (now - startMs > MAX_SESSION_MS) {
                stopTracking()
                return
            }
            val idx = ((now - startMs + 1000L) / 60_000L).toInt()
            if (idx > minuteIndex) {
                val row = buildEpoch(minuteIndex)
                persist(row)
                minuteIndex = idx
            }
            val next = startMs + (minuteIndex + 1) * 60_000L
            handler.postDelayed(this, max(1000L, next - now))
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("sleep-sampling").also { it.start() }
        handler = Handler(thread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handler.post { stopTracking() }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                enterForeground(Graph.prefs.micEnabled.value && hasMicPermission())
                handler.post { beginNew() }
            }
            else -> {
                // The system restarted us after the process was killed: pick the night back up.
                enterForeground(Graph.prefs.micEnabled.value && hasMicPermission())
                handler.post { resume() }
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ lifecycle

    private fun beginNew() {
        if (tracking) return
        val mic = Graph.prefs.micEnabled.value && hasMicPermission()
        val now = System.currentTimeMillis()
        scope.launch {
            val id = Graph.repo.startSession(mic, now)
            Graph.prefs.activeSessionId = id
            handler.post { beginSampling(id, now, mic) }
        }
    }

    private fun resume() {
        if (tracking) return
        scope.launch {
            val id = Graph.prefs.activeSessionId
            val s = if (id >= 0) Graph.repo.getSession(id) else null
            if (s == null || s.endMs != null) {
                Graph.prefs.activeSessionId = -1L
                withContext(Dispatchers.Main) { shutDown() }
            } else {
                handler.post { beginSampling(s.id, s.startMs, s.micEnabled && hasMicPermission()) }
            }
        }
    }

    private fun beginSampling(id: Long, start: Long, mic: Boolean) {
        if (tracking) return
        sessionId = id
        startMs = start
        micOn = mic
        val now = System.currentTimeMillis()
        minuteIndex = ((now - startMs) / 60_000L).toInt()

        synchronized(lock) {
            hasLast = false; moveAcc = 0f; noiseSum = 0.0; noiseN = 0; noiseMax = 0f
            currentLux = -1f; screenMs = 0L
            val pm = getSystemService(PowerManager::class.java)
            screenOnSince = if (pm.isInteractive) now else -1L
        }

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepScore::tracking").apply {
            setReferenceCounted(false)
            acquire(MAX_SESSION_MS)
        }

        val sm = getSystemService(SensorManager::class.java)
        sensorManager = sm
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, 200_000, handler)
        }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (micOn) startMic()

        tracking = true
        TrackingState.live.value = LiveReading(minutes = minuteIndex)
        TrackingState.running.value = true

        val next = startMs + (minuteIndex + 1) * 60_000L
        handler.postDelayed(tick, max(1000L, next - now))
    }

    private fun stopTracking() {
        if (!tracking) {
            // Nothing running (for example a stray stop). Just make sure we don't linger.
            scope.launch { withContext(Dispatchers.Main) { shutDown() } }
            return
        }
        tracking = false
        handler.removeCallbacks(tick)
        sensorManager?.unregisterListener(this)
        try { unregisterReceiver(screenReceiver) } catch (e: IllegalArgumentException) {}
        micRunning = false

        val id = sessionId
        val endMs = System.currentTimeMillis()
        val lastRow = buildEpoch(minuteIndex)
        scope.launch {
            Graph.repo.addEpoch(lastRow)
            Graph.repo.finishSession(id, endMs)
            Graph.prefs.activeSessionId = -1L
            TrackingState.running.value = false
            TrackingState.live.value = LiveReading()
            withContext(Dispatchers.Main) { shutDown() }
        }
    }

    private fun shutDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tracking = false
        micRunning = false
        handler.removeCallbacksAndMessages(null)
        sensorManager?.unregisterListener(this)
        try { unregisterReceiver(screenReceiver) } catch (e: IllegalArgumentException) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        thread.quitSafely()
        TrackingState.running.value = false
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ sampling

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                synchronized(lock) {
                    if (hasLast) {
                        val dx = x - lx; val dy = y - ly; val dz = z - lz
                        val d = sqrt(dx * dx + dy * dy + dz * dz)
                        if (d > MOVE_FLOOR) moveAcc += d - MOVE_FLOOR
                    }
                    lx = x; ly = y; lz = z; hasLast = true
                }
            }
            Sensor.TYPE_LIGHT -> synchronized(lock) { currentLux = event.values[0] }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Closes out the current minute and returns it as one row. Resets the accumulators. */
    private fun buildEpoch(index: Int): EpochRow {
        val now = System.currentTimeMillis()
        val row: EpochRow
        synchronized(lock) {
            if (screenOnSince >= 0) {
                screenMs += now - screenOnSince
                screenOnSince = now
            }
            val avg = if (micOn && noiseN > 0) (noiseSum / noiseN).toFloat() else -1f
            val peak = if (micOn && noiseN > 0) noiseMax else -1f
            row = EpochRow(
                sessionId = sessionId,
                minuteIndex = index,
                movement = moveAcc,
                noiseAvg = avg,
                noisePeak = peak,
                lux = currentLux,
                screenSec = (screenMs / 1000L).toInt().coerceAtMost(60)
            )
            moveAcc = 0f; noiseSum = 0.0; noiseN = 0; noiseMax = 0f; screenMs = 0L
        }
        TrackingState.live.value = LiveReading(row.noiseAvg, row.movement, row.lux, index + 1)
        return row
    }

    private fun persist(row: EpochRow) {
        scope.launch { Graph.repo.addEpoch(row) }
    }

    private fun startMic() {
        if (!hasMicPermission()) { micOn = false; return }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { micOn = false; return }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, 4096) * 2
            )
        } catch (e: SecurityException) { null } catch (e: IllegalArgumentException) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            micOn = false
            return
        }
        try { rec.startRecording() } catch (e: IllegalStateException) { rec.release(); micOn = false; return }
        micRunning = true
        Thread({
            val buf = ShortArray(2000) // ~250 ms of audio; overwritten on every read, never stored
            try {
                while (micRunning) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
                    val rms = sqrt(sum / n)
                    val db = (20.0 * log10(max(rms, 1.0))).toFloat()
                    synchronized(lock) {
                        noiseSum += db; noiseN++
                        if (db > noiseMax) noiseMax = db
                    }
                }
            } finally {
                try { rec.stop() } catch (e: IllegalStateException) {}
                rec.release()
            }
        }, "sleep-mic").start()
    }

    // ------------------------------------------------------------------ notification

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun enterForeground(mic: Boolean) {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val type = if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(NOTIF_ID, n, type)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Android refused the microphone type (for example the app was in the background). Retry without it.
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, n)
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "tracking")
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }
}
