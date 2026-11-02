package app.sleepscore.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sleepscore.data.Catch
import app.sleepscore.data.DexEntry
import app.sleepscore.data.Graph
import app.sleepscore.engine.Rarity
import app.sleepscore.engine.Species
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------- sprites

/** Loads the 32x32 pixel-art textures bundled in assets/pokemon and keeps them in memory (about 4 KB each). */
object Sprites {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun load(ctx: Context, dex: Int, shiny: Boolean, hasShiny: Boolean): ImageBitmap? {
        val name = if (shiny && hasShiny) "${dex}_s" else "$dex"
        cache[name]?.let { return it }
        return try {
            ctx.assets.open("pokemon/$name.png").use { BitmapFactory.decodeStream(it) }
                ?.asImageBitmap()
                ?.also { cache[name] = it }
        } catch (e: IOException) {
            null
        }
    }
}

/** Draws a Pokémon texture crisp (no smoothing). [silhouette] paints it solid dark for "not found yet". */
@Composable
fun PokemonSprite(
    species: Species,
    shiny: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    silhouette: Boolean = false
) {
    val ctx = LocalContext.current
    val bmp = remember(species.dex, shiny) { Sprites.load(ctx, species.dex, shiny, species.hasShiny) }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = if (silhouette) null else species.name,
            modifier = modifier.size(size),
            filterQuality = FilterQuality.None,
            colorFilter = if (silhouette) ColorFilter.tint(Color(0xFF05081A)) else null
        )
    } else {
        Box(modifier.size(size))
    }
}

fun rarityColor(r: Rarity): Color = when (r) {
    Rarity.COMMON -> Palette.dim
    Rarity.UNCOMMON -> Palette.good
    Rarity.RARE -> Palette.ok
    Rarity.VERY_RARE -> Palette.rem
    Rarity.LEGENDARY -> Palette.fair
    Rarity.MYTHICAL -> Color(0xFFFF8AD8)
}

@Composable
private fun RarityChip(r: Rarity) {
    val c = rarityColor(r)
    Text(
        r.label.uppercase(),
        color = c,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

private fun dexNumber(dex: Int) = "#%04d".format(dex)

// ---------------------------------------------------------------- wake-up popup

/** "While you slept, you found..." Shown once per Pokémon; tap Reveal, then close it to add it to the Pokédex view. */
@Composable
fun CatchRevealDialog(c: Catch, onDone: () -> Unit) {
    val sp = Graph.dex.find(c.dex)
    if (sp == null) {
        LaunchedEffect(c.id) { onDone() }
        return
    }
    val entries by Graph.dex.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val count = entries.firstOrNull { it.dex == c.dex }?.count ?: 1
    var revealed by remember(c.id) { mutableStateOf(false) }
    val pop by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.8f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "pop"
    )
    val shape = RoundedCornerShape(28.dp)
    val gold = revealed && c.shiny

    Dialog(
        onDismissRequest = { if (revealed) onDone() },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Palette.panel)
                .then(if (gold) Modifier.border(3.dp, Palette.fair, shape) else Modifier)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!revealed) {
                Text(
                    "While you slept...",
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.text,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Dim("Something found you in your sleep.")
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.size(190.dp).clip(RoundedCornerShape(24.dp)).background(Palette.panelHi),
                    contentAlignment = Alignment.Center
                ) {
                    PokemonSprite(sp, shiny = false, size = 150.dp, silhouette = true)
                    Text("?", style = MaterialTheme.typography.displayMedium, color = Palette.accent, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
                Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Reveal", fontWeight = FontWeight.SemiBold)
                }
            } else {
                if (c.shiny) {
                    Text(
                        "✨  SHINY!  ✨",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Palette.fair,
                        fontWeight = FontWeight.ExtraBold
                    )
                } else {
                    Text(
                        "You found...",
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.dim
                    )
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .size(210.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (c.shiny) Palette.fair.copy(alpha = 0.14f) else Palette.panelHi),
                    contentAlignment = Alignment.Center
                ) {
                    PokemonSprite(sp, shiny = c.shiny, size = 170.dp, modifier = Modifier.scale(pop))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    sp.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Palette.text,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Dim(dexNumber(sp.dex))
                Spacer(Modifier.height(8.dp))
                RarityChip(sp.rarity)
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        c.newSpecies -> "New! Added to your Pokédex."
                        c.newShiny -> "Your first shiny ${sp.name}! Added to your Pokédex."
                        else -> "Already in your Pokédex. You've found it $count times."
                    },
                    color = if (c.newSpecies || c.newShiny) Palette.good else Palette.dim,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (c.newSpecies || c.newShiny) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
                if (c.shiny && !sp.hasShiny) {
                    Spacer(Modifier.height(4.dp))
                    Dim("(No shiny texture for this one in the pack, so it shows its normal look.)")
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Nice!", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Pokédex tab

private val dexFilters = listOf("All", "Found", "Missing", "Shiny")

@Composable
fun PokedexScreen() {
    val entries by Graph.dex.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val byDex = remember(entries) { entries.associateBy { it.dex } }
    val all = Graph.dex.species
    var filter by rememberSaveable { mutableStateOf(0) }
    var open by remember { mutableStateOf<Int?>(null) }

    val shown = remember(byDex, filter) {
        when (filter) {
            1 -> all.filter { byDex.containsKey(it.dex) }
            2 -> all.filter { !byDex.containsKey(it.dex) }
            3 -> all.filter { (byDex[it.dex]?.shinyCount ?: 0) > 0 }
            else -> all
        }
    }
    val found = byDex.size
    val shinies = entries.count { it.shinyCount > 0 }
    val total = all.size

    LazyVerticalGrid(
        columns = GridCells.Adaptive(84.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth()) {
                Text("Pokédex", style = MaterialTheme.typography.headlineMedium, color = Palette.text, fontWeight = FontWeight.Bold)
                Dim("Every morning after a real sleep (3+ hours) you find one Pokémon.")
                Spacer(Modifier.height(10.dp))
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Found  $found / $total", color = Palette.text, fontWeight = FontWeight.SemiBold)
                        Text("✨ Shiny  $shinies", color = Palette.fair, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    val frac = if (total == 0) 0f else found.toFloat() / total
                    Row(
                        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.panelHi)
                    ) {
                        if (frac > 0f) Box(Modifier.weight(frac.coerceAtLeast(0.01f)).height(8.dp).background(Palette.accent))
                        if (frac < 1f) Spacer(Modifier.weight(1f - frac.coerceAtMost(0.99f)))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dexFilters.forEachIndexed { i, label ->
                        FilterChip(
                            selected = filter == i,
                            onClick = { filter = i },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Panel {
                    Text(
                        if (found == 0) "Nothing here yet" else "Nothing matches this filter",
                        color = Palette.text,
                        fontWeight = FontWeight.SemiBold
                    )
                    Dim(
                        if (found == 0) "Track a night of sleep and tap stop in the morning. Your first Pokémon is waiting."
                        else "Try another filter."
                    )
                }
            }
        }
        items(shown, key = { it.dex }) { sp ->
            DexCell(sp, byDex[sp.dex]) { open = sp.dex }
        }
    }

    val openDex = open
    if (openDex != null) {
        val sp = Graph.dex.find(openDex)
        val entry = byDex[openDex]
        if (sp != null && entry != null) DexDetailDialog(sp, entry) { open = null } else open = null
    }
}

@Composable
private fun DexCell(sp: Species, entry: DexEntry?, onClick: () -> Unit) {
    val found = entry != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (found) Palette.panel else Palette.panel.copy(alpha = 0.55f))
            .then(if (found) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            PokemonSprite(sp, shiny = false, size = 56.dp, silhouette = !found)
            if (entry != null && entry.shinyCount > 0) Text("✨", style = MaterialTheme.typography.labelSmall)
        }
        Text(dexNumber(sp.dex), style = MaterialTheme.typography.labelSmall, color = Palette.dim)
        Text(
            if (found) sp.name else "???",
            style = MaterialTheme.typography.labelMedium,
            color = if (found) Palette.text else Palette.dim,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DexDetailDialog(sp: Species, entry: DexEntry, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = {
            Column {
                Text(sp.name, fontWeight = FontWeight.Bold)
                Dim(dexNumber(sp.dex))
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(130.dp).clip(RoundedCornerShape(20.dp)).background(Palette.panelHi), contentAlignment = Alignment.Center) {
                        PokemonSprite(sp, shiny = false, size = 112.dp)
                    }
                    if (entry.shinyCount > 0) {
                        Box(
                            Modifier.size(130.dp).clip(RoundedCornerShape(20.dp)).background(Palette.fair.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) { PokemonSprite(sp, shiny = true, size = 112.dp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                RarityChip(sp.rarity)
                Spacer(Modifier.height(10.dp))
                KeyValueRow("Found", "${entry.count} time${if (entry.count == 1) "" else "s"}")
                KeyValueRow("First found", fmtDay(entry.firstMs))
                if (entry.shinyCount > 0) {
                    KeyValueRow("Shiny found", "${entry.shinyCount} time${if (entry.shinyCount == 1) "" else "s"}")
                    KeyValueRow("First shiny", fmtDay(entry.firstShinyMs))
                }
            }
        }
    )
}
