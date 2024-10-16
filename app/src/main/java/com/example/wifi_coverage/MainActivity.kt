package com.example.wifi_coverage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: WifiCoverageViewModel
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            viewModel.onPermissionsGranted()
        } else {
            viewModel.onPermissionsDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = WifiCoverageViewModel(applicationContext)
        setContent {
            WifiCoverageScreen(viewModel, ::requestPermissions)
        }
    }

    private fun requestPermissions(permissions: Array<String>) {
        requestPermissionLauncher.launch(permissions)
    }
}

sealed class PermissionState {
    data object Unknown : PermissionState()
    data object Granted : PermissionState()
    data object Denied : PermissionState()
    data class NeedsRequest(val permissions: Array<String>) : PermissionState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as NeedsRequest

            return permissions.contentEquals(other.permissions)
        }

        override fun hashCode(): Int {
            return permissions.contentHashCode()
        }
    }
}

class WifiCoverageViewModel(private val context: Context) : ViewModel() {
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _coverageData = MutableStateFlow<List<WifiMeasurement>>(emptyList())
    val coverageData: StateFlow<List<WifiMeasurement>> = _coverageData

    private val _permissionState = MutableStateFlow<PermissionState>(PermissionState.Unknown)
    val permissionState: StateFlow<PermissionState> = _permissionState

    private var measurementJob: Job? = null

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

@Composable
fun WifiCoverageScreen(viewModel: WifiCoverageViewModel, requestPermissions: (Array<String>) -> Unit) {
    val permissionState by viewModel.permissionState.collectAsState()
    val coverageData by viewModel.coverageData.collectAsState()
    var isTracking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.checkAndRequestPermissions()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when (permissionState) {
            is PermissionState.NeedsRequest -> {
                LaunchedEffect(Unit) {
                    requestPermissions((permissionState as PermissionState.NeedsRequest).permissions)
                }
            }
            is PermissionState.Denied -> {
                showDialog = true
            }
            is PermissionState.Granted -> {
                Box(modifier = Modifier.weight(1f)) {
                    MapView(coverageData)
                }
                Button(
                    onClick = {
                        isTracking = !isTracking
                        if (isTracking) {
                            viewModel.startMeasurements()
                        } else {
                            viewModel.stopMeasurements()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8E44AD)
                    )
                ) {
                    Text(if (isTracking) "Stop" else "Start", color = Color.White)
                }
                Text(
                    "Move around to check WiFi Coverage at specific locations",
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> Text("Loading...")
        }
    }
    if (showDialog) {
        PermissionDialog(
            onRequestPermissions = {
                requestPermissions((permissionState as PermissionState.NeedsRequest).permissions)
                showDialog = false // Optionally hide the dialog after requesting permissions
            }
        )
    }
}

@Composable
fun PermissionDialog(onRequestPermissions: () -> Unit) {
    AlertDialog(
        onDismissRequest = {}, // Disable dismissing by tapping outside or pressing back
        title = { Text("Permission Required") },
        text = {
            Text("This app requires location and Wi-Fi permissions to function properly. Please grant the necessary permissions.")
        },
        confirmButton = {
            Button(
                onClick = onRequestPermissions
            ) {
                Text("Grant Permissions")
            }
        }
    )
}



@Composable
fun MapView(coverageData: List<WifiMeasurement>) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }

    DisposableEffect(Unit) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        onDispose {
            mapView.onDetach()
        }
    }

    AndroidView({ mapView }) { map ->
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.controller.setZoom(15.0)

        if (coverageData.isNotEmpty()) {
            val lastMeasurement = coverageData.last()
            map.controller.setCenter(GeoPoint(lastMeasurement.latitude, lastMeasurement.longitude))
        }

        map.overlays.clear()

        coverageData.forEach { measurement ->
            val marker = Marker(map)
            marker.position = GeoPoint(measurement.latitude, measurement.longitude)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.icon = ContextCompat.getDrawable(context, R.drawable.baseline_wifi_24)
            map.overlays.add(marker)

            val circle = Polygon(map).apply {
                points = Polygon.pointsAsCircle(GeoPoint(measurement.latitude, measurement.longitude), 50.0)
            }
            circle.fillPaint.apply {
                style = Paint.Style.FILL
                color = getColorForSignalStrength(measurement.strength)
            }

            map.overlays.add(circle)
        }

        map.invalidate()
    }
}

fun getColorForSignalStrength(strength: Int): Int {
    return when {
        strength > -50 -> Color(0x80FF0000).toArgb() // Strong signal (red)
        strength > -60 -> Color(0x80FFA500).toArgb() // Good signal (orange)
        strength > -70 -> Color(0x80FFFF00).toArgb() // Fair signal (yellow)
        else -> Color(0x8000FF00).toArgb() // Weak signal (green)
    }
}

data class WifiMeasurement(val latitude: Double, val longitude: Double, val strength: Int)
