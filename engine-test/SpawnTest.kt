// Statistical check of the Pokémon spawner. Run with run-spawn-test.sh.
import app.sleepscore.engine.*
import java.io.File
import kotlin.random.Random

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "  PASS " else "  FAIL ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

fun main(args: Array<String>) {
    val species = Spawner.parse(File(args[0]).readLines())
    val pool = Spawner.Pool(species)
    println("species=${species.size} regular=${pool.regular.size} legendary=${pool.legendaries.size} mythical=${pool.mythicals.size}")

    val n = 4_000_000
    val rnd = Random(2024)
    val counts = IntArray(2000)
    var shiny = 0
    val tiers = HashMap<Rarity, Int>()
    for (i in 0 until n) {
        val e = Spawner.roll(pool, rnd)
        counts[e.species.dex]++
        if (e.shiny) shiny++
        tiers.merge(e.species.rarity, 1, Int::plus)
    }
    fun pct(r: Rarity) = 100.0 * (tiers[r] ?: 0) / n
    println("tier shares: " + Rarity.values().joinToString { "${it.label} ${"%.3f".format(pct(it))}%" })
    println("shiny: $shiny of $n  (expected ${n / Spawner.SHINY_ODDS}, 1 in ${n / maxOf(shiny, 1)})")

    val byDex = species.associateBy { it.dex }
    check("legendary share about 1%", pct(Rarity.LEGENDARY) in 0.9..1.1, "%.3f".format(pct(Rarity.LEGENDARY)))
    check("mythical share about 0.2%", pct(Rarity.MYTHICAL) in 0.17..0.23, "%.3f".format(pct(Rarity.MYTHICAL)))
    val expShiny = n.toDouble() / Spawner.SHINY_ODDS
    check("shiny rate matches 1/4096 (within 4 sigma)", kotlin.math.abs(shiny - expShiny) < 4 * Math.sqrt(expShiny), "$shiny vs ${"%.0f".format(expShiny)}")

    // a catch-rate-255 species should appear ~5.67x as often as a catch-rate-45 one (same flags)
    val common = species.filter { it.captureRate == 255 && !it.legendary && !it.mythical && !it.baby }
    val rare = species.filter { it.captureRate == 45 && !it.legendary && !it.mythical && !it.baby }
    val cAvg = common.map { counts[it.dex] }.average()
    val rAvg = rare.map { counts[it.dex] }.average()
    check("255-rate appears ~5.7x as often as 45-rate", cAvg / rAvg in 5.2..6.2, "%.2f".format(cAvg / rAvg))
    val vr = species.filter { it.captureRate == 3 && !it.legendary && !it.mythical && !it.baby }
    if (vr.isNotEmpty()) {
        val vAvg = vr.map { counts[it.dex] }.average()
        check("255-rate ~85x as often as 3-rate", cAvg / vAvg in 70.0..100.0, "%.1f".format(cAvg / vAvg))
    }
    val baby = species.filter { it.baby && it.captureRate == 255 }
    if (baby.isNotEmpty()) {
        val bAvg = baby.map { counts[it.dex] }.average()
        check("baby Pokémon scaled to about a quarter", bAvg / cAvg in 0.2..0.31, "%.2f".format(bAvg / cAvg))
    }
    check("every species can appear", species.all { counts[it.dex] > 0 }, "never seen: " + species.filter { counts[it.dex] == 0 }.joinToString { it.name })
    println("most common: " + species.sortedByDescending { counts[it.dex] }.take(5).joinToString { "${it.name} ${counts[it.dex]}" })
    println("rarest seen: " + species.filter { counts[it.dex] > 0 }.sortedBy { counts[it.dex] }.take(5).joinToString { "${it.name} ${counts[it.dex]}" })

    // how long does it take one person to see everything? (one roll per morning)
    val seen = HashSet<Int>(); var days = 0; val r2 = Random(7)
    var d100 = -1; var d500 = -1
    while (seen.size < species.size && days < 3_000_000) {
        days++; seen += Spawner.roll(pool, r2).species.dex
        if (d100 < 0 && seen.size >= 100) d100 = days
        if (d500 < 0 && seen.size >= 500) d500 = days
    }
    println("one roll per morning: 100 species after $d100 days, 500 after $d500 days, all ${species.size} after $days days (${days / 365} years)")
    check("a legendary shows up within about a year on average", pct(Rarity.LEGENDARY) * 3.65 >= 3.0)

    println(if (failures == 0) "ALL SPAWN CHECKS PASSED" else "$failures SPAWN CHECK(S) FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
