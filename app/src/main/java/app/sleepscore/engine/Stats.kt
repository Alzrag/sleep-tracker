package app.sleepscore.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Plain snapshot of one analysed session, so the stats code has no Android/Room dependency. */
data class SessionSummary(
    val id: Long,
    val startMs: Long,
    val onsetMs: Long,
    val wakeMs: Long,
    val isNap: Boolean,
    val timeInBedMin: Int,
    val totalSleepMin: Int,
    val lightMin: Int,
    val deepMin: Int,
    val remMin: Int,
    val wasoMin: Int,
    val latencyMin: Int,
    val awakenings: Int,
    val efficiency: Float,
    val noiseEvents: Int,
    val score: Int
)

data class WeekdayStat(
    val day: DayOfWeek,
    val nights: Int,
    val avgSleepMin: Float?,
    val avgScore: Float?,
    val avgBedtimeMin: Int?     // minutes after 18:00
)

data class DayPoint(val date: LocalDate, val sleepMin: Int, val score: Int?)

data class RangeStats(
    val from: LocalDate,
    val to: LocalDate,
    val daysInRange: Int,
    val daysWithData: Int,
    val nights: Int,
    val naps: Int,
    val avgSleepPerNightMin: Int,
    val avgSleepPerDayMin: Int,      // includes naps, over days that have data
    val avgNapMin: Int,
    val avgScore: Int,
    val bestScore: Int,
    val bestScoreDate: LocalDate?,
    val worstScore: Int,
    val worstScoreDate: LocalDate?,
    val avgGoalPct: Int,
    val avgTimeInBedMin: Int,
    val avgEfficiencyPct: Int,
    val avgLightMin: Int,
    val avgDeepMin: Int,
    val avgRemMin: Int,
    val avgWasoMin: Int,
    val lightPct: Int,
    val deepPct: Int,
    val remPct: Int,
    val avgLatencyMin: Int,
    val avgAwakenings: Float,
    val avgNoiseEventsPerHour: Float,
    val avgBedtimeMin: Int?,         // minutes after 18:00, from when you tapped start
    val avgFallAsleepMin: Int?,      // minutes after 18:00
    val avgWakeMinOfDay: Int?,       // minutes after 00:00
    val fallAsleepSdMin: Int?,
    val socialJetlagMin: Int?,
    val sleepDebtTotalMin: Int,
    val sleepDebtPerDayMin: Int,
    val byWeekday: List<WeekdayStat>,
    val daily: List<DayPoint>,
    val insights: List<String>
)

object Stats {

    /** Minutes after 18:00 (so a 00:30 bedtime is 390 and sorts/averages sensibly). */
    fun minutesAfter18(ms: Long, zone: ZoneId): Int {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val mod = t.hour * 60 + t.minute
        return (mod - 18 * 60 + 1440) % 1440
    }

    fun clock12(minAfter18: Int): String {
        val mod = (minAfter18 + 18 * 60) % 1440
        val h24 = mod / 60
        val m = mod % 60
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return "%d:%02d %s".format(h12, m, if (h24 < 12) "AM" else "PM")
    }

    fun clock12FromMinuteOfDay(mod: Int): String = clock12((mod - 18 * 60 + 1440) % 1440)

    fun wakeDate(s: SessionSummary, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(s.wakeMs).atZone(zone).toLocalDate()

    fun compute(
        all: List<SessionSummary>,
        from: LocalDate,
        to: LocalDate,
        goalMin: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): RangeStats? {
        val inRange = all.filter { wakeDate(it, zone) in from..to }
        if (inRange.isEmpty()) return null
        val nights = inRange.filter { !it.isNap }
        val naps = inRange.filter { it.isNap }
        val daysInRange = (ChronoUnit.DAYS.between(from, to) + 1).toInt()

        val byDate = inRange.groupBy { wakeDate(it, zone) }
        val daysWithData = byDate.size
        val totalAllSleep = inRange.sumOf { it.totalSleepMin }

        fun avgI(list: List<SessionSummary>, f: (SessionSummary) -> Int): Int =
            if (list.isEmpty()) 0 else (list.sumOf { f(it).toLong() }.toDouble() / list.size).roundToInt()

        val nightCount = nights.size
        val sleepTotalNights = nights.sumOf { it.totalSleepMin }
        val deepTot = nights.sumOf { it.deepMin }
        val remTot = nights.sumOf { it.remMin }
        val lightTot = nights.sumOf { it.lightMin }
        val stageSum = max(1, deepTot + remTot + lightTot)

        val best = nights.maxByOrNull { it.score }
        val worst = nights.minByOrNull { it.score }

        // noise events per hour of sleep
        val noiseRates = nights.mapNotNull {
            val hrs = (it.wakeMs - it.onsetMs) / 3_600_000f
            if (hrs > 0.5f) it.noiseEvents / hrs else null
        }

        // timing
        val bedMins = nights.map { minutesAfter18(it.startMs, zone) }
        val onsetMins = nights.map { minutesAfter18(it.onsetMs, zone) }
        val wakeMods = nights.map {
            val t = Instant.ofEpochMilli(it.wakeMs).atZone(zone); t.hour * 60 + t.minute
        }
        val sd = if (onsetMins.size >= 3) {
            val mean = onsetMins.average()
            sqrt(onsetMins.sumOf { (it - mean) * (it - mean) } / onsetMins.size).roundToInt()
        } else null

        fun isWeekend(d: DayOfWeek) = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY
        val weekendNights = nights.filter { isWeekend(wakeDate(it, zone).dayOfWeek) }
        val weekdayNights = nights.filter { !isWeekend(wakeDate(it, zone).dayOfWeek) }
        fun mid(s: SessionSummary) = minutesAfter18((s.onsetMs + s.wakeMs) / 2, zone)
        val sjl = if (weekendNights.size >= 2 && weekdayNights.size >= 2)
            abs(weekendNights.map { mid(it) }.average() - weekdayNights.map { mid(it) }.average()).roundToInt()
        else null

        // sleep debt per day with data
        var debt = 0
        for ((_, list) in byDate) debt += max(0, goalMin - list.sumOf { it.totalSleepMin })

        // weekday breakdown (by the day you woke up)
        val byDow = DayOfWeek.values().map { dow ->
            val ns = nights.filter { wakeDate(it, zone).dayOfWeek == dow }
            WeekdayStat(
                day = dow,
                nights = ns.size,
                avgSleepMin = if (ns.isEmpty()) null else ns.sumOf { it.totalSleepMin }.toFloat() / ns.size,
                avgScore = if (ns.isEmpty()) null else ns.sumOf { it.score }.toFloat() / ns.size,
                avgBedtimeMin = if (ns.isEmpty()) null
                else ns.map { minutesAfter18(it.startMs, zone) }.average().roundToInt()
            )
        }

        // daily series covering the whole range (days with no data are simply absent)
        val daily = (0 until daysInRange).mapNotNull { off ->
            val d = from.plusDays(off.toLong())
            val list = byDate[d] ?: return@mapNotNull null
            val mainScore = list.filter { !it.isNap }.maxByOrNull { it.totalSleepMin }?.score
            DayPoint(d, list.sumOf { it.totalSleepMin }, mainScore)
        }

        val insights = buildInsights(nights, byDow, weekdayNights, weekendNights, bedMins, goalMin, daysWithData, debt, noiseRates, zone)

        return RangeStats(
            from = from, to = to,
            daysInRange = daysInRange, daysWithData = daysWithData,
            nights = nightCount, naps = naps.size,
            avgSleepPerNightMin = if (nightCount == 0) 0 else sleepTotalNights / nightCount,
            avgSleepPerDayMin = totalAllSleep / max(1, daysWithData),
            avgNapMin = avgI(naps) { it.totalSleepMin },
            avgScore = avgI(nights) { it.score },
            bestScore = best?.score ?: 0,
            bestScoreDate = best?.let { wakeDate(it, zone) },
            worstScore = worst?.score ?: 0,
            worstScoreDate = worst?.let { wakeDate(it, zone) },
            avgGoalPct = if (nightCount == 0) 0 else (nights.sumOf { it.totalSleepMin.toDouble() / max(1, goalMin) } / nightCount * 100).roundToInt(),
            avgTimeInBedMin = avgI(nights) { it.timeInBedMin },
            avgEfficiencyPct = if (nightCount == 0) 0 else (nights.sumOf { it.efficiency.toDouble() } / nightCount * 100).roundToInt(),
            avgLightMin = avgI(nights) { it.lightMin },
            avgDeepMin = avgI(nights) { it.deepMin },
            avgRemMin = avgI(nights) { it.remMin },
            avgWasoMin = avgI(nights) { it.wasoMin },
            lightPct = (lightTot * 100f / stageSum).roundToInt(),
            deepPct = (deepTot * 100f / stageSum).roundToInt(),
            remPct = (remTot * 100f / stageSum).roundToInt(),
            avgLatencyMin = avgI(nights) { it.latencyMin },
            avgAwakenings = if (nightCount == 0) 0f else nights.sumOf { it.awakenings }.toFloat() / nightCount,
            avgNoiseEventsPerHour = if (noiseRates.isEmpty()) 0f else noiseRates.average().toFloat(),
            avgBedtimeMin = if (bedMins.isEmpty()) null else bedMins.average().roundToInt(),
            avgFallAsleepMin = if (onsetMins.isEmpty()) null else onsetMins.average().roundToInt(),
            avgWakeMinOfDay = if (wakeMods.isEmpty()) null else wakeMods.average().roundToInt(),
            fallAsleepSdMin = sd,
            socialJetlagMin = sjl,
            sleepDebtTotalMin = debt,
            sleepDebtPerDayMin = debt / max(1, daysWithData),
            byWeekday = byDow,
            daily = daily,
            insights = insights
        )
    }

    private fun buildInsights(
        nights: List<SessionSummary>,
        byDow: List<WeekdayStat>,
        weekdayNights: List<SessionSummary>,
        weekendNights: List<SessionSummary>,
        bedMins: List<Int>,
        goalMin: Int,
        daysWithData: Int,
        debt: Int,
        noiseRates: List<Float>,
        zone: ZoneId
    ): List<String> {
        val out = ArrayList<String>()

        // sleep debt
        if (daysWithData >= 3) {
            val perDay = debt / daysWithData
            if (perDay >= 20) out += "You are averaging ${fmtDur(perDay)} short of your goal each day."
            else out += "You are meeting your sleep goal on average. Nice."
        }

        // bedtime vs score
        if (nights.size >= 6) {
            val sortedBeds = bedMins.sorted()
            val median = sortedBeds[sortedBeds.size / 2]
            val early = nights.filter { minutesAfter18(it.startMs, zone) <= median }
            val late = nights.filter { minutesAfter18(it.startMs, zone) > median }
            if (early.size >= 3 && late.size >= 3) {
                val e = early.sumOf { it.score } / early.size
                val l = late.sumOf { it.score } / late.size
                if (abs(e - l) >= 3) {
                    val better = if (e > l) "earlier" else "later"
                    out += "Nights you started at or before ${clock12(median)} averaged $e points vs $l for later starts, so $better bedtimes work better for you."
                }
            }
        }

        // weekday vs weekend duration
        if (weekdayNights.size >= 2 && weekendNights.size >= 2) {
            val wd = weekdayNights.sumOf { it.totalSleepMin } / weekdayNights.size
            val we = weekendNights.sumOf { it.totalSleepMin } / weekendNights.size
            if (abs(wd - we) >= 20) {
                out += if (we > wd) "You sleep ${fmtDur(we - wd)} longer on weekends than on weekdays. That can be a sign of weekday sleep debt."
                else "You sleep ${fmtDur(wd - we)} less on weekends than on weekdays."
            }
        }

        // best / worst weekday
        val scored = byDow.filter { it.nights >= 2 && it.avgScore != null }
        if (scored.size >= 3) {
            val b = scored.maxByOrNull { it.avgScore!! }!!
            val w = scored.minByOrNull { it.avgScore!! }!!
            if (b.avgScore!! - w.avgScore!! >= 5f) {
                out += "Best sleep when you wake up on ${dayName(b.day)}s (${b.avgScore.roundToInt()}), toughest on ${dayName(w.day)}s (${w.avgScore.roundToInt()})."
            }
        }

        // noise
        if (noiseRates.isNotEmpty()) {
            val r = noiseRates.average().toFloat()
            if (r >= 3f) out += "Noise interrupted your room about %.1f times per hour of sleep. A fan or earplugs might help.".format(r)
        }
        return out.take(4)
    }

    fun dayName(d: DayOfWeek): String = d.name.lowercase().replaceFirstChar { it.uppercase() }

    fun fmtDur(min: Int): String {
        val m = max(0, min)
        val h = m / 60
        val r = m % 60
        return if (h == 0) "${r}m" else "${h}h ${r}m"
    }
}
