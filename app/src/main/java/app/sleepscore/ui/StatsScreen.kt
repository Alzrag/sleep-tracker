package app.sleepscore.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sleepscore.data.Graph
import app.sleepscore.data.SleepSession
import app.sleepscore.data.toSummary
import app.sleepscore.engine.RangeStats
import app.sleepscore.engine.Stats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.max

private val presetLabels = listOf("7 days", "14 days", "30 days", "60 days", "Custom")
private val presetDays = listOf(7, 14, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(sessions: List<SleepSession>) {
    val goalMin by Graph.prefs.goalMin.collectAsStateWithLifecycle()

    var preset by rememberSaveable { mutableStateOf(2) }
    var fromDay by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(29).toEpochDay()) }
    var toDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var showPicker by remember { mutableStateOf(false) }
    var weekdayMetric by rememberSaveable { mutableStateOf(0) } // 0 = hours, 1 = score

    val from = LocalDate.ofEpochDay(fromDay)
    val to = LocalDate.ofEpochDay(toDay)

    val summaries = remember(sessions) {
        sessions.filter { it.analyzed && it.totalSleepMin >= 10 }.map { it.toSummary() }
    }
    val stats = remember(summaries, fromDay, toDay, goalMin) { Stats.compute(summaries, from, to, goalMin) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Stats", style = MaterialTheme.typography.headlineMedium, color = Palette.text, fontWeight = FontWeight.Bold)

        Panel {
            SectionTitle("Date range")
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presetLabels.forEachIndexed { i, label ->
                    FilterChip(
                        selected = preset == i,
                        onClick = {
                            if (i < presetDays.size) {
                                preset = i
                                val today = LocalDate.now()
                                toDay = today.toEpochDay()
                                fromDay = today.minusDays((presetDays[i] - 1).toLong()).toEpochDay()
                            } else {
                                showPicker = true
                            }
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Dim("${fmtDay(from)}  to  ${fmtDay(to)}")
        }

        if (stats == null) {
            Panel {
                SectionTitle("No sleep in this range")
                Dim("Pick a different range, or track a few nights and come back. Stats are grouped by the day you woke up.")
            }
        } else {
            Overview(stats, goalMin)
            Stages(stats)
            Timing(stats)
            Quality(stats)
            Charts(stats, goalMin, weekdayMetric) { weekdayMetric = it }
            if (stats.insights.isNotEmpty()) {
                Panel {
                    SectionTitle("Things worth knowing")
                    Spacer(Modifier.height(6.dp))
                    stats.insights.forEach {
                        Text("•  $it", color = Palette.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showPicker) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = to.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                    onClick = {
                        val s = state.selectedStartDateMillis
                        val e = state.selectedEndDateMillis
                        if (s != null && e != null) {
                            fromDay = Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            toDay = Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            preset = 4
                        }
                        showPicker = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) {
            DateRangePicker(state = state, modifier = Modifier.height(480.dp))
        }
    }
}

@Composable
private fun Overview(st: RangeStats, goalMin: Int) {
    Panel {
        SectionTitle("Averages")
        Dim("${st.nights} night${if (st.nights == 1) "" else "s"} and ${st.naps} nap${if (st.naps == 1) "" else "s"} across ${st.daysWithData} of ${st.daysInRange} days")
        Spacer(Modifier.height(10.dp))
        TileGrid(
            listOf(
                Tile("Sleep score", if (st.nights > 0) "${st.avgScore}" else "-", if (st.nights > 0) "best ${st.bestScore}, worst ${st.worstScore}" else null),
                Tile("Of your goal", if (st.nights > 0) "${st.avgGoalPct}%" else "-", "goal ${fmtDur(goalMin)}"),
                Tile("Sleep per night", if (st.nights > 0) fmtDur(st.avgSleepPerNightMin) else "-", "main sleeps only"),
                Tile("Sleep per day", fmtDur(st.avgSleepPerDayMin), if (st.naps > 0) "includes naps (avg nap ${fmtDur(st.avgNapMin)})" else "includes naps"),
                Tile("Time in bed", if (st.nights > 0) fmtDur(st.avgTimeInBedMin) else "-"),
                Tile("Sleep debt", fmtDur(st.sleepDebtPerDayMin), "per day vs goal")
            )
        )
        if (st.bestScoreDate != null && st.worstScoreDate != null && st.nights > 1) {
            Spacer(Modifier.height(8.dp))
            Dim("Best night: woke ${fmtDay(st.bestScoreDate)}. Roughest: woke ${fmtDay(st.worstScoreDate)}.")
        }
    }
}

@Composable
private fun Stages(st: RangeStats) {
    if (st.nights == 0) return
    Panel {
        SectionTitle("Average sleep stages")
        Spacer(Modifier.height(10.dp))
        StageBar(st.deepPct, st.lightPct, st.remPct)
        Spacer(Modifier.height(6.dp))
        StageLegend()
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Deep", "${fmtDur(st.avgDeepMin)}  (${st.deepPct}%)")
        KeyValueRow("Light", "${fmtDur(st.avgLightMin)}  (${st.lightPct}%)")
        KeyValueRow("REM", "${fmtDur(st.avgRemMin)}  (${st.remPct}%)")
        KeyValueRow("Awake during the night", fmtDur(st.avgWasoMin))
    }
}

@Composable
private fun Timing(st: RangeStats) {
    if (st.nights == 0) return
    Panel {
        SectionTitle("Timing")
        Spacer(Modifier.height(6.dp))
        st.avgBedtimeMin?.let { KeyValueRow("Average bedtime", Stats.clock12(it)) }
        st.avgFallAsleepMin?.let { KeyValueRow("Average fall-asleep time", Stats.clock12(it)) }
        st.avgWakeMinOfDay?.let { KeyValueRow("Average wake-up", Stats.clock12FromMinuteOfDay(it)) }
        st.fallAsleepSdMin?.let { KeyValueRow("Bedtime consistency", "within about $it min") }
        st.socialJetlagMin?.let { KeyValueRow("Weekend shift (social jet lag)", fmtDur(it)) }
        if (st.avgBedtimeMin == null) Dim("Not enough nights yet.")
    }
}

@Composable
private fun Quality(st: RangeStats) {
    if (st.nights == 0) return
    Panel {
        SectionTitle("Sleep quality")
        Spacer(Modifier.height(6.dp))
        KeyValueRow("Efficiency (asleep / in bed)", "${st.avgEfficiencyPct}%")
        KeyValueRow("Time to fall asleep", "about ${fmtDur(st.avgLatencyMin)}")
        KeyValueRow("Awakenings per night", "%.1f".format(st.avgAwakenings))
        KeyValueRow("Noise disturbances per hour", "%.1f".format(st.avgNoiseEventsPerHour))
    }
}

@Composable
private fun Charts(st: RangeStats, goalMin: Int, metric: Int, onMetric: (Int) -> Unit) {
    Panel {
        SectionTitle("Score per night")
        Spacer(Modifier.height(8.dp))
        ScoreTrendChart(st.daily, st.from, st.to)
    }
    Panel {
        SectionTitle("Sleep per day")
        Dim("Green bars reached your goal. Includes naps.")
        Spacer(Modifier.height(8.dp))
        DailySleepChart(st.daily, st.from, st.to, goalMin)
    }
    Panel {
        SectionTitle("By day of the week")
        Dim("Grouped by the day you woke up. Main sleeps only.")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = metric == 0, onClick = { onMetric(0) }, label = { Text("Hours") })
            FilterChip(selected = metric == 1, onClick = { onMetric(1) }, label = { Text("Score") })
        }
        Spacer(Modifier.height(10.dp))
        val labels = st.byWeekday.map { it.day.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } }
        if (metric == 0) {
            val values = st.byWeekday.map { it.avgSleepMin }
            LabeledBars(
                labels = labels,
                values = values,
                valueText = values.map { if (it == null) "-" else fmtHours(it.toInt()) },
                maxValue = max(goalMin * 1.2f, values.filterNotNull().maxOrNull() ?: 0f),
                color = Palette.accent
            )
        } else {
            val values = st.byWeekday.map { it.avgScore }
            LabeledBars(
                labels = labels,
                values = values,
                valueText = values.map { if (it == null) "-" else "${it.toInt()}" },
                maxValue = 100f,
                color = Palette.good
            )
        }
        Spacer(Modifier.height(10.dp))
        st.byWeekday.filter { it.nights > 0 && it.avgBedtimeMin != null }.let { rows ->
            if (rows.isNotEmpty()) {
                Dim("Average bedtime by day")
                rows.forEach { r ->
                    KeyValueRow(Stats.dayName(r.day), "${Stats.clock12(r.avgBedtimeMin ?: 0)}  (${r.nights} night${if (r.nights == 1) "" else "s"})")
                }
            }
        }
    }
}
