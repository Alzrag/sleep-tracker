package app.sleepscore.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sleepscore.engine.SessionSummary

@Entity(tableName = "sessions")
data class SleepSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long? = null,           // null while tracking is running
    val micEnabled: Boolean = true,
    val isNap: Boolean = false,
    val analyzed: Boolean = false,
    val goalMin: Int = 510,            // sleep goal used when this night was scored
    val onsetMs: Long = 0,
    val wakeMs: Long = 0,
    val timeInBedMin: Int = 0,
    val totalSleepMin: Int = 0,
    val wasoMin: Int = 0,
    val lightMin: Int = 0,
    val deepMin: Int = 0,
    val remMin: Int = 0,
    val latencyMin: Int = 0,
    val awakenings: Int = 0,
    val efficiency: Float = 0f,
    val noiseEvents: Int = 0,
    val avgNoiseDb: Float = -1f,
    val score: Int = 0,
    val sDuration: Int = 0,
    val sEfficiency: Int = 0,
    val sDeep: Int = 0,
    val sRem: Int = 0,
    val sContinuity: Int = 0,
    val sLatency: Int = 0,
    val sEnvironment: Int = 0,
    val stages: String = ""            // one char per minute from startMs: W, L, D, R
)

@Entity(
    tableName = "epochs",
    primaryKeys = ["sessionId", "minuteIndex"],
    foreignKeys = [
        ForeignKey(
            entity = SleepSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class EpochRow(
    val sessionId: Long,
    val minuteIndex: Int,
    val movement: Float,
    val noiseAvg: Float,
    val noisePeak: Float,
    val lux: Float,
    val screenSec: Int
)

fun SleepSession.toSummary(): SessionSummary = SessionSummary(
    id = id,
    startMs = startMs,
    onsetMs = onsetMs,
    wakeMs = wakeMs,
    isNap = isNap,
    timeInBedMin = timeInBedMin,
    totalSleepMin = totalSleepMin,
    lightMin = lightMin,
    deepMin = deepMin,
    remMin = remMin,
    wasoMin = wasoMin,
    latencyMin = latencyMin,
    awakenings = awakenings,
    efficiency = efficiency,
    noiseEvents = noiseEvents,
    score = score
)
