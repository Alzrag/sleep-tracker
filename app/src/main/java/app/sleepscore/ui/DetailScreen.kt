package app.sleepscore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sleepscore.data.EpochRow
import app.sleepscore.data.Graph
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(id: Long, onBack: () -> Unit) {
    val session by Graph.repo.observeSession(id).collectAsStateWithLifecycle(initialValue = null)
    val epochs by produceState(initialValue = emptyList<EpochRow>(), id) {
        value = Graph.repo.getEpochs(id)
    }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    val s = session
    if (s == null) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.text) }
            Dim("This night is no longer available.")
        }
        return
    }

    val noSleep = s.totalSleepMin < 10
    val minutes = s.stages.length
    val movement = FloatArray(minutes)
    val noise = FloatArray(minutes) { -1f }
    for (e in epochs) {
        if (e.minuteIndex in 0 until minutes) {
            movement[e.minuteIndex] = e.movement
            noise[e.minuteIndex] = e.noiseAvg
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.text) }
            Column(Modifier.weight(1f)) {
                Text(
                    fmtDay(s.wakeMs.takeIf { it > 0 } ?: s.startMs),
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.text,
                    fontWeight = FontWeight.Bold
                )
                Dim("${fmtTime(s.startMs)} to ${fmtTime(s.endMs ?: s.wakeMs)}")
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, "Delete this night", tint = Palette.dim)
            }
        }

        Panel {
            if (noSleep) {
                SectionTitle("No sleep detected")
                Dim("The phone was moved or the screen was on for almost the whole session, so there wasn't a stretch of stillness to count as sleep. You can delete this entry with the trash icon.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScoreRing(s.score, caption = scoreLabel(s.score))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(fmtDur(s.totalSleepMin), style = MaterialTheme.typography.headlineSmall, color = Palette.text, fontWeight = FontWeight.Bold)
                        val goal = if (s.goalMin > 0) s.goalMin else 510
                        val pct = (s.totalSleepMin * 100f / goal).toInt()
                        Text("$pct% of your ${fmtDur(goal)} goal", color = Palette.dim, style = MaterialTheme.typography.bodyMedium)
                        Dim("Fell asleep ${fmtTime(s.onsetMs)}, woke ${fmtTime(s.wakeMs)}")
                    }
                }
            }
        }

        if (!noSleep) {
            Panel {
                SectionTitle("Sleep stages")
                Spacer(Modifier.height(8.dp))
                Hypnogram(s.stages, s.startMs)
                Spacer(Modifier.height(14.dp))
                val total = s.totalSleepMin.coerceAtLeast(1)
                StageBar(s.deepMin, s.lightMin, s.remMin, s.wasoMin)
                Spacer(Modifier.height(6.dp))
                StageLegend()
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Deep", "${fmtDur(s.deepMin)}  (${s.deepMin * 100 / total}%)")
                KeyValueRow("Light", "${fmtDur(s.lightMin)}  (${s.lightMin * 100 / total}%)")
                KeyValueRow("REM", "${fmtDur(s.remMin)}  (${s.remMin * 100 / total}%)")
                KeyValueRow("Awake during the night", fmtDur(s.wasoMin))
            }

            Panel {
                SectionTitle("The night in numbers")
                Spacer(Modifier.height(6.dp))
                KeyValueRow("Time in bed", fmtDur(s.timeInBedMin))
                KeyValueRow("Time to fall asleep", "about ${fmtDur(s.latencyMin)}")
                KeyValueRow("Sleep efficiency", "${(s.efficiency * 100).toInt()}%")
                KeyValueRow("Awakenings (3+ min)", "${s.awakenings}")
                if (s.micEnabled) {
                    KeyValueRow("Noise disturbances", "${s.noiseEvents}")
                    if (s.avgNoiseDb >= 0f) KeyValueRow("Average room noise", "%.0f dB (relative)".format(s.avgNoiseDb))
                } else {
                    KeyValueRow("Noise", "microphone was off")
                }
            }

            Panel {
                SectionTitle("How the score adds up")
                Dim("Half of the score is how much of your goal you slept. The rest is the quality of that sleep.")
                Spacer(Modifier.height(8.dp))
                PointsBar("Duration", s.sDuration, 50)
                PointsBar("Efficiency", s.sEfficiency, 12)
                PointsBar("Deep sleep", s.sDeep, 10)
                PointsBar("REM sleep", s.sRem, 10)
                PointsBar("Staying asleep", s.sContinuity, 10)
                PointsBar("Falling asleep", s.sLatency, 4)
                PointsBar("Quiet room", s.sEnvironment, 4)
            }

            Panel {
                SectionTitle("What the phone noticed")
                Spacer(Modifier.height(8.dp))
                Dim("Movement (taller = more restless)")
                Spacer(Modifier.height(4.dp))
                MovementChart(movement.toList())
                if (s.micEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Dim("Room noise")
                    Spacer(Modifier.height(4.dp))
                    NoiseChart(noise.toList())
                }
            }

            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Count this as a nap", color = Palette.text, style = MaterialTheme.typography.bodyLarge)
                        Dim("Naps are left out of your average score and counted in total sleep per day.")
                    }
                    Switch(checked = s.isNap, onCheckedChange = { scope.launch { Graph.repo.setNap(s.id, it) } })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this night?") },
            text = { Text("It will be removed from your history and stats.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { Graph.repo.delete(s.id) }
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}
