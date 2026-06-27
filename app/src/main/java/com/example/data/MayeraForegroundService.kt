package com.example.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.BuildConfig
import com.example.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.BorderStroke

class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val myViewModelStore = ViewModelStore()

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun resume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        myViewModelStore.clear()
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = myViewModelStore
}

class MayeraForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "mayera_foreground_service_channel"
        const val NOTIFICATION_ID = 49132
        const val ACTION_START = "START_MAYERA_BACKGROUND"
        const val ACTION_STOP = "STOP_MAYERA_BACKGROUND"

        var isServiceRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private lateinit var lifecycleOwner: ServiceLifecycleOwner
    private var params: WindowManager.LayoutParams? = null

    private lateinit var cloudTts: CloudTtsManager
    private var voiceManager: VoiceManager? = null
    private var phoneManager: PhoneFeatureManager? = null
    private var db: AppDatabase? = null
    private var repository: TaskRepository? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Live overlay speech / status states
    private val _statusText = MutableStateFlow("Mayera stands ready")
    private val _isListening = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                    Log.d("MayeraForegroundService", "Ringing phone number: $number")
                    serviceScope.launch {
                        val contacts = withContext(Dispatchers.IO) {
                            phoneManager?.getContacts() ?: emptyList()
                        }
                        val contactName = contacts.find { 
                            val cleanNum = it.number.replace("[^0-9]".toRegex(), "")
                            val cleanIncoming = number.replace("[^0-9]".toRegex(), "")
                            cleanNum.endsWith(cleanIncoming.takeLast(8)) || cleanIncoming.endsWith(cleanNum.takeLast(8))
                        }?.name ?: number.ifEmpty { "কেউ একজন" }
                        
                        speak("$contactName আপনাকে কল করছেন।")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        lifecycleOwner = ServiceLifecycleOwner()
        lifecycleOwner.start()
        lifecycleOwner.resume()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Init databases and Managers
        db = AppDatabase.getDatabase(this)
        repository = TaskRepository(db!!.taskDao())
        phoneManager = PhoneFeatureManager(this)
        voiceManager = VoiceManager(this)

        // Initialize TTS
        cloudTts = CloudTtsManager(this)
        cloudTts.speak("মায়েরা ব্যাকগ্রাউন্ড অ্যাসিস্ট্যান্ট সচল হয়েছে।")

        // Initialize notification channel
        createNotificationChannel()

        // Register phone receiver
        val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(phoneStateReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(phoneStateReceiver, intentFilter)
        }

        // Listen to notification listener updates dynamically as well
        serviceScope.launch {
            NotificationEvents.events.collectLatest { event ->
                // Announce out loud if not on home call (handled by receiver for extra precision)
                if (event.type == NotificationType.CALL && event.appName == "Phone Call") {
                    // Handled by our receiver
                } else {
                    val message = when (event.type) {
                        NotificationType.CALL -> "${event.sender} আপনাকে ${event.appName} এ কল করছেন।"
                        NotificationType.MESSAGE -> "${event.sender} আপনাকে ${event.appName} এ লিখেছেন: \"${event.content}\"।"
                        else -> "${event.appName} থেকে নতুন নোটিফিকেশন এসেছে: ${event.sender}। ${event.content}"
                    }
                    speak(message)
                }
            }
        }

        // Setup speech recognition callbacks
        voiceManager?.init(
            onResult = { result ->
                _isListening.value = false
                processVoiceCommand(result)
            },
            onError = { error ->
                _isListening.value = false
                _statusText.value = error
            }
        )

        // Watch partial speech text to display in real-time
        serviceScope.launch {
            voiceManager?.partialText?.collect { text ->
                if (text.isNotEmpty() && _isListening.value) {
                    _statusText.value = text
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Show floating assistant overlay if permission is granted
        if (Settings.canDrawOverlays(this)) {
            showFloatingOverlay()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        removeFloatingOverlay()
        lifecycleOwner.destroy()
        cloudTts.destroy()
        voiceManager?.destroy()
        try {
            unregisterReceiver(phoneStateReceiver)
        } catch (e: Exception) {
            Log.e("MayeraForegroundService", "Error unregistering receiver", e)
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun speak(text: String) {
        cloudTts.speak(text)
    }

    private fun processVoiceCommand(command: String) {
        _isGenerating.value = true
        _statusText.value = "Mayera is thinking..."

        serviceScope.launch {
            try {
                // Get fresh context data
                val battery = phoneManager?.batteryData?.value
                val sensors = phoneManager?.sensorData?.value
                
                val loc = suspendCoroutine<android.location.Location?> { cont ->
                    phoneManager?.getLastLocation { location ->
                        cont.resume(location)
                    }
                }
                
                val tasks = withContext(Dispatchers.IO) { 
                    repository?.allTasks?.firstOrNull() ?: emptyList() 
                }
                val contacts = withContext(Dispatchers.IO) { phoneManager?.getContacts()?.take(15) ?: emptyList() }

                val contactsList = contacts.joinToString(", ") { "${it.name} (${it.number})" }
                    .ifEmpty { "No contacts available" }
                val batteryStr = battery?.let { "${it.level}% (${it.status})" } ?: "Unknown"
                val sensorsStr = sensors?.let { "Accel(x=${it.accelX}, y=${it.accelY})" } ?: "Unknown"
                val locationStr = loc?.let { "Lat=${it.latitude}, Lng=${it.longitude}" } ?: "Unknown"

                val systemInstructionText = """
                    You are Mayera (মায়েরা), a highly advanced, ultra-responsive, respectful, and warm female AI Voice and Personal Assistant.
                    You MUST respond in fluent, natural Bengali (চলতি বাংলা) like a highly respectful, loyal, and supportive female AI companion.
                    
                    CRITICAL RULES FOR IDENTITY AND ADDRESSING:
                    1. Name Change: Your name is "Mayera" (মায়েরা). You must always introduce yourself only as Mayera.
                    2. User Title: You MUST strictly address the user as "Boss" (বস) in EVERY response and in EVERY sentence you speak or write. Never call the user by name or any generic/informal title.
                    3. Tone & Personality: Maintain an extremely respectful, loyal, devoted, and supportive female AI persona. Your Bengali must be smooth, polite, natural, and affectionate yet highly professional.
                    
                    Keep answers extremely concise and action-oriented.
                    
                    Supported action commands (always start response with the command if requested):
                    1. [CALL: PhoneNumber] - Call a phone number.
                    2. [SMS: PhoneNumber | MessageText] - Send SMS.
                    3. [WHATSAPP: PhoneNumber | MessageText] - Send WhatsApp message.
                    4. [ADD_TASK: Title | Category | Description] - Add a task. Category is Work, Personal, Health, Shopping, Other.
                    5. [LAUNCH_APP: AppName] - Launch app by name (e.g. YouTube).
                    6. [GO_HOME] - Go to home screen.
                    7. [FLASHLIGHT: ON] or [FLASHLIGHT: OFF] - Turn flashlight on or off.
                    
                    Current Phone Status:
                    - Battery status: $batteryStr
                    - Sensor status: $sensorsStr
                    - Current Location: $locationStr
                    - Top Contacts: $contactsList
                    
                    Spoken feedback must be brief, warm Bengali. Do not explain command tags. Just prepend the command tag if performing an action, and write Bengali speech response addressing the user as Boss.
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = command)))),
                    systemInstruction = Content(parts = listOf(Part(text = systemInstructionText))),
                    generationConfig = GenerationConfig(temperature = 0.5f)
                )

                val apiKey = ApiKeyManager.getApiKey(this@MayeraForegroundService)
                var rawResult = ""

                withContext(Dispatchers.IO) {
                    val responseBody = RetrofitClient.service.generateContentStream(
                        model = "gemini-3.5-flash",
                        apiKey = apiKey,
                        request = request
                    )

                    responseBody.byteStream().bufferedReader().use { reader ->
                        var line: String?
                        val textRegex = """"text"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
                        while (reader.readLine().also { line = it } != null) {
                            val currentLine = line ?: continue
                            val matchResults = textRegex.findAll(currentLine)
                            for (match in matchResults) {
                                val escapedText = match.groups[1]?.value ?: continue
                                val unescaped = unescapeJsonString(escapedText)
                                if (unescaped.isNotEmpty()) {
                                    rawResult += unescaped
                                    // Update streaming text in overlay in real-time
                                    _statusText.value = getCleanDisplayResponse(rawResult)
                                }
                            }
                        }
                    }
                }

                // Execute actions
                var cleanAiText = rawResult.trim()
                if (cleanAiText.startsWith("[")) {
                    val endIndex = cleanAiText.indexOf("]")
                    if (endIndex != -1) {
                        val commandTag = cleanAiText.substring(1, endIndex)
                        cleanAiText = cleanAiText.substring(endIndex + 1).trim()
                        
                        val colonIndex = commandTag.indexOf(":")
                        val cmdType = if (colonIndex != -1) commandTag.substring(0, colonIndex).trim().uppercase() else commandTag.trim().uppercase()
                        val cmdArgs = if (colonIndex != -1) commandTag.substring(colonIndex + 1).trim() else ""

                        withContext(Dispatchers.Main) {
                            when (cmdType) {
                                "CALL" -> phoneManager?.makeCall(cmdArgs)
                                "SMS" -> {
                                    val parts = cmdArgs.split("|").map { it.trim() }
                                    phoneManager?.sendSMS(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "")
                                }
                                "WHATSAPP" -> {
                                    val parts = cmdArgs.split("|").map { it.trim() }
                                    phoneManager?.sendWhatsAppMessage(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "")
                                }
                                "ADD_TASK" -> {
                                    val parts = cmdArgs.split("|").map { it.trim() }
                                    val title = parts.getOrNull(0) ?: "New Task"
                                    val cat = parts.getOrNull(1) ?: "Personal"
                                    val desc = parts.getOrNull(2) ?: ""
                                    serviceScope.launch(Dispatchers.IO) {
                                        repository?.insert(Task(title = title, category = cat, description = desc))
                                    }
                                }
                                "LAUNCH_APP" -> phoneManager?.launchApp(cmdArgs)
                                "GO_HOME" -> {
                                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    startActivity(homeIntent)
                                }
                                "FLASHLIGHT" -> {
                                    if (cmdArgs.uppercase() == "ON") {
                                        phoneManager?.toggleFlashlight(true)
                                    } else {
                                        phoneManager?.toggleFlashlight(false)
                                    }
                                }
                            }
                        }
                    }
                }

                _statusText.value = cleanAiText
                speak(cleanAiText)

            } catch (e: Exception) {
                Log.e("MayeraForegroundService", "Error processing command", e)
                _statusText.value = "Error: ${e.localizedMessage}"
                speak("দুঃখিত, আমি সংযোগ করতে পারছি না।")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun getCleanDisplayResponse(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("[")) {
            val idx = text.indexOf("]")
            if (idx != -1) {
                text = text.substring(idx + 1).trim()
            }
        }
        return text
    }

    private fun unescapeJsonString(str: String): String {
        return str.replace("\\n", "\n")
                  .replace("\\t", "\t")
                  .replace("\\\"", "\"")
                  .replace("\\\\", "\\")
    }

    // Floating Overlay logic
    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingOverlay() {
        if (overlayView != null) return

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 250
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                var isExpanded by remember { mutableStateOf(false) }
                val statusText by _statusText.collectAsState()
                val isListeningState by _isListening.collectAsState()
                val isGeneratingState by _isGenerating.collectAsState()

                // Request focus update dynamically on layout resize
                LaunchedEffect(isExpanded) {
                    updateOverlayFocus(isExpanded)
                }

                MyApplicationTheme {
                    Box(
                        modifier = Modifier
                            .wrapContentSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    params?.let { p ->
                                        p.x += dragAmount.x.toInt()
                                        p.y += dragAmount.y.toInt()
                                        windowManager.updateViewLayout(overlayView, p)
                                    }
                                }
                            }
                    ) {
                        if (!isExpanded) {
                            // Minimised bubble UI
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(SpaceBlue, Color(0xFF131B2E))
                                        )
                                    )
                                    .border(2.dp, SunsetRed, CircleShape)
                                    .clickable { isExpanded = true }
                            ) {
                                // Pulsing core visual helper
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val scale by infiniteTransition.animateFloat(
                                    initialValue = 1f,
                                    targetValue = 1.2f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "scale"
                                )

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .scale(if (isListeningState) scale else 1f)
                                        .background(SunsetRed.copy(0.2f), CircleShape)
                                        .border(1.5.dp, SunsetRed, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Mayera Overlay",
                                        tint = SunsetRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        } else {
                            // Expanded Panel UI
                            Card(
                                modifier = Modifier
                                    .width(320.dp)
                                    .padding(8.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = SpaceBlue),
                                border = BorderStroke(1.dp, SunsetRed.copy(0.4f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    // Top drag-handle and headers
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(SunsetRed, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "মায়েরা অ্যাসিস্ট্যান্ট",
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 14.sp
                                            )
                                        }

                                        Row {
                                            IconButton(
                                                onClick = {
                                                    // Start App
                                                    val appIntent = Intent(this@MayeraForegroundService, MainActivity::class.java).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    startActivity(appIntent)
                                                    isExpanded = false
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.OpenInNew,
                                                    contentDescription = "Open App",
                                                    tint = Color.White.copy(0.7f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                            IconButton(
                                                onClick = { isExpanded = false },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Minimize,
                                                    contentDescription = "Minimize",
                                                    tint = Color.White.copy(0.7f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Status / Response text scroll view
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 60.dp, max = 150.dp)
                                            .background(
                                                Color(0xFF090D16),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                0.5.dp,
                                                Color.White.copy(alpha = 0.1f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = statusText,
                                            color = Color.White.copy(0.9f),
                                            fontSize = 13.sp,
                                            textAlign = TextAlign.Start,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Action controls layout
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        var typedInput by remember { mutableStateOf("") }

                                        // TextField for optional quick type entries
                                        OutlinedTextField(
                                            value = typedInput,
                                            onValueChange = { typedInput = it },
                                            placeholder = { Text("বার্তা লিখুন...", fontSize = 11.sp, color = Color.White.copy(0.4f)) },
                                            maxLines = 1,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(max = 48.dp),
                                            shape = RoundedCornerShape(24.dp),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                            keyboardActions = KeyboardActions(onSend = {
                                                if (typedInput.isNotBlank()) {
                                                    processVoiceCommand(typedInput)
                                                    typedInput = ""
                                                }
                                            }),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = SunsetRed,
                                                unfocusedBorderColor = Color.White.copy(0.2f),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                focusedContainerColor = Color(0xFF090D16),
                                                unfocusedContainerColor = Color(0xFF090D16)
                                            )
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // Big floating dynamic Mic trigger button
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(SunsetRed)
                                                .clickable {
                                                    if (isListeningState) {
                                                        voiceManager?.stopListening()
                                                        _isListening.value = false
                                                    } else {
                                                        _isListening.value = true
                                                        voiceManager?.startListening()
                                                    }
                                                }
                                        ) {
                                            Icon(
                                                imageVector = if (isListeningState) Icons.Default.Stop else Icons.Default.Mic,
                                                contentDescription = "Voice search",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun updateOverlayFocus(isExpanded: Boolean) {
        val view = overlayView ?: return
        val currentParams = params ?: return
        if (isExpanded) {
            currentParams.flags = currentParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            currentParams.flags = currentParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(view, currentParams)
    }

    private fun removeFloatingOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("MayeraForegroundService", "Error removing overlay", e)
            }
            overlayView = null
        }
    }

    // Persistent notifications logic
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "মায়েরা ব্যাকগ্রাউন্ড অ্যাসিস্ট্যান্ট",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "মায়েরার সবসময় চালু থাকা ব্যাকগ্রাউন্ড ভয়েস সার্ভিস"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MayeraForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("মায়েরা অ্যাসিস্ট্যান্ট সচল আছে")
            .setContentText("মায়েরা ভয়েস সার্ভিস ব্যাকগ্রাউন্ডে সবসময় প্রস্তুত।")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Standard system voice icon
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "বন্ধ করুন", stopPendingIntent)
            .setColor(0xFFFF4081.toInt())
            .setOngoing(true)
            .build()
    }
}
