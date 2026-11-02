package app.sleepscore.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleepscore.engine.DayPoint
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/** Classic sleep "hypnogram": one coloured block per run of the same stage. */
@Composable
fun Hypnogram(stages: String, startMs: Long, modifier: Modifier = Modifier) {
    val rowH = 28.dp
    val rows = listOf("Awake", "REM", "Light", "Deep")
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(46.dp)) {
                rows.forEach { name ->
                    Box(Modifier.height(rowH), contentAlignment = Alignment.CenterStart) {
                        Text(name, style = MaterialTheme.typography.labelSmall, color = Palette.dim)
                    }
                }
            }
            Canvas(Modifier.weight(1f).height(rowH * 4)) {
                val n = stages.length
                if (n == 0) return@Canvas
                val w = this.size.width
                val h = this.size.height
                val rh = h / 4f
                for (r in 1..3) {
                    drawLine(Palette.panelHi, Offset(0f, rh * r), Offset(w, rh * r), 1f)
                }
                var i = 0
                while (i < n) {
                    val c = stages[i]
                    var j = i
                    while (j < n && stages[j] == c) j++
                    val row = when (c) { 'W' -> 0; 'R' -> 1; 'L' -> 2; else -> 3 }
                    val color = when (c) {
                        'W' -> Palette.wake
                        'R' -> Palette.rem
                        'L' -> Palette.light
                        else -> Palette.deep
                    }
                    val x0 = w * i / n
                    val x1 = w * j / n
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x0, rh * row + rh * 0.16f),
                        size = Size(max(x1 - x0, 2f), rh * 0.68f),
                        cornerRadius = CornerRadius(5f, 5f)
                    )
                    i = j
                }
            }
        }
        if (stages.isNotEmpty()) {
            val totalMs = stages.length * 60_000L
            Row(
                Modifier.fillMaxWidth().padding(start = 46.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Dim(fmtTime(startMs))
                Dim(fmtTime(startMs + totalMs / 2))
                Dim(fmtTime(startMs + totalMs))
            }
        }
    }
}

/** Per-minute movement as small bars. [values] is one entry per minute. */
@Composable
fun MovementChart(values: List<Float>, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(8.dp)).background(Palette.panelHi)) {
        val n = values.size
        if (n == 0) return@Canvas
        val w = this.size.width
        val h = this.size.height
        val bw = max(w / n, 1.5f)
        val cap = 6f
        for (i in 0 until n) {
            val v = min(values[i], cap) / cap
            if (v <= 0.02f) continue
            drawRect(
                Palette.wake,
                topLeft = Offset(w * i / n, h - h * v),
                size = Size(bw, h * v)
            )
        }
    }
}

/** Per-minute average noise as a line. Negative values mean "no data". */
@Composable
fun NoiseChart(values: List<Float>, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(8.dp)).background(Palette.panelHi)) {
        val n = values.size
        val valid = values.filter { it >= 0f }
        if (n < 2 || valid.isEmpty()) return@Canvas
        val lo = valid.minOrNull() ?: 0f
        val hi = max(valid.maxOrNull() ?: 0f, lo + 10f)
        val w = this.size.width
        val h = this.size.height
        val path = Path()
        var open = false
        for (i in 0 until n) {
            val v = values[i]
            if (v < 0f) { open = false; continue }
            val x = w * i / (n - 1)
            val y = h - h * 0.1f - (h * 0.8f) * (v - lo) / (hi - lo)
            if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
        }
        drawPath(path, Palette.light, style = Stroke(width = 2.5f, join = StrokeJoin.Round))
    }
}

/** Seven bars (or any number) with value labels above and names below. */
@Composable
fun LabeledBars(
    labels: List<String>,
    values: List<Float?>,
    valueText: List<String>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in labels.indices) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(valueText[i], style = MaterialTheme.typography.labelSmall, color = Palette.dim)
                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.BottomCenter) {
                    val frac = ((values[i] ?: 0f) / max(maxValue, 0.0001f)).coerceIn(0f, 1f)
                    if (frac > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(frac)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(color)
                        )
                    }
                }
                Text(labels[i], style = MaterialTheme.typography.labelSmall, color = Palette.text)
            }
        }
    }
}

/** Sleep hours per day across the chosen range, with a dashed line at the goal. */
@Composable
fun DailySleepChart(
    days: List<DayPoint>,
    from: LocalDate,
    to: LocalDate,
    goalMin: Int,
    modifier: Modifier = Modifier
) {
    val n = (ChronoUnit.DAYS.between(from, to) + 1).toInt().coerceAtLeast(1)
    val maxMin = max(goalMin * 1.25f, (days.maxOfOrNull { it.sleepMin } ?: 0) * 1.1f)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(8.dp)).background(Palette.panelHi)) {
            val w = this.size.width
            val h = this.size.height
            val slot = w / n
            val barW = max(slot * 0.7f, 2f)
            for (d in days) {
                val off = ChronoUnit.DAYS.between(from, d.date).toInt()
                if (off !in 0 until n) continue
                val bh = h * (d.sleepMin / maxMin).coerceIn(0f, 1f)
                drawRoundRect(
                    color = if (d.sleepMin >= goalMin) Palette.good else Palette.accent,
                    topLeft = Offset(slot * off + (slot - barW) / 2f, h - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }
            val gy = h - h * (goalMin / maxMin)
            drawLine(
                Palette.fair, Offset(0f, gy), Offset(w, gy), 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Dim(fmtShortDay(from))
            Dim("dashed line = ${fmtDur(goalMin)} goal")
            Dim(fmtShortDay(to))
        }
    }
}

/** Sleep score per night as a line with dots (0 to 100). */
@Composable
fun ScoreTrendChart(days: List<DayPoint>, from: LocalDate, to: LocalDate, modifier: Modifier = Modifier) {
    val n = (ChronoUnit.DAYS.between(from, to) + 1).toInt().coerceAtLeast(1)
    val pts = days.filter { it.score != null }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(8.dp)).background(Palette.panelHi)) {
            Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                val w = this.size.width
                val h = this.size.height
                val padY = 14f
                fun yOf(s: Int) = padY + (h - 2 * padY) * (1f - s / 100f)
                for (g in listOf(50, 70, 85)) {
                    drawLine(Color(0x22FFFFFF), Offset(0f, yOf(g)), Offset(w, yOf(g)), 1f)
                }
                val path = Path()
                var first = true
                for (p in pts) {
                    val off = ChronoUnit.DAYS.between(from, p.date).toInt()
                    if (off !in 0 until n) continue
                    val x = w * (off + 0.5f) / n
                    val y = yOf(p.score ?: 0)
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                }
                if (!first) drawPath(path, Palette.accent, style = Stroke(width = 3f, join = StrokeJoin.Round))
                for (p in pts) {
                    val off = ChronoUnit.DAYS.between(from, p.date).toInt()
                    if (off !in 0 until n) continue
                    drawCircle(scoreColor(p.score ?: 0), 5f, Offset(w * (off + 0.5f) / n, yOf(p.score ?: 0)))
                }
            }
            Text("100", Modifier.align(Alignment.TopStart).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = Palette.dim)
            Text("0", Modifier.align(Alignment.BottomStart).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = Palette.dim)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Dim(fmtShortDay(from))
            Dim(fmtShortDay(to))
        }
    }
}
