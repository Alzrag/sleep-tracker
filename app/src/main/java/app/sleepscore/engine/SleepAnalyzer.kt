package app.sleepscore.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One minute of raw phone data. [minuteIndex] counts from the moment tracking started. */
data class EpochInput(
    val minuteIndex: Int,
    val movement: Float,   // sum of accelerometer jitter above the noise floor during the minute
    val noiseAvg: Float,   // average relative dB over the minute (-1 if microphone was off)
    val noisePeak: Float,  // loudest ~250 ms chunk in the minute (-1 if microphone was off)
    val lux: Float,        // average ambient light, -1 if no sensor
    val screenSec: Int     // seconds the screen was on during the minute
)

object Stage {
    const val WAKE = 'W'
    const val LIGHT = 'L'
    const val DEEP = 'D'
    const val REM = 'R'
}

data class ScoreBreakdown(
    val duration: Int,    // out of 50
    val efficiency: Int,  // out of 12
    val deep: Int,        // out of 10
    val rem: Int,         // out of 10
    val continuity: Int,  // out of 10
    val latency: Int,     // out of 4
    val environment: Int  // out of 4
) {
    val total: Int get() = duration + efficiency + deep + rem + continuity + latency + environment
}

data class AnalysisResult(
    val stages: String,          // one char per minute from tracking start: W, L, D, R
    val sleepOnsetMin: Int,      // minute index when you fell asleep
    val wakeMin: Int,            // minute index (exclusive) when you finally woke
    val timeInBedMin: Int,
    val totalSleepMin: Int,
    val wasoMin: Int,            // awake minutes after falling asleep
    val lightMin: Int,
    val deepMin: Int,
    val remMin: Int,
    val latencyMin: Int,
    val awakenings: Int,
    val efficiency: Float,
    val noiseEvents: Int,
    val avgNoiseDb: Float,       // -1 when the microphone was off
    val score: Int,
    val breakdown: ScoreBreakdown
)

/**
 * Turns a night of phone-only sensor data into sleep/wake, light/deep/REM estimates and a score.
 *
 * This is an ESTIMATE. A phone on a mattress cannot measure brain waves; staging is inferred from
 * how still the bed is, noise, light, screen use and the typical ~90 minute rhythm of sleep cycles.
 * Every tunable constant lives at the top of this object.
 */
object SleepAnalyzer {
    // --- movement thresholds (units: summed accelerometer jitter per minute) ---
    private const val STILL_T = 0.25f      // below this the minute counts as "still"
    private const val ACTIVE_T = 4.0f      // above this the minute is clearly "active"
    private const val MOVE_CAP = 20f       // one huge spike (phone pick-up) can't dominate smoothing
    private const val WAKE_SMOOTHED = 1.2f // smoothed activity above this = awake
    private const val SCREEN_WAKE_SEC = 15 // screen on this long within a minute = awake
    private const val LUX_WAKE = 10f
    private const val NOISE_EVENT_DB = 15f // louder than the room's baseline by this much = noise event

    private const val CYCLE_MIN = 90f

    private val SMOOTH_W = floatArrayOf(0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.15f, 0.10f) // offsets -4..+2

    fun analyze(
        epochs: List<EpochInput>,
        totalMinutes: Int,
        micEnabled: Boolean,
        goalMin: Int
    ): AnalysisResult {
        val n = max(totalMinutes, 1)

        // ---- 1. lay epochs on a fixed grid, filling short gaps from the previous minute ----
        val mov = FloatArray(n)
        val nAvg = FloatArray(n) { -1f }
        val nPeak = FloatArray(n) { -1f }
        val lux = FloatArray(n) { -1f }
        val screen = IntArray(n)
        val forcedWake = BooleanArray(n)
        val byIdx = HashMap<Int, EpochInput>()
        for (e in epochs) if (e.minuteIndex in 0 until n) byIdx[e.minuteIndex] = e
        var lastPresent = -1000
        for (i in 0 until n) {
            val e = byIdx[i]
            if (e != null) {
                mov[i] = e.movement; nAvg[i] = e.noiseAvg; nPeak[i] = e.noisePeak
                lux[i] = e.lux; screen[i] = e.screenSec
                lastPresent = i
            } else if (i - lastPresent <= 10 && i > 0) {
                mov[i] = mov[i - 1]; nAvg[i] = nAvg[i - 1]; nPeak[i] = nPeak[i - 1]
                lux[i] = lux[i - 1]; screen[i] = 0
            } else {
                forcedWake[i] = true // long gap with no data: don't pretend we know
            }
        }

        // ---- 2. remove the phone's own vibration floor, then smooth ----
        val present = (0 until n).filter { byIdx.containsKey(it) }.map { mov[it] }.sorted()
        val floor = if (present.isEmpty()) 0f else min(1.0f, present[(present.size * 0.25f).toInt().coerceIn(0, present.size - 1)])
        val a = FloatArray(n) { min(max(0f, mov[it] - floor), MOVE_CAP) }
        val smooth = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f; var ws = 0f
            for (k in SMOOTH_W.indices) {
                val j = i + k - 4
                if (j in 0 until n) { s += SMOOTH_W[k] * a[j]; ws += SMOOTH_W[k] }
            }
            smooth[i] = if (ws > 0f) s / ws else 0f
        }

        // ---- 3. sleep / wake per minute ----
        val asleep = BooleanArray(n) { i ->
            val wake = forcedWake[i] ||
                screen[i] >= SCREEN_WAKE_SEC ||
                smooth[i] > WAKE_SMOOTHED ||
                (lux[i] > LUX_WAKE && smooth[i] > 0.4f)
            !wake
        }
        fixShortRuns(asleep, sleepRunMin = 4, wakeRunMin = 3)

        // ---- 4. sleep onset and final wake ----
        val onsetRun = if (n < 90) 5 else 10
        var onset = -1
        run {
            var i = 0
            while (i + onsetRun <= n) {
                var ok = true
                for (j in i until i + onsetRun) if (!asleep[j]) { ok = false; break }
                if (ok) { onset = i; break }
                i++
            }
        }
        var wakeEnd = -1
        if (onset >= 0) {
            for (i in n - 1 downTo onset) if (asleep[i]) { wakeEnd = i + 1; break }
        }

        val inSleep = BooleanArray(n)
        if (onset >= 0 && wakeEnd > onset) for (i in onset until wakeEnd) inSleep[i] = asleep[i]

        // ---- 5. stage the sleeping minutes ----
        val stage = CharArray(n) { Stage.WAKE }
        val noiseBase = noiseBaseline(nAvg, byIdx.keys)
        if (onset >= 0) {
            val stillFrac = FloatArray(n)
            val twitchFrac = FloatArray(n)
            val noiseFrac = FloatArray(n)
            for (i in 0 until n) {
                var cnt = 0; var still = 0; var twitch = 0; var loud = 0
                for (j in max(0, i - 3)..min(n - 1, i + 3)) {
                    cnt++
                    if (a[j] < STILL_T) still++ else if (a[j] < ACTIVE_T) twitch++
                    if (micEnabled && nPeak[j] >= 0f && nPeak[j] - noiseBase > NOISE_EVENT_DB) loud++
                }
                stillFrac[i] = still.toFloat() / cnt
                twitchFrac[i] = twitch.toFloat() / cnt
                noiseFrac[i] = loud.toFloat() / cnt
            }
            for (i in 0 until n) {
                if (!inSleep[i]) continue
                val t = (i - onset).toFloat()
                val cycle = (t / CYCLE_MIN).toInt()
                val phase = (t % CYCLE_MIN) / CYCLE_MIN
                val st = stillFrac[i]
                val deepWin = gauss(phase, 0.30f, 0.20f)
                val remWin = gauss(phase, 0.85f, 0.15f)
                val deepDecay = exp(-0.6f * cycle)
                val remGrow = min(1.1f, 0.5f + 0.2f * cycle)
                val light = 0.35f + 0.65f * (1f - st)
                val deep = 1.4f * deepWin * deepDecay * st * st * (1f - 0.7f * noiseFrac[i])
                val rem = 1.1f * remWin * remGrow * (0.5f + 0.5f * st) * (1f + 0.4f * twitchFrac[i])
                stage[i] = when {
                    deep >= light && deep >= rem -> Stage.DEEP
                    rem >= light && rem > deep -> Stage.REM
                    else -> Stage.LIGHT
                }
            }
            smoothStages(stage, inSleep)
        }

        // ---- 6. summary numbers ----
        var light = 0; var deep = 0; var rem = 0
        for (i in 0 until n) when (stage[i]) {
            Stage.LIGHT -> light++
            Stage.DEEP -> deep++
            Stage.REM -> rem++
        }
        val totalSleep = light + deep + rem
        val periodMin = if (onset >= 0 && wakeEnd > onset) wakeEnd - onset else 0
        val waso = max(0, periodMin - totalSleep)
        var awakenings = 0
        if (periodMin > 0) {
            var i = onset
            while (i < wakeEnd) {
                if (!inSleep[i]) {
                    var j = i
                    while (j < wakeEnd && !inSleep[j]) j++
                    if (j - i >= 3) awakenings++
                    i = j
                } else i++
            }
        }
        val latency = if (onset >= 0) onset else n
        val efficiency = if (n > 0) totalSleep.toFloat() / n else 0f

        // noise events: clusters of loud minutes while asleep
        var noiseEvents = 0
        var noiseSum = 0.0; var noiseCnt = 0
        if (micEnabled && periodMin > 0) {
            var inEvent = false
            for (i in onset until wakeEnd) {
                if (nAvg[i] >= 0f) { noiseSum += nAvg[i]; noiseCnt++ }
                val loud = nPeak[i] >= 0f && nPeak[i] - noiseBase > NOISE_EVENT_DB
                if (loud && !inEvent) noiseEvents++
                inEvent = loud
            }
        }
        val avgNoise = if (noiseCnt > 0) (noiseSum / noiseCnt).toFloat() else -1f

        val bd = score(
            totalSleep, goalMin, efficiency, deep, rem, awakenings, waso,
            latency, noiseEvents, periodMin, micEnabled
        )

        return AnalysisResult(
            stages = String(stage),
            sleepOnsetMin = if (onset >= 0) onset else n,
            wakeMin = if (wakeEnd >= 0) wakeEnd else n,
            timeInBedMin = n,
            totalSleepMin = totalSleep,
            wasoMin = waso,
            lightMin = light,
            deepMin = deep,
            remMin = rem,
            latencyMin = latency,
            awakenings = awakenings,
            efficiency = efficiency,
            noiseEvents = noiseEvents,
            avgNoiseDb = avgNoise,
            score = bd.total.coerceIn(0, 100),
            breakdown = bd
        )
    }

    fun score(
        totalSleep: Int, goalMin: Int, efficiency: Float, deepMin: Int, remMin: Int,
        awakenings: Int, wasoMin: Int, latencyMin: Int, noiseEvents: Int, periodMin: Int,
        micEnabled: Boolean
    ): ScoreBreakdown {
        if (totalSleep <= 0) return ScoreBreakdown(0, 0, 0, 0, 0, 0, if (micEnabled) 0 else 4)
        val goal = max(goalMin, 1)
        val duration = 50f * clamp01(totalSleep.toFloat() / goal)
        val eff = 12f * clamp01((efficiency - 0.65f) / 0.25f)
        val deepPct = deepMin.toFloat() / totalSleep
        val remPct = remMin.toFloat() / totalSleep
        val deepPts = 10f * clamp01((deepPct - 0.03f) / 0.10f)
        val remPts = 10f * clamp01((remPct - 0.08f) / 0.12f)
        val hours = max(periodMin / 60f, 0.5f)
        val aph = awakenings / hours
        val continuity = 10f * (0.5f * clamp01(1f - aph / 4f) + 0.5f * clamp01(1f - wasoMin / 60f))
        val latencyPts = 4f * clamp01(1f - (latencyMin - 15f) / 30f)
        val env = if (!micEnabled) 4f else 4f * clamp01(1f - ((noiseEvents / hours) - 1f) / 5f)
        return ScoreBreakdown(
            duration.roundToInt(), eff.roundToInt(), deepPts.roundToInt(), remPts.roundToInt(),
            continuity.roundToInt(), latencyPts.roundToInt(), env.roundToInt()
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun clamp01(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x

    private fun gauss(x: Float, mu: Float, sigma: Float): Float {
        val d = (x - mu) / sigma
        return exp(-d * d)
    }

    private fun noiseBaseline(nAvg: FloatArray, present: Set<Int>): Float {
        val v = present.filter { it in nAvg.indices && nAvg[it] >= 0f }.map { nAvg[it] }.sorted()
        return if (v.isEmpty()) 0f else v[v.size / 2]
    }

    /** Short sleep blips inside wake become wake; short wake blips inside sleep become sleep. */
    private fun fixShortRuns(asleep: BooleanArray, sleepRunMin: Int, wakeRunMin: Int) {
        val n = asleep.size
        // wake blips inside sleep
        var i = 0
        while (i < n) {
            if (!asleep[i]) {
                var j = i
                while (j < n && !asleep[j]) j++
                if (i > 0 && j < n && j - i < wakeRunMin) for (k in i until j) asleep[k] = true
                i = j
            } else i++
        }
        // sleep blips inside wake
        i = 0
        while (i < n) {
            if (asleep[i]) {
                var j = i
                while (j < n && asleep[j]) j++
                if (j - i < sleepRunMin && (i == 0 || !asleep[i - 1])) {
                    val wakeBefore = i == 0 || !asleep[i - 1]
                    val wakeAfter = j >= n || !asleep[j]
                    if (wakeBefore && wakeAfter) for (k in i until j) asleep[k] = false
                }
                i = j
            } else i++
        }
    }

    /** Majority filter, then turn deep/REM fragments shorter than 5 minutes into light sleep. */
    private fun smoothStages(stage: CharArray, inSleep: BooleanArray) {
        val n = stage.size
        val orig = stage.copyOf()
        for (i in 0 until n) {
            if (!inSleep[i]) continue
            var l = 0; var d = 0; var r = 0
            for (j in max(0, i - 4)..min(n - 1, i + 4)) {
                if (!inSleep[j]) continue
                when (orig[j]) { Stage.LIGHT -> l++; Stage.DEEP -> d++; Stage.REM -> r++ }
            }
            val best = max(l, max(d, r))
            val cur = when (orig[i]) { Stage.LIGHT -> l; Stage.DEEP -> d; else -> r }
            if (cur < best) {
                stage[i] = when {
                    l == best -> Stage.LIGHT
                    d == best -> Stage.DEEP
                    else -> Stage.REM
                }
            }
        }
        var i = 0
        while (i < n) {
            val s = stage[i]
            if (s == Stage.DEEP || s == Stage.REM) {
                var j = i
                while (j < n && stage[j] == s) j++
                if (j - i < 5) for (k in i until j) stage[k] = Stage.LIGHT
                i = j
            } else i++
        }
    }
}
