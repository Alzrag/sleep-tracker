package app.sleepscore.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.panel)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Palette.text,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
fun Dim(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = Palette.dim)
}

data class Tile(val label: String, val value: String, val sub: String? = null)

@Composable
fun StatTile(tile: Tile, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.panelHi)
            .padding(12.dp)
    ) {
        Text(tile.label, style = MaterialTheme.typography.labelMedium, color = Palette.dim)
        Text(
            tile.value,
            style = MaterialTheme.typography.titleLarge,
            color = Palette.text,
            fontWeight = FontWeight.Bold
        )
        if (tile.sub != null) {
            Text(tile.sub, style = MaterialTheme.typography.labelSmall, color = Palette.dim)
        }
    }
}

/** Two tiles per row. */
@Composable
fun TileGrid(tiles: List<Tile>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(pair[0], Modifier.weight(1f))
                if (pair.size > 1) StatTile(pair[1], Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ScoreRing(score: Int, diameter: Dp = 120.dp, caption: String? = null) {
    val color = scoreColor(score)
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.10f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            drawArc(
                color = Palette.panelHi, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = stroke)
            )
            drawArc(
                color = color, startAngle = -90f, sweepAngle = 360f * score.coerceIn(0, 100) / 100f,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$score",
                style = MaterialTheme.typography.headlineLarge,
                color = Palette.text,
                fontWeight = FontWeight.Bold
            )
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

/** Proportional bar of how the night split into stages. */
@Composable
fun StageBar(deep: Int, light: Int, rem: Int, awake: Int = 0, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
    ) {
        if (deep > 0) Box(Modifier.weight(deep.toFloat()).fillMaxHeight().background(Palette.deep))
        if (light > 0) Box(Modifier.weight(light.toFloat()).fillMaxHeight().background(Palette.light))
        if (rem > 0) Box(Modifier.weight(rem.toFloat()).fillMaxHeight().background(Palette.rem))
        if (awake > 0) Box(Modifier.weight(awake.toFloat()).fillMaxHeight().background(Palette.wake))
        if (deep + light + rem + awake == 0) Box(Modifier.weight(1f).fillMaxHeight().background(Palette.panelHi))
    }
}

@Composable
fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.dim)
    }
}

@Composable
fun StageLegend(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        LegendDot(Palette.deep, "Deep")
        LegendDot(Palette.light, "Light")
        LegendDot(Palette.rem, "REM")
        LegendDot(Palette.wake, "Awake")
    }
}

/** A labelled horizontal progress bar, used for the score breakdown. */
@Composable
fun PointsBar(label: String, points: Int, max: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.text)
            Text("$points / $max", style = MaterialTheme.typography.bodyMedium, color = Palette.dim)
        }
        Spacer(Modifier.height(4.dp))
        val frac = if (max == 0) 0f else (points.toFloat() / max).coerceIn(0f, 1f)
        Row(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Palette.panelHi)
        ) {
            if (frac > 0f) {
                Box(Modifier.weight(frac).fillMaxHeight().background(scoreColor((frac * 100).toInt())))
            }
            if (frac < 1f) Spacer(Modifier.weight(1f - frac))
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = Palette.dim)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.text, fontWeight = FontWeight.Medium)
    }
}
