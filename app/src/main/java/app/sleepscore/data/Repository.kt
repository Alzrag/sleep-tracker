package app.sleepscore.data

import app.sleepscore.engine.AnalysisResult
import app.sleepscore.engine.EpochInput
import app.sleepscore.engine.SleepAnalyzer
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

class SleepRepository(private val db: AppDatabase, private val prefs: Prefs, private val dex: PokedexRepository) {
    private val dao = db.dao()

    companion object {
        const val RETENTION_DAYS = 60L
        const val MIN_SESSION_MIN = 10
    }

    val completed: Flow<List<SleepSession>> = dao.observeCompleted()
    val active: Flow<SleepSession?> = dao.observeActive()

    fun observeSession(id: Long): Flow<SleepSession?> = dao.observeSession(id)
    suspend fun getEpochs(id: Long): List<EpochRow> = dao.getEpochs(id)
    suspend fun getActive(): SleepSession? = dao.getActive()
    suspend fun getSession(id: Long): SleepSession? = dao.getSession(id)
    suspend fun lastMinuteIndex(id: Long): Int? = dao.lastMinuteIndex(id)

    suspend fun startSession(mic: Boolean, nowMs: Long): Long =
        dao.insertSession(SleepSession(startMs = nowMs, micEnabled = mic, goalMin = prefs.goalMin.value))

    suspend fun addEpoch(row: EpochRow) = dao.insertEpoch(row)

    /** Analyses a finished session. Returns false (and deletes it) if it was too short to mean anything. */
    suspend fun finishSession(id: Long, endMs: Long): Boolean {
        val s = dao.getSession(id) ?: return false
        val totalMin = ceil((endMs - s.startMs) / 60000.0).toInt()
        if (totalMin < MIN_SESSION_MIN) {
            dao.deleteSession(id)
            return false
        }
        val res = analyse(s, totalMin)
        val startHour = Instant.ofEpochMilli(s.startMs).atZone(ZoneId.systemDefault()).hour
        val nap = res.totalSleepMin < 180 || (startHour in 9..18 && res.totalSleepMin < 300)
        dao.updateSession(withResult(s.copy(endMs = endMs, isNap = nap), res))
        // Waking up after a real sleep earns one Pokémon (re-scoring a night later never rolls again).
        if (res.totalSleepMin >= PokedexRepository.MIN_SLEEP_FOR_CATCH_MIN) dex.rollFor(id, endMs)
        return true
    }

    /** Re-scores every stored night, e.g. after the sleep goal changes. Keeps each night's nap flag. */
    suspend fun reanalyzeAll() {
        for (s in dao.getAllCompleted()) {
            val end = s.endMs ?: continue
            val totalMin = ceil((end - s.startMs) / 60000.0).toInt()
            if (totalMin < MIN_SESSION_MIN) continue
            dao.updateSession(withResult(s, analyse(s, totalMin)))
        }
    }

    suspend fun setNap(id: Long, nap: Boolean) {
        val s = dao.getSession(id) ?: return
        dao.updateSession(s.copy(isNap = nap))
    }

    suspend fun delete(id: Long) = dao.deleteSession(id)

    suspend fun pruneOld() {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600_000L
        dao.deleteOlderThan(cutoff)
    }

    private suspend fun analyse(s: SleepSession, totalMin: Int): AnalysisResult {
        val epochs = dao.getEpochs(s.id).map {
            EpochInput(it.minuteIndex, it.movement, it.noiseAvg, it.noisePeak, it.lux, it.screenSec)
        }
        return SleepAnalyzer.analyze(epochs, totalMin, s.micEnabled, prefs.goalMin.value)
    }

    private fun withResult(s: SleepSession, r: AnalysisResult): SleepSession = s.copy(
        analyzed = true,
        goalMin = prefs.goalMin.value,
        onsetMs = s.startMs + r.sleepOnsetMin * 60_000L,
        wakeMs = s.startMs + r.wakeMin * 60_000L,
        timeInBedMin = r.timeInBedMin,
        totalSleepMin = r.totalSleepMin,
        wasoMin = r.wasoMin,
        lightMin = r.lightMin,
        deepMin = r.deepMin,
        remMin = r.remMin,
        latencyMin = r.latencyMin,
        awakenings = r.awakenings,
        efficiency = r.efficiency,
        noiseEvents = r.noiseEvents,
        avgNoiseDb = r.avgNoiseDb,
        score = r.score,
        sDuration = r.breakdown.duration,
        sEfficiency = r.breakdown.efficiency,
        sDeep = r.breakdown.deep,
        sRem = r.breakdown.rem,
        sContinuity = r.breakdown.continuity,
        sLatency = r.breakdown.latency,
        sEnvironment = r.breakdown.environment,
        stages = r.stages
    )
}
