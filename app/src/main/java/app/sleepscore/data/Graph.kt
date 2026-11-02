package app.sleepscore.data

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow

/** Tiny service locator: one database, one prefs object, one repository for the whole process. */
object Graph {
    lateinit var prefs: Prefs
        private set
    lateinit var repo: SleepRepository
        private set

    lateinit var dex: PokedexRepository
        private set

    fun init(app: Application) {
        prefs = Prefs(app)
        dex = PokedexRepository(PokedexDatabase.build(app), PokedexRepository.loadSpecies(app))
        repo = SleepRepository(AppDatabase.build(app), prefs, dex)
    }
}

/** What the UI shows while the service is running. Lives in memory only. */
data class LiveReading(
    val noiseDb: Float = -1f,
    val movement: Float = 0f,
    val lux: Float = -1f,
    val minutes: Int = 0
)

object TrackingState {
    val running = MutableStateFlow(false)
    val live = MutableStateFlow(LiveReading())
}
