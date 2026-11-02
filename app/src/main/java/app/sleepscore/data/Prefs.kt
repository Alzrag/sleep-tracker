package app.sleepscore.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("sleepscore", Context.MODE_PRIVATE)

    private val _goalMin = MutableStateFlow(sp.getInt("goalMin", 510)) // 8.5 hours
    val goalMin: StateFlow<Int> = _goalMin

    private val _micEnabled = MutableStateFlow(sp.getBoolean("mic", true))
    val micEnabled: StateFlow<Boolean> = _micEnabled

    fun setGoal(minutes: Int) {
        sp.edit().putInt("goalMin", minutes).apply()
        _goalMin.value = minutes
    }

    fun setMic(enabled: Boolean) {
        sp.edit().putBoolean("mic", enabled).apply()
        _micEnabled.value = enabled
    }

    /** Which version of the sleep analysis last scored the saved nights. */
    var analyzerVersion: Int
        get() = sp.getInt("analyzerVersion", 1)
        set(v) { sp.edit().putInt("analyzerVersion", v).apply() }

    var activeSessionId: Long
        get() = sp.getLong("active", -1L)
        set(v) { sp.edit().putLong("active", v).apply() }
}
