package app.sleepscore.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sleepscore.data.Graph
import app.sleepscore.data.SleepSession
import app.sleepscore.data.TrackingState
import app.sleepscore.service.TrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun TonightScreen(
    sessions: List<SleepSession>,
    active: SleepSession?,
    onOpenSession: (Long) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by TrackingState.running.collectAsStateWithLifecycle()
    val live by TrackingState.live.collectAsStateWithLifecycle()
    val micEnabled by Graph.prefs.micEnabled.collectAsStateWithLifecycle()
    val goalMin by Graph.prefs.goalMin.collectAsStateWithLifecycle()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active?.id) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Start whatever the answer was. Without the microphone permission the app simply
        // tracks movement, light and screen use only.
        TrackingService.start(ctx)
    }

    fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun startTracking() {
        val need = ArrayList<String>()
        if (micEnabled && !granted(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) TrackingService.start(ctx) else permLauncher.launch(need.toTypedArray())
    }

    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(greeting, style = MaterialTheme.typography.headlineMedium, color = Palette.text, fontWeight = FontWeight.Bold)

        if (active != null && running) {
            Panel {
                SectionTitle("Tracking your sleep")
                Spacer(Modifier.height(6.dp))
                Text(
                    fmtClock((nowMs - active.startMs) / 1000),
                    style = MaterialTheme.typography.displayMedium,
                    color = Palette.accent,
                    fontWeight = FontWeight.Bold
                )
                Dim("Started ${fmtTime(active.startMs)}. You can lock the phone and put it on your mattress or nightstand.")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        Tile("Noise", if (live.noiseDb >= 0f) "%.0f dB".format(live.noiseDb) else if (active.micEnabled) "..." else "off"),
                        Modifier.weight(1f)
                    )
                    StatTile(
                        Tile("Movement", when {
                            live.minutes == 0 -> "..."
                            live.movement < 0.5f -> "Still"
                            live.movement < 4f -> "Restless"
                            else -> "Active"
                        }),
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { TrackingService.stop(ctx) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.wake, contentColor = Palette.bg)
                ) { Text("I'm awake: stop and score my sleep", fontWeight = FontWeight.SemiBold) }
            }
        } else if (active != null) {
            // The app was closed or the phone restarted while a night was being recorded.
            Panel {
                SectionTitle("Unfinished session found")
                Dim("A night that started ${fmtDay(active.startMs)} at ${fmtTime(active.startMs)} was interrupted. You can keep tracking it or score what was recorded.")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { startResume(ctx) }, modifier = Modifier.weight(1f)) { Text("Resume") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val last = Graph.repo.lastMinuteIndex(active.id)
                                val end = if (last == null) nowMs else active.startMs + (last + 1) * 60_000L
                                Graph.repo.finishSession(active.id, end)
                                Graph.prefs.activeSessionId = -1L
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Score it now") }
                }
            }
        } else {
            Panel {
                SectionTitle("Ready for bed?")
                Dim("Tap start, put the phone face down on your mattress or nightstand, and ideally plug it in. In the morning tap stop to see your score.")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Listen for noise", color = Palette.text, style = MaterialTheme.typography.bodyLarge)
                        Dim("Measures loudness only. Audio is never recorded or saved.")
                    }
                    Switch(checked = micEnabled, onCheckedChange = { Graph.prefs.setMic(it) })
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { startTracking() },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("Start sleep tracking", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(6.dp))
                Dim("Goal: ${fmtDur(goalMin)} of sleep. Change it with the gear icon.")
            }
        }

        val last = sessions.firstOrNull { it.analyzed }
        if (last != null) {
            Panel {
                SectionTitle(if (last.isNap) "Last sleep (nap)" else "Last night")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScoreRing(last.score, caption = scoreLabel(last.score))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(fmtDur(last.totalSleepMin), style = MaterialTheme.typography.headlineSmall, color = Palette.text, fontWeight = FontWeight.Bold)
                        val pct = (last.totalSleepMin * 100f / (if (last.goalMin > 0) last.goalMin else 1)).toInt()
                        Text("$pct% of your ${fmtDur(last.goalMin)} goal", color = Palette.dim, style = MaterialTheme.typography.bodyMedium)
                        Dim("${fmtTime(last.startMs)} to ${fmtTime(last.wakeMs)}")
                    }
                }
                Spacer(Modifier.height(12.dp))
                StageBar(last.deepMin, last.lightMin, last.remMin)
                Spacer(Modifier.height(6.dp))
                StageLegend()
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { onOpenSession(last.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("See the full night")
                }
            }
        } else if (active == null) {
            Panel {
                SectionTitle("No nights yet")
                Dim("Your first score will show up here after your first tracked sleep.")
            }
        }

        Dim("Sleep stages are estimates from movement, noise and light. A phone can't measure brain activity, so treat light, deep and REM as a good guess, not a medical result.")
        Spacer(Modifier.height(8.dp))
    }
}

private fun startResume(ctx: android.content.Context) {
    // A null-action start is treated by the service as "pick up the unfinished night".
    ctx.startForegroundService(android.content.Intent(ctx, TrackingService::class.java))
}
