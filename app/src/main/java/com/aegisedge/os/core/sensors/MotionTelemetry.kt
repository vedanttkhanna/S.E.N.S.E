package com.aegisedge.os.core.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.math.sqrt

/**
 * IMU + GPS telemetry for Dashcam mode and forensic manifests.
 *
 * Emits [GForceEvent]s when total acceleration deviates from 1 g by more than
 * the active mode's threshold — the symbolic "crash candidate" signal that
 * Layer 3 fuses with PaliGemma trajectory logs to separate a genuine collision
 * from a pothole, and to detect "crash-for-cash" reversing fraud (spike with
 * REVERSE trajectory + no forward closure).
 */
class MotionTelemetry(private val context: Context) {

    data class GForceEvent(
        val timestampNanos: Long,
        val gForce: Float,
        val axes: FloatArray,          // raw x,y,z m/s^2
        val gyroRates: FloatArray,     // rad/s at spike time
    )

    data class Fix(val lat: Double, val lon: Double, val speedMps: Float)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lastGyro = FloatArray(3)

    /** Latest heading from the magnetometer (degrees), for manifest metadata. */
    val headingDegrees = MutableStateFlow(0f)
    val currentGForce: StateFlow<Float> get() = _currentGForce
    private val _currentGForce = MutableStateFlow(1f)

    fun gForceSpikes(thresholdG: Float = 2.5f): Flow<GForceEvent> = callbackFlow {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> e.values.copyInto(lastGyro)
                    Sensor.TYPE_MAGNETIC_FIELD ->
                        headingDegrees.value = e.values[0]   // simplified; production uses rotation vector
                    Sensor.TYPE_ACCELEROMETER -> {
                        val (x, y, z) = e.values
                        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                        _currentGForce.value = g
                        if (g >= thresholdG || g <= 1f / thresholdG) {
                            trySend(GForceEvent(e.timestamp, g, e.values.copyOf(), lastGyro.copyOf()))
                        }
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }

        listOf(accel, gyro, mag).forEach {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    /** One-shot GPS fix for stamping forensic manifests; null if unavailable. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(): Fix? = runCatching {
        val loc = LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        loc?.let { Fix(it.latitude, it.longitude, it.speed) }
    }.getOrNull()
}
