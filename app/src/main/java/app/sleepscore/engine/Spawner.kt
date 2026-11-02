package app.sleepscore.engine

import kotlin.random.Random

enum class Rarity(val label: String) {
    COMMON("Common"),
    UNCOMMON("Uncommon"),
    RARE("Rare"),
    VERY_RARE("Very rare"),
    LEGENDARY("Legendary"),
    MYTHICAL("Mythical")
}

data class Species(
    val dex: Int,
    val name: String,
    val captureRate: Int,   // the games' catch rate, 3 (hardest) to 255 (easiest)
    val legendary: Boolean,
    val mythical: Boolean,
    val baby: Boolean,
    val hasShiny: Boolean   // false when the texture pack has no shiny sprite for it
) {
    val rarity: Rarity
        get() = when {
            mythical -> Rarity.MYTHICAL
            legendary -> Rarity.LEGENDARY
            captureRate >= 150 -> Rarity.COMMON
            captureRate >= 75 -> Rarity.UNCOMMON
            captureRate >= 25 -> Rarity.RARE
            else -> Rarity.VERY_RARE
        }
}

data class Encounter(val species: Species, val shiny: Boolean)

/**
 * Picks the Pokémon you "find" in your sleep.
 *
 *  - Ordinary Pokémon are weighted by their catch rate from the games, so a Pidgey (255) turns up
 *    about 5x as often as a Charmander (45), and 85x as often as a 3-catch-rate Pokémon.
 *  - Baby Pokémon are scaled down, because they don't show up in the wild in the games.
 *  - Legendary and mythical Pokémon each have their own small flat chance, then one is picked at
 *    random from that group (their catch rates are mostly 3, so weighting wouldn't tell them apart).
 *  - Any encounter is shiny with the games' standard odds of 1 in 4096.
 *
 * All the knobs are right here.
 */
object Spawner {
    const val SHINY_ODDS = 4096
    const val LEGENDARY_CHANCE = 0.01   // 1 in 100 mornings
    const val MYTHICAL_CHANCE = 0.002   // 1 in 500 mornings
    const val BABY_WEIGHT = 0.25

    /** Parses species.tsv: dex, name, capture rate, flags (L/M/B or -), has-shiny (0/1), tab separated. */
    fun parse(lines: List<String>): List<Species> =
        lines.filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            Species(
                dex = p[0].trim().toInt(),
                name = p[1],
                captureRate = p[2].trim().toInt(),
                legendary = 'L' in p[3],
                mythical = 'M' in p[3],
                baby = 'B' in p[3],
                hasShiny = p[4].trim() == "1"
            )
        }

    class Pool(all: List<Species>) {
        val legendaries = all.filter { it.legendary && !it.mythical }
        val mythicals = all.filter { it.mythical }
        val regular = all.filter { !it.legendary && !it.mythical }
        val weights: DoubleArray = DoubleArray(regular.size) {
            regular[it].captureRate * (if (regular[it].baby) BABY_WEIGHT else 1.0)
        }
        val totalWeight: Double = weights.sum()
    }

    fun roll(pool: Pool, rnd: Random = Random.Default): Encounter {
        val u = rnd.nextDouble()
        val species = when {
            u < MYTHICAL_CHANCE && pool.mythicals.isNotEmpty() -> pool.mythicals[rnd.nextInt(pool.mythicals.size)]
            u < MYTHICAL_CHANCE + LEGENDARY_CHANCE && pool.legendaries.isNotEmpty() ->
                pool.legendaries[rnd.nextInt(pool.legendaries.size)]
            else -> weighted(pool, rnd)
        }
        return Encounter(species, rnd.nextInt(SHINY_ODDS) == 0)
    }

    private fun weighted(pool: Pool, rnd: Random): Species {
        var x = rnd.nextDouble() * pool.totalWeight
        for (i in pool.regular.indices) {
            x -= pool.weights[i]
            if (x < 0) return pool.regular[i]
        }
        return pool.regular.last()
    }
}
