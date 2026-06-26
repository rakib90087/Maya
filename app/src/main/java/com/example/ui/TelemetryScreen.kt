package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.BatteryData
import com.example.data.ContactInfo
import com.example.data.SensorData
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun TelemetryScreen(
    viewModel: AssistantViewModel,
    modifier: Modifier = Modifier
) {
    val battery by viewModel.batteryData.collectAsState()
    val sensors by viewModel.sensorData.collectAsState()
    val specs = viewModel.deviceSpecs
    val location by viewModel.currentLocation.collectAsState()

    val context = LocalContext.current
    var contactsSearch by remember { mutableStateOf("") }
    var contactsList by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }

    // Read contacts permission
    val contactsPermissionState = rememberPermissionState(permission = Manifest.permission.READ_CONTACTS)

    // Load contacts when permission is granted or search query changes
    LaunchedEffect(contactsPermissionState.status.isGranted, contactsSearch) {
        if (contactsPermissionState.status.isGranted) {
            contactsList = viewModel.phoneManager.getContacts(contactsSearch)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header
        item {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    text = "SYSTEM TELEMETRY",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Live phone features & diagnostic sensors",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }
        }

        // 2. Battery & Location status Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Battery Box
                TelemetryCard(
                    title = "Battery Power",
                    icon = if (battery.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                    iconColor = if (battery.isCharging) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                ) {
                    BatteryIndicator(battery = battery)
                }

                // GPS Location Box
                TelemetryCard(
                    title = "GPS Coordinates",
                    icon = Icons.Default.LocationOn,
                    iconColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (location != null) {
                            Text(
                                text = "LAT: ${"%.5f".format(location!!.latitude)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "LNG: ${"%.5f".format(location!!.longitude)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Accuracy: ${location!!.accuracy.toInt()}m",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        } else {
                            Text(
                                text = "Locating...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.refreshLocation() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Retry", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // 3. Compass and Tilt (Accelerometer) widgets
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Compass Widget
                TelemetryCard(
                    title = "Digital Compass",
                    icon = Icons.Default.Explore,
                    iconColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CompassDial(bearing = sensors.compassBearing)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${sensors.compassBearing.toInt()}° ${getCardinalDirection(sensors.compassBearing)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Accelerometer Tilt Widget
                TelemetryCard(
                    title = "Tilt Level",
                    icon = Icons.Default.ScreenRotation,
                    iconColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TiltLevelBubble(ax = sensors.accelX, ay = sensors.accelY)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "X: ${"%.1f".format(sensors.accelX)} | Y: ${"%.1f".format(sensors.accelY)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        // 4. Ambient Light Sensor Card
        item {
            TelemetryCard(
                title = "Ambient Light Sensor",
                icon = if (sensors.lightLux < 10) Icons.Default.Brightness3 else Icons.Default.WbSunny,
                iconColor = if (sensors.lightLux < 10) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "${sensors.lightLux} lux",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = when {
                                sensors.lightLux == 0f -> "Pitch Black"
                                sensors.lightLux < 15f -> "Dim Ambient / Dark Room"
                                sensors.lightLux < 100f -> "Indoor Lighting"
                                sensors.lightLux < 1000f -> "Bright Office"
                                else -> "Sunny / Direct Light"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    // Light level bar
                    val percentage = (sensors.lightLux / 1000f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { percentage },
                        modifier = Modifier
                            .width(120.dp)
                            .height(8.dp)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(0.2f), CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        // 5. Contacts Integration Card
        item {
            TelemetryCard(
                title = "Device Contacts",
                icon = Icons.Default.People,
                iconColor = MaterialTheme.colorScheme.primary
            ) {
                if (contactsPermissionState.status.isGranted) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = contactsSearch,
                            onValueChange = { contactsSearch = it },
                            placeholder = { Text("Search phone contacts...", fontSize = 13.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("contacts_search_input"),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (contactsList.isEmpty()) {
                            Text(
                                text = if (contactsSearch.isEmpty()) "No contacts found on device." else "No matching contacts found.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.heightIn(max = 160.dp)
                            ) {
                                contactsList.take(5).forEach { contact ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = contact.name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = contact.number,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Maya needs permission to access contacts.",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { contactsPermissionState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("grant_contacts_permission")
                        ) {
                            Text("Grant Contacts Access", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 6. Device Specs Card
        item {
            TelemetryCard(
                title = "Device Hardware Specifications",
                icon = Icons.Default.DeveloperMode,
                iconColor = MaterialTheme.colorScheme.secondary
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    specs.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = key,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Text(
                                text = value,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp)) // padding for navigation bar
        }
    }
}

@Composable
fun TelemetryCard(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
fun BatteryIndicator(battery: BatteryData) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
    ) {
        // Battery Percentage text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${battery.level}%",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (battery.isCharging) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            )
            Text(
                text = battery.status,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = "${battery.temperature}°C",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        // Beautiful battery progress indicator
        CircularProgressIndicator(
            progress = { battery.level / 100f },
            modifier = Modifier.size(86.dp),
            color = if (battery.isCharging) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            strokeWidth = 6.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
fun CompassDial(bearing: Float) {
    val animatedBearing by animateFloatAsState(
        targetValue = bearing,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "bearingAnimation"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(90.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(0.3f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(0.2f), CircleShape)
    ) {
        // Draw Compass Dial using Canvas
        val dialColor = MaterialTheme.colorScheme.onSurface
        val primaryColor = MaterialTheme.colorScheme.primary
        val secondaryColor = MaterialTheme.colorScheme.secondary

        Canvas(modifier = Modifier.size(70.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2

            // Rotate the canvas negative to display standard heading
            rotate(degrees = -animatedBearing, pivot = center) {
                // Outer circle
                drawCircle(
                    color = dialColor.copy(alpha = 0.1f),
                    radius = radius,
                    center = center
                )

                // North Arrow
                val pathNorth = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x, center.y - radius)
                    lineTo(center.x - 6.dp.toPx(), center.y)
                    lineTo(center.x + 6.dp.toPx(), center.y)
                    close()
                }
                drawPath(pathNorth, color = primaryColor)

                // South Arrow
                val pathSouth = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x, center.y + radius)
                    lineTo(center.x - 6.dp.toPx(), center.y)
                    lineTo(center.x + 6.dp.toPx(), center.y)
                    close()
                }
                drawPath(pathSouth, color = secondaryColor.copy(alpha = 0.6f))

                // East Mark
                drawCircle(color = dialColor.copy(alpha = 0.5f), radius = 2.dp.toPx(), center = Offset(center.x + radius - 4.dp.toPx(), center.y))
                // West Mark
                drawCircle(color = dialColor.copy(alpha = 0.5f), radius = 2.dp.toPx(), center = Offset(center.x - radius + 4.dp.toPx(), center.y))
            }
        }

        // Center hub dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}

@Composable
fun TiltLevelBubble(ax: Float, ay: Float) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(90.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(0.3f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.secondary.copy(0.2f), CircleShape)
    ) {
        val bubbleColor = MaterialTheme.colorScheme.secondary
        val crosshairColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width / 2

            // Draw crosshairs
            drawLine(
                color = crosshairColor,
                start = Offset(0f, center.y),
                end = Offset(size.width, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = crosshairColor,
                start = Offset(center.x, 0f),
                end = Offset(center.x, size.height),
                strokeWidth = 1.dp.toPx()
            )

            // Draw center bullseye
            drawCircle(
                color = crosshairColor,
                radius = 12.dp.toPx(),
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )

            // Calculate bubble displacement
            // ax goes roughly -10 to 10, ay goes roughly -10 to 10
            // Map them to pixel displacement, clamping inside the circle
            val maxDisplacement = radius - 10.dp.toPx()
            val dx = (-ax / 9.81f * maxDisplacement).coerceIn(-maxDisplacement, maxDisplacement)
            val dy = (ay / 9.81f * maxDisplacement).coerceIn(-maxDisplacement, maxDisplacement)

            // Draw bubble
            drawCircle(
                color = bubbleColor,
                radius = 7.dp.toPx(),
                center = Offset(center.x + dx, center.y + dy)
            )
        }
    }
}

fun getCardinalDirection(bearing: Float): String {
    return when (bearing) {
        in 337.5f..360f, in 0f..22.5f -> "N"
        in 22.5f..67.5f -> "NE"
        in 67.5f..112.5f -> "E"
        in 112.5f..157.5f -> "SE"
        in 157.5f..202.5f -> "S"
        in 202.5f..247.5f -> "SW"
        in 247.5f..292.5f -> "W"
        in 292.5f..337.5f -> "NW"
        else -> "N"
    }
}
