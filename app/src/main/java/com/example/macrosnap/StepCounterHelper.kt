package com.example.macrosnap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.coroutines.resume

/**
 * Reads the hardware TYPE_STEP_COUNTER sensor (cumulative steps since boot) and converts it into
 * "steps today" by storing a per-day baseline. Handles device reboots mid-day by carrying over
 * steps counted before the counter reset.
 *
 * Note: the daily baseline is captured the first time the app reads the sensor each day, so steps
 * taken before the first app open of the day are not counted.
 */
class StepCounterHelper(context: Context) {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val stepSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val prefs = appContext.getSharedPreferences("macrosnap_prefs", Context.MODE_PRIVATE)

    val isAvailable: Boolean get() = stepSensor != null

    suspend fun readTodaySteps(timeoutMs: Long = 4_000): Int? {
        val manager = sensorManager ?: return null
        val sensor = stepSensor ?: return null

        val totalSinceBoot = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Float> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0])
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        } ?: return null

        return computeTodaySteps(totalSinceBoot.toLong())
    }

    @Synchronized
    private fun computeTodaySteps(totalSinceBoot: Long): Int {
        val today = LocalDate.now().toEpochDay()
        val savedDay = prefs.getLong(KEY_DAY, -1L)
        var baseline = prefs.getLong(KEY_BASELINE, -1L)
        var carried = prefs.getLong(KEY_CARRIED, 0L)
        val lastTotal = prefs.getLong(KEY_LAST_TOTAL, -1L)

        if (savedDay != today || baseline < 0) {
            // New day (or first run): start counting from the current reading.
            baseline = totalSinceBoot
            carried = 0L
        } else if (lastTotal >= 0 && totalSinceBoot < lastTotal) {
            // Device rebooted: the counter restarted from zero.
            carried += (lastTotal - baseline).coerceAtLeast(0L)
            baseline = 0L
        }

        prefs.edit()
            .putLong(KEY_DAY, today)
            .putLong(KEY_BASELINE, baseline)
            .putLong(KEY_CARRIED, carried)
            .putLong(KEY_LAST_TOTAL, totalSinceBoot)
            .apply()

        return (carried + (totalSinceBoot - baseline)).coerceAtLeast(0L).toInt()
    }

    companion object {
        private const val KEY_DAY = "step_baseline_day"
        private const val KEY_BASELINE = "step_baseline_value"
        private const val KEY_CARRIED = "step_carried_value"
        private const val KEY_LAST_TOTAL = "step_last_total"

        /** ~40 kcal per 1,000 steps, matching the manual activity dialog estimate. */
        fun caloriesForSteps(steps: Int): Int = ((steps / 1000.0) * 40).toInt()
    }
}
