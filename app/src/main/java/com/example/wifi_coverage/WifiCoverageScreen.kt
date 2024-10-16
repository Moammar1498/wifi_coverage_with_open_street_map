package com.example.wifi_coverage

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun WifiCoverageScreen(viewModel: WifiCoverageViewModel, requestPermissions: (Array<String>) -> Unit) {
    val permissionState by viewModel.permissionState.collectAsState()
    val coverageData by viewModel.coverageData.collectAsState()
    var isTracking by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.checkAndRequestPermissions() // Initial permission check
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when (permissionState) {
            is PermissionState.NeedsRequest -> {
                LaunchedEffect(Unit) {
                    requestPermissions((permissionState as PermissionState.NeedsRequest).permissions)
                }
            }
            is PermissionState.Denied -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Permissions denied. WiFi coverage tracking is not available.")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                            context.startActivity(intent)
                        }) {
                            Text("Open Settings")
                        }
                    }
                }
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
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator() // Show a loading spinner
                }
            }
        }
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

@Composable
fun MapView(coverageData: List<WifiMeasurement>) {
    val context = LocalContext.current
    val mapView = remember { org.osmdroid.views.MapView(context) }

    DisposableEffect(Unit) {
        Configuration.getInstance()
            .load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
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
                points = Polygon.pointsAsCircle(
                    GeoPoint(measurement.latitude, measurement.longitude),
                    50.0
                )
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