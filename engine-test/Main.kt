// Offline sanity test for the sleep engine. Run with run-engine-test.sh (needs kotlinc).
// Generates simulated nights with a KNOWN structure and checks the analyzer's output is sensible.
import app.sleepscore.engine.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

private val zone = ZoneId.of("America/Denver")

/** Truth stages per minute for a plausible night: 90-min cycles, deep early, REM late. */
fun truth(minutesAsleep: Int): CharArray {
    val t = CharArray(minutesAsleep)
    for (i in 0 until minutesAsleep) {
        val cycle = i / 90
        val ph = (i % 90) / 90f
        t[i] = when {
            ph in 0.10f..0.45f && cycle < 3 -> 'D'
            ph >= 0.80f -> 'R'
            else -> 'L'
        }
    }
    return t
}

fun simulateNight(
    rnd: Random, sleepHours: Float, latency: Int = 14, midWakeAt: Int? = 210, mic: Boolean = true,
    restlessFactor: Float = 1f
): Pair<List<EpochInput>, Int> {
    val asleepMin = (sleepHours * 60).toInt()
    val tr = truth(asleepMin)
    val total = latency + asleepMin + 9
    val ep = ArrayList<EpochInput>()
    for (i in 0 until total) {
        var mv: Float; var screen = 0; var lux = 0f
        val st: Char
        if (i < latency) {
            st = 'W'
            mv = if (i < 3) 8f + rnd.nextFloat() * 10 else if (rnd.nextFloat() < 0.15f) 1.5f + rnd.nextFloat() * 3 else rnd.nextFloat() * 0.3f
            if (i < 2) { screen = 40; lux = 30f }
        } else if (i >= latency + asleepMin) {
            st = 'W'; mv = 2f + rnd.nextFloat() * 6; if (i == total - 3) screen = 30
        } else {
            val s = i - latency
            val wakeNow = midWakeAt != null && s in midWakeAt until midWakeAt + 8
            if (wakeNow) { st = 'W'; mv = 2f + rnd.nextFloat() * 6; if (s == midWakeAt + 2) { screen = 25; lux = 20f } }
            else {
                st = tr[s]
                val rollP = (when (st) { 'D' -> 0.01f; 'R' -> 0.05f; else -> 0.06f }) * restlessFactor
                mv = if (rnd.nextFloat() < rollP) 1.5f + rnd.nextFloat() * 4 else rnd.nextFloat() * 0.15f
                if (st == 'R' && rnd.nextFloat() < 0.12f) mv += 0.4f + rnd.nextFloat() * 0.8f
            }
        }
        mv += 0.05f + rnd.nextFloat() * 0.05f // device vibration floor
        var avg = -1f; var peak = -1f
        if (mic) {
            avg = 30f + rnd.nextFloat() * 2
            peak = avg + 3 + rnd.nextFloat() * 3
            if (rnd.nextFloat() < 0.008f) peak = avg + 22f // occasional loud event
        }
        ep.add(EpochInput(i, mv, avg, peak, lux, screen))
    }
    return ep to total
}

fun hyp(stages: String, step: Int = 15): String {
    val sb = StringBuilder()
    var i = 0
    while (i < stages.length) {
        val w = stages.substring(i, minOf(stages.length, i + step))
        val best = "WLDR".maxByOrNull { c -> w.count { it == c } }!!
        sb.append(best); i += step
    }
    return sb.toString()
}

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "  PASS " else "  FAIL ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

fun main() {
    val goal = 510
    println("== Typical 7.5h night with one mid-night waking ==")
    val rnd = Random(42)
    val (ep, total) = simulateNight(rnd, 7.5f)
    val r = SleepAnalyzer.analyze(ep, total, true, goal)
    println("hypnogram (15 min blocks): ${hyp(r.stages)}")
    println("asleep=${r.totalSleepMin} onset=${r.sleepOnsetMin} wake=${r.wakeMin} bed=${r.timeInBedMin} light=${r.lightMin} deep=${r.deepMin} rem=${r.remMin} waso=${r.wasoMin} awak=${r.awakenings} eff=${"%.2f".format(r.efficiency)} noise=${r.noiseEvents} score=${r.score} ${r.breakdown}")
    check("sleep duration close to truth (450)", r.totalSleepMin in 420..465, "${r.totalSleepMin}")
    // A phone cannot see someone lying awake and still, so latency is a LOWER BOUND on the truth (14).
    check("latency is a plausible lower bound (<=20)", r.latencyMin in 0..20, "${r.latencyMin}")
    check("deep share 8-30%", r.deepMin * 100f / r.totalSleepMin in 8f..30f, "%.1f".format(r.deepMin * 100f / r.totalSleepMin))
    check("REM share 12-35%", r.remMin * 100f / r.totalSleepMin in 12f..35f, "%.1f".format(r.remMin * 100f / r.totalSleepMin))
    check("mid-night waking detected", r.awakenings >= 1, "${r.awakenings}")
    check("typical 7.5h night scores 75-95", r.score in 75..95, "${r.score}")
    check("stages length == minutes", r.stages.length == total)

    println("== Short bad night (4h, restless) ==")
    val (ep2, t2) = simulateNight(Random(7), 4f, midWakeAt = 90, restlessFactor = 3f)
    val r2 = SleepAnalyzer.analyze(ep2, t2, true, goal)
    println("hypnogram: ${hyp(r2.stages)}  asleep=${r2.totalSleepMin} score=${r2.score}")
    check("short night scores lower than typical", r2.score < r.score, "${r2.score} < ${r.score}")
    check("duration points reflect 3.7/8.5h of 50", r2.breakdown.duration in 18..26, "${r2.breakdown.duration}")

    println("== Full 8.5h night, no microphone ==")
    val (ep3, t3) = simulateNight(Random(3), 8.5f, mic = false, midWakeAt = null)
    val r3 = SleepAnalyzer.analyze(ep3, t3, false, goal)
    println("hypnogram: ${hyp(r3.stages)}  asleep=${r3.totalSleepMin} score=${r3.score} ${r3.breakdown}")
    check("no-mic: noise fields neutral", r3.avgNoiseDb < 0f && r3.noiseEvents == 0)
    check("no-mic: full duration points", r3.breakdown.duration >= 47, "${r3.breakdown.duration}")
    check("good night scores higher than typical", r3.score >= r.score, "${r3.score}")

    println("== Edge cases ==")
    val e0 = SleepAnalyzer.analyze(emptyList(), 30, true, goal)
    check("no data -> zero sleep, no crash", e0.totalSleepMin == 0 && e0.score <= 5, "score=${e0.score}")
    val e1 = SleepAnalyzer.analyze(emptyList(), 0, true, goal)
    check("zero minutes -> no crash", e1.totalSleepMin == 0)
    val gap = ep.filter { it.minuteIndex < 100 || it.minuteIndex > 160 }
    val eg = SleepAnalyzer.analyze(gap, total, true, goal)
    println("with 60 min gap: hyp=${hyp(eg.stages)} asleep=${eg.totalSleepMin} score=${eg.score}")
    check("gap handled, still sensible", eg.totalSleepMin in 300..460, "${eg.totalSleepMin}")
    val allAwake = (0 until 60).map { EpochInput(it, 12f, 40f, 50f, 100f, 50) }
    val ea = SleepAnalyzer.analyze(allAwake, 60, true, goal)
    check("phone in use all hour -> no sleep", ea.totalSleepMin == 0, "${ea.totalSleepMin}")

    println("== Stats over 30 simulated days (with naps) ==")
    val sessions = ArrayList<SessionSummary>()
    val base = ZonedDateTime.of(2026, 9, 28, 22, 45, 0, 0, zone)
    val sr = Random(99)
    for (d in 0 until 30) {
        val hours = 6f + sr.nextFloat() * 2.5f
        val (e, tm) = simulateNight(sr, hours, latency = 8 + sr.nextInt(20), midWakeAt = if (sr.nextBoolean()) 200 else null)
        val a = SleepAnalyzer.analyze(e, tm, true, goal)
        val start = base.plusDays(d.toLong()).plusMinutes(sr.nextInt(90).toLong() - 30)
        val startMs = start.toInstant().toEpochMilli()
        sessions += SessionSummary(
            d.toLong(), startMs, startMs + a.sleepOnsetMin * 60_000L, startMs + a.wakeMin * 60_000L, false,
            a.timeInBedMin, a.totalSleepMin, a.lightMin, a.deepMin, a.remMin, a.wasoMin, a.latencyMin,
            a.awakenings, a.efficiency, a.noiseEvents, a.score
        )
        if (d % 5 == 0) {
            val ns = start.plusHours(15).toInstant().toEpochMilli()
            sessions += SessionSummary(1000L + d, ns, ns + 5 * 60_000L, ns + 45 * 60_000L, true, 50, 38, 30, 8, 0, 0, 5, 0, 0.76f, 0, 40)
        }
    }
    val st = Stats.compute(sessions, LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 28), goal, zone)!!
    println("nights=${st.nights} naps=${st.naps} daysWithData=${st.daysWithData}/${st.daysInRange}")
    println("avg/night=${Stats.fmtDur(st.avgSleepPerNightMin)} avg/day=${Stats.fmtDur(st.avgSleepPerDayMin)} score=${st.avgScore} (best ${st.bestScore}, worst ${st.worstScore}) goal%=${st.avgGoalPct}")
    println("stages: light ${st.lightPct}% deep ${st.deepPct}% rem ${st.remPct}% | latency ${st.avgLatencyMin}m awak ${"%.1f".format(st.avgAwakenings)} eff ${st.avgEfficiencyPct}%")
    println("bed ${st.avgBedtimeMin?.let { Stats.clock12(it) }} asleep ${st.avgFallAsleepMin?.let { Stats.clock12(it) }} wake ${st.avgWakeMinOfDay?.let { Stats.clock12FromMinuteOfDay(it) }} sd=${st.fallAsleepSdMin} sjl=${st.socialJetlagMin} debt/day=${st.sleepDebtPerDayMin}m")
    st.byWeekday.forEach { println("  ${it.day}: n=${it.nights} sleep=${it.avgSleepMin?.let { m -> Stats.fmtDur(m.toInt()) }} score=${it.avgScore?.toInt()} bed=${it.avgBedtimeMin?.let { b -> Stats.clock12(b) }}") }
    st.insights.forEach { println("  insight: $it") }
    check("30 nights counted", st.nights == 30, "${st.nights}")
    check("naps counted", st.naps == 6, "${st.naps}")
    check("avg per day > avg per night (naps add)", st.avgSleepPerDayMin > st.avgSleepPerNightMin)
    check("stage pcts sum ~100", st.lightPct + st.deepPct + st.remPct in 98..102)
    check("7 weekday rows", st.byWeekday.size == 7)
    check("range filter works", Stats.compute(sessions, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), goal, zone)!!.nights in 6..8)
    check("empty range -> null", Stats.compute(sessions, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 7), goal, zone) == null)

    println()
    println(if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
