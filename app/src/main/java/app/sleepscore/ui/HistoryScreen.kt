package app.sleepscore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleepscore.data.SleepSession

@Composable
fun HistoryScreen(sessions: List<SleepSession>, onOpen: (Long) -> Unit) {
    if (sessions.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("History", style = MaterialTheme.typography.headlineMedium, color = Palette.text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Dim("No nights recorded yet. The app keeps your last 60 days.")
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("History", style = MaterialTheme.typography.headlineMedium, color = Palette.text, fontWeight = FontWeight.Bold)
            Dim("Last 60 days. Tap a night for the full breakdown.")
            Spacer(Modifier.height(4.dp))
        }
        items(sessions, key = { it.id }) { s -> SessionRow(s) { onOpen(s.id) } }
    }
}

@Composable
private fun SessionRow(s: SleepSession, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.panel)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val noSleep = s.totalSleepMin < 10
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (noSleep) Palette.panelHi else scoreColor(s.score).copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (noSleep) "-" else "${s.score}",
                color = if (noSleep) Palette.dim else scoreColor(s.score),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                fmtDay(s.wakeMs.takeIf { it > 0 } ?: s.startMs),
                color = Palette.text,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge
            )
            Dim(
                if (noSleep) "No sleep detected"
                else "${fmtDur(s.totalSleepMin)} asleep, ${fmtTime(s.startMs)} to ${fmtTime(s.wakeMs)}"
            )
        }
        if (s.isNap) {
            Text(
                "NAP",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.rem,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
