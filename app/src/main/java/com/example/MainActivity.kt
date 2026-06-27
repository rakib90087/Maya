package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.data.*
import com.example.ui.*
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.scale
import com.example.ui.theme.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var repository: TaskRepository
    private lateinit var phoneManager: PhoneFeatureManager
    private lateinit var voiceManager: VoiceManager
    private lateinit var cloudTts: CloudTtsManager

    private lateinit var viewModel: AssistantViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Init Database and repositories
        db = AppDatabase.getDatabase(this)
        repository = TaskRepository(db.taskDao())

        // 2. Init Managers
        phoneManager = PhoneFeatureManager(this)
        voiceManager = VoiceManager(this)

        // 3. Init TTS
        cloudTts = CloudTtsManager(this)

        // 4. Init ViewModel with Custom Factory
        val factory = AssistantViewModelFactory(application, repository, phoneManager)
        viewModel = ViewModelProvider(this, factory)[AssistantViewModel::class.java]

        // 5. Wire Voice callbacks
        setupVoiceCallbacks()

        setContent {
            MyApplicationTheme {
                MainContentScreen(viewModel = viewModel)
            }
        }
    }

    private fun setupVoiceCallbacks() {
        // Link VM trigger -> VoiceManager start/stop
        viewModel.startListeningCallback = {
            runOnUiThread {
                voiceManager.startListening()
            }
        }
        viewModel.stopListeningCallback = {
            runOnUiThread {
                voiceManager.stopListening()
            }
        }
        viewModel.startPassiveListeningCallback = {
            runOnUiThread {
                voiceManager.startPassiveListening()
            }
        }
        viewModel.stopPassiveListeningCallback = {
            runOnUiThread {
                voiceManager.stopPassiveListening()
            }
        }

        // Link VoiceManager results/errors -> VM updates
        voiceManager.init(
            onResult = { speechText ->
                viewModel.setListening(false)
                viewModel.sendMessage(speechText)
                
                // Restart passive listening after a command is processed
                if (viewModel.wakeWordModeEnabled.value) {
                    viewModel.startPassiveListening()
                }
            },
            onError = { errorMsg ->
                viewModel.setListening(false)
                viewModel.setPartialSpeechText(errorMsg)
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                
                // Restart passive listening after an error
                if (viewModel.wakeWordModeEnabled.value) {
                    viewModel.startPassiveListening()
                }
            },
            onWakeWordDetected = { query ->
                runOnUiThread {
                    // Trigger haptic or play a short sound
                    if (query.isNullOrBlank()) {
                        viewModel.sendMessage("মায়া") // Will trigger a greeting/wake response
                    } else {
                        // Directly send the wake word query!
                        viewModel.sendMessage(query)
                    }
                }
            }
        )

        // Collect partial results from VoiceManager and set it in ViewModel
        lifecycleScope.launch {
            voiceManager.partialText.collect { text ->
                viewModel.setPartialSpeechText(text)
            }
        }

        // VM trigger -> Text to Speech
        viewModel.speakCallback = { text ->
            cloudTts.speak(
                text = text,
                onStart = {
                    viewModel.setSpeaking(true)
                },
                onComplete = {
                    viewModel.setSpeaking(false)
                    // Resume passive listening once speaking finishes
                    runOnUiThread {
                        if (viewModel.wakeWordModeEnabled.value) {
                            viewModel.startPassiveListening()
                        }
                    }
                }
            )
        }
        viewModel.stopSpeakingCallback = {
            cloudTts.stopSpeaking()
            viewModel.setSpeaking(false)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkNotificationAccess()
        viewModel.checkOverlayPermission(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cloudTts.destroy()
        voiceManager.destroy()
    }
}

enum class NavigationTab(val route: String, val title: String, val icon: ImageVector) {
    ASSISTANT("assistant", "Mayera Chat", Icons.Default.ChatBubble),
    TASKS("tasks", "Tasks", Icons.Default.Assignment),
    TELEMETRY("telemetry", "Sensors", Icons.Default.Assessment)
}

@OptIn(ExperimentalPermissionsApi::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainContentScreen(viewModel: AssistantViewModel) {
    var currentTab by remember { mutableStateOf(NavigationTab.ASSISTANT) }
    val isListening by viewModel.isListening.collectAsState()

    val permissionList = remember {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list
    }

    // Accompanist handles dangerous dynamic runtime permissions
    val permissionsState = rememberMultiplePermissionsState(
        permissions = permissionList
    )

    // Request permissions once at launch
    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    // Start passive listening automatically once RECORD_AUDIO is granted
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted && viewModel.wakeWordModeEnabled.value) {
            viewModel.startPassiveListening()
        }
    }

    Scaffold(
        bottomBar = {
            CustomBottomNavigationBar(
                currentTab = currentTab,
                onTabSelected = { currentTab = it }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            // Render correct screen depending on selected Navigation Tab
            when (currentTab) {
                NavigationTab.ASSISTANT -> {
                    AssistantScreen(
                        viewModel = viewModel,
                        onMicClick = {
                            if (!permissionsState.allPermissionsGranted) {
                                permissionsState.launchMultiplePermissionRequest()
                            } else {
                                if (isListening) {
                                    viewModel.stopVoiceRecognition()
                                } else {
                                    viewModel.startVoiceRecognition()
                                }
                            }
                        }
                    )
                }
                NavigationTab.TASKS -> {
                    TasksScreen(viewModel = viewModel)
                }
                NavigationTab.TELEMETRY -> {
                    TelemetryScreen(viewModel = viewModel)
                }
            }

            val partialSpeechText by viewModel.partialSpeechText.collectAsState()

            // Real-time floating overlay for Speech Recognition status
            AnimatedVisibility(
                visible = isListening,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                VoiceOverlay(partialText = partialSpeechText)
            }
        }
    }
}

@Composable
fun CustomBottomNavigationBar(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
                )
            )
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationTab.values().forEach { tab ->
                val isSelected = currentTab == tab
                val tintColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("tab_button_${tab.route}")
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        tint = tintColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = tab.title,
                        color = tintColor,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun VoiceOverlay(partialText: String) {
    Card(
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(2.dp, SunsetRed.copy(0.7f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
        modifier = Modifier
            .width(260.dp)
            .padding(16.dp)
            .testTag("voice_overlay_card")
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Mayera Voice Sensor",
                color = SunsetRed,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Pulsing voice rings
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(80.dp)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val ring1 by infiniteTransition.animateFloat(
                    initialValue = 0.8f, targetValue = 1.4f,
                    animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
                    label = "ring1"
                )
                val ring2 by infiniteTransition.animateFloat(
                    initialValue = 0.8f, targetValue = 1.4f,
                    animationSpec = infiniteRepeatable(animation = tween(1200, delayMillis = 600, easing = LinearEasing), repeatMode = RepeatMode.Restart),
                    label = "ring2"
                )

                Box(modifier = Modifier.size(52.dp).scale(ring1).background(SunsetRed.copy(alpha = 0.15f), CircleShape))
                Box(modifier = Modifier.size(52.dp).scale(ring2).background(SunsetRed.copy(alpha = 0.1f), CircleShape))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .background(SunsetRed, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "LISTENING LIVE",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (partialText.isNotBlank()) partialText else "Speak clearly to dictate your command.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}
