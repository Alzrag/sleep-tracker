package app.sleepscore.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A dark theme on purpose: this app is opened in a dark bedroom. */
object Palette {
    val bg = Color(0xFF0B1020)
    val panel = Color(0xFF151C38)
    val panelHi = Color(0xFF1F2850)
    val text = Color(0xFFE6E9F5)
    val dim = Color(0xFFA3ABCB)
    val accent = Color(0xFF8FA2FF)

    val wake = Color(0xFFFF9E6B)
    val rem = Color(0xFFC39BFF)
    val light = Color(0xFF6CB6FF)
    val deep = Color(0xFF4A56D6)

    val good = Color(0xFF5BD6A0)
    val ok = Color(0xFF6CB6FF)
    val fair = Color(0xFFFFC857)
    val poor = Color(0xFFFF7A6B)
}

fun scoreColor(score: Int): Color = when {
    score >= 85 -> Palette.good
    score >= 70 -> Palette.ok
    score >= 55 -> Palette.fair
    else -> Palette.poor
}

fun scoreLabel(score: Int): String = when {
    score >= 85 -> "Excellent"
    score >= 70 -> "Good"
    score >= 55 -> "Fair"
    else -> "Poor"
}

@Composable
fun SleepTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.bg,
            secondary = Palette.rem,
            background = Palette.bg,
            onBackground = Palette.text,
            surface = Palette.panel,
            onSurface = Palette.text,
            surfaceVariant = Palette.panelHi,
            onSurfaceVariant = Palette.dim,
            outline = Color(0xFF3A4570),
            primaryContainer = Color(0xFF26337A),
            onPrimaryContainer = Color(0xFFDDE2FF),
            surfaceContainerHighest = Palette.panelHi,
            surfaceContainerHigh = Palette.panel,
            surfaceContainer = Palette.panel,
            surfaceContainerLow = Palette.panel,
            surfaceContainerLowest = Palette.bg
        ),
        content = content
    )
}
