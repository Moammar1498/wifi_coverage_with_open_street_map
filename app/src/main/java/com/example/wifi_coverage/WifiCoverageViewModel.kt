package com.example.wifi_coverage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WifiCoverageViewModel(private val context: Context) : ViewModel() {
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _coverageData = MutableStateFlow<List<WifiMeasurement>>(emptyList())
    val coverageData: StateFlow<List<WifiMeasurement>> = _coverageData

    private val _permissionState = MutableStateFlow<PermissionState>(PermissionState.Unknown)
    val permissionState: StateFlow<PermissionState> = _permissionState

    private var measurementJob: Job? = null

    init {
        checkAndRequestPermissions()
    }

    fun refreshPermissionState() {
        val locationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val wifiPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE)

        if (locationPermission == PackageManager.PERMISSION_GRANTED && wifiPermission == PackageManager.PERMISSION_GRANTED) {
            _permissionState.value = PermissionState.Granted
        } else {
            _permissionState.value = PermissionState.Denied
        }
    }

    fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            _permissionState.value = PermissionState.Granted
        } else {
            _permissionState.value = PermissionState.NeedsRequest(permissions)
        }
    }

    fun onPermissionsGranted() {
        _permissionState.value = PermissionState.Granted
    }

    fun onPermissionsDenied() {
        _permissionState.value = PermissionState.Denied
    }

    fun startMeasurements() {
        if (_permissionState.value != PermissionState.Granted) return

        measurementJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val measurement = getCurrentWifiMeasurement()
                    _coverageData.value += measurement
                } catch (e: SecurityException) {
                    _permissionState.value = PermissionState.Denied
                    break
                }
                delay(5000) // Measure every 5 seconds
            }
        }
    }

    fun stopMeasurements() {
        measurementJob?.cancel()
    }

    private fun getCurrentWifiMeasurement(): WifiMeasurement {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Location permission not granted")
        }

        val scanResults =
            wifiManager.scanResults.filter { it.level > -100 } // Filter out very weak signals

        val averageSignalStrength = scanResults.map { it.level }.average().toInt()
        val location = getLastKnownLocation()

        return WifiMeasurement(location.latitude, location.longitude, averageSignalStrength)
    }

    private fun getLastKnownLocation(): Location {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Location permission not granted")
        }

        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val location = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                bestLocation = location
            }
        }
        return bestLocation ?: throw IllegalStateException("Unable to get location")
    }
}
