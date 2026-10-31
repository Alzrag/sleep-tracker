package app.sleepscore.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sleepscore.data.Graph
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SleepTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val sessions by Graph.repo.completed.collectAsStateWithLifecycle(initialValue = emptyList())
    val active by Graph.repo.active.collectAsStateWithLifecycle(initialValue = null)

    BackHandler(enabled = detailId != null) { detailId = null }

    Scaffold(
        containerColor = Palette.bg,
        topBar = {
            if (detailId == null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sleep Score", color = Palette.dim, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.dim)
                    }
                }
            }
        },
        bottomBar = {
            if (detailId == null) {
                NavigationBar(containerColor = Color(0xFF0F1530)) {
                    val items = listOf("Tonight" to "🌙", "History" to "📅", "Stats" to "📊")
                    items.forEachIndexed { i, (label, emoji) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(emoji) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = Palette.accent,
                                unselectedTextColor = Palette.dim,
                                indicatorColor = Palette.panelHi
                            )
                        )
                    }
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().background(Palette.bg).padding(inner)) {
            val id = detailId
            if (id != null) {
                DetailScreen(id = id, onBack = { detailId = null })
            } else {
                when (tab) {
                    0 -> TonightScreen(sessions = sessions, active = active, onOpenSession = { detailId = it })
                    1 -> HistoryScreen(sessions = sessions, onOpen = { detailId = it })
                    else -> StatsScreen(sessions = sessions)
                }
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val savedGoal by Graph.prefs.goalMin.collectAsStateWithLifecycle()
    val micEnabled by Graph.prefs.micEnabled.collectAsStateWithLifecycle()
    // slider works in quarter-hours so 8.5 h lands exactly on a notch
    var hours by remember { mutableFloatStateOf(savedGoal / 60f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Sleep goal: ${fmtDur((hours * 60).roundToInt())}")
                Slider(
                    value = hours,
                    onValueChange = { hours = (it * 4).roundToInt() / 4f },
                    valueRange = 5f..11f,
                    steps = 23
                )
                Text("Your score and \"% of goal\" use this. Changing it re-scores your saved nights.")
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Listen for noise")
                        Text("Measures loudness only. Audio is never recorded or saved.")
                    }
                    Switch(checked = micEnabled, onCheckedChange = { Graph.prefs.setMic(it) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = (hours * 60).roundToInt()
                if (minutes != savedGoal) {
                    Graph.prefs.setGoal(minutes)
                    scope.launch { Graph.repo.reanalyzeAll() }
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
