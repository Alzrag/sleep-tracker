package app.sleepscore.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import app.sleepscore.engine.Spawner
import app.sleepscore.engine.Species
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/** One Pokémon found after one sleep. [seen] turns true once the reveal popup has been shown. */
@Entity(tableName = "catches", indices = [Index(value = ["sessionId"], unique = true)])
data class Catch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val dex: Int,
    val shiny: Boolean,
    val caughtMs: Long,
    val newSpecies: Boolean,
    val newShiny: Boolean,
    val seen: Boolean = false
)

/** One Pokédex page: how many of a species you have found, and when you first did. */
@Entity(tableName = "dex")
data class DexEntry(
    @PrimaryKey val dex: Int,
    val count: Int,
    val shinyCount: Int,
    val firstMs: Long,
    val firstShinyMs: Long
)

@Dao
interface PokedexDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCatch(c: Catch): Long

    @Query("SELECT * FROM catches WHERE sessionId = :sessionId LIMIT 1")
    suspend fun catchForSession(sessionId: Long): Catch?

    @Query("SELECT * FROM catches WHERE seen = 0 ORDER BY caughtMs ASC")
    fun observeUnseen(): Flow<List<Catch>>

    @Query("SELECT COUNT(*) FROM catches WHERE seen = 0")
    suspend fun unseenCount(): Int

    @Query("UPDATE catches SET seen = 1 WHERE id = :id")
    suspend fun markSeen(id: Long)

    @Query("SELECT * FROM dex")
    fun observeDex(): Flow<List<DexEntry>>

    @Query("SELECT * FROM dex WHERE dex = :dex LIMIT 1")
    suspend fun getEntry(dex: Int): DexEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntry(e: DexEntry)
}

/**
 * A separate database file from the sleep history, so sleep history pruning (60 days) never touches
 * the Pokédex and the Pokédex can never break the sleep data.
 */
@Database(entities = [Catch::class, DexEntry::class], version = 1, exportSchema = false)
abstract class PokedexDatabase : RoomDatabase() {
    abstract fun dao(): PokedexDao

    companion object {
        fun build(ctx: Context): PokedexDatabase =
            Room.databaseBuilder(ctx.applicationContext, PokedexDatabase::class.java, "pokedex.db").build()
    }
}

class PokedexRepository(private val db: PokedexDatabase, val species: List<Species>) {
    companion object {
        /** You only find something if you actually slept: at least this many minutes. */
        const val MIN_SLEEP_FOR_CATCH_MIN = 180

        fun loadSpecies(ctx: Context): List<Species> =
            ctx.assets.open("species.tsv").bufferedReader().use { Spawner.parse(it.readLines()) }
    }

    private val dao = db.dao()
    private val pool = Spawner.Pool(species)
    private val byDex = species.associateBy { it.dex }

    val unseen: Flow<List<Catch>> = dao.observeUnseen()
    val entries: Flow<List<DexEntry>> = dao.observeDex()

    fun find(dex: Int): Species? = byDex[dex]

    suspend fun unseenCount(): Int = dao.unseenCount()
    suspend fun markSeen(id: Long) = dao.markSeen(id)

    /** Rolls one encounter for this sleep. A session can only ever produce one, even if re-scored later. */
    suspend fun rollFor(sessionId: Long, nowMs: Long) {
        db.withTransaction<Unit> {
            if (dao.catchForSession(sessionId) == null) {
                val enc = Spawner.roll(pool, Random.Default)
                val prev = dao.getEntry(enc.species.dex)
                val newShiny = enc.shiny && (prev?.shinyCount ?: 0) == 0
                dao.insertCatch(
                    Catch(
                        sessionId = sessionId,
                        dex = enc.species.dex,
                        shiny = enc.shiny,
                        caughtMs = nowMs,
                        newSpecies = prev == null,
                        newShiny = newShiny
                    )
                )
                dao.putEntry(
                    DexEntry(
                        dex = enc.species.dex,
                        count = (prev?.count ?: 0) + 1,
                        shinyCount = (prev?.shinyCount ?: 0) + if (enc.shiny) 1 else 0,
                        firstMs = prev?.firstMs ?: nowMs,
                        firstShinyMs = if (newShiny) nowMs else (prev?.firstShinyMs ?: 0L)
                    )
                )
            }
        }
    }
}
