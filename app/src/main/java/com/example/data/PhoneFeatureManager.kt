package com.example.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SensorData(
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val lightLux: Float = 0f,
    val compassBearing: Float = 0f
)

data class BatteryData(
    val level: Int = 100,
    val status: String = "Unknown",
    val temperature: Float = 0f,
    val isCharging: Boolean = false,
    val health: String = "Good"
)

data class ContactInfo(
    val name: String,
    val number: String
)

class PhoneFeatureManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val magneticSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Flows for real-time sensor updates
    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    // Flow for battery updates
    private val _batteryData = MutableStateFlow(BatteryData())
    val batteryData: StateFlow<BatteryData> = _batteryData.asStateFlow()

    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
                
                val statusInt = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING || 
                                 statusInt == BatteryManager.BATTERY_STATUS_FULL
                val status = when (statusInt) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "Full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                    else -> "Unknown"
                }

                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                val healthInt = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                val health = when (healthInt) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheated"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                    else -> "Unknown"
                }

                _batteryData.value = BatteryData(
                    level = batteryPct,
                    status = status,
                    temperature = temp,
                    isCharging = isCharging,
                    health = health
                )
            }
        }
    }

    init {
        // Register battery receiver
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startSensorUpdates()
    }

    fun startSensorUpdates() {
        accelerometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        lightSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magneticSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stopSensorUpdates() {
        sensorManager?.unregisterListener(this)
    }

    fun cleanup() {
        stopSensorUpdates()
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e("PhoneFeatureManager", "Error unregistering receiver: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                gravity = event.values.clone()
                _sensorData.value = _sensorData.value.copy(
                    accelX = event.values[0],
                    accelY = event.values[1],
                    accelZ = event.values[2]
                )
                calculateCompassBearing()
            }
            Sensor.TYPE_LIGHT -> {
                _sensorData.value = _sensorData.value.copy(
                    lightLux = event.values[0]
                )
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic = event.values.clone()
                calculateCompassBearing()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    private fun calculateCompassBearing() {
        if (gravity.isNotEmpty() && geomagnetic.isNotEmpty()) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            val success = SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)
            if (success) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                // Convert azimuth to degrees
                var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (azimuth < 0) {
                    azimuth += 360f
                }
                _sensorData.value = _sensorData.value.copy(
                    compassBearing = azimuth
                )
            }
        }
    }

    // Read real contacts with permission check
    @SuppressLint("Range")
    fun getContacts(searchQuery: String = ""): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                if (searchQuery.isNotEmpty()) "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" else null,
                if (searchQuery.isNotEmpty()) arrayOf("%$searchQuery%") else null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    if (nameIndex >= 0 && numberIndex >= 0) {
                        val name = it.getString(nameIndex) ?: ""
                        val number = it.getString(numberIndex) ?: ""
                        if (name.isNotEmpty() && number.isNotEmpty() && contacts.none { c -> c.name == name }) {
                            contacts.add(ContactInfo(name, number))
                            if (contacts.size >= 50) break // limit to 50
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PhoneFeatureManager", "Error querying contacts: ${e.message}")
        }
        return contacts
    }

    // Read real last location with permissions
    @SuppressLint("MissingPermission")
    fun getLastLocation(onLocationResult: (Location?) -> Unit) {
        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    onLocationResult(location)
                }
                .addOnFailureListener {
                    onLocationResult(null)
                }
        } catch (e: Exception) {
            Log.e("PhoneFeatureManager", "Error getting location: ${e.message}")
            onLocationResult(null)
        }
    }

    fun getDeviceSpecs(): Map<String, String> {
        return mapOf(
            "Model" to Build.MODEL,
            "Brand" to Build.BRAND,
            "Device" to Build.DEVICE,
            "Hardware" to Build.HARDWARE,
            "Android OS" to Build.VERSION.RELEASE,
            "SDK Version" to Build.VERSION.SDK_INT.toString(),
            "Manufacturer" to Build.MANUFACTURER,
            "CPU ABI" to Build.SUPPORTED_ABIS.firstOrNull().toString()
        )
    }
}
