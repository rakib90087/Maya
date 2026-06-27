package com.example.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.os.Build
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantViewModel(
    application: Application,
    private val repository: TaskRepository,
    val phoneManager: PhoneFeatureManager
) : AndroidViewModel(application) {

    // Chat states
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                text = "হ্যালো! আমি মায়েরা, আপনার অ্যাডভান্সড ভয়েস অ্যাসিস্ট্যান্ট। আমি আপনার ফোন কল, মেসেজ, হোয়াটসঅ্যাপ ও দৈনিক কাজ সরাসরি অটোমেট করতে পারি!",
                isUser = false
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // Real-time values
    val batteryData = phoneManager.batteryData
    val sensorData = phoneManager.sensorData
    val deviceSpecs = phoneManager.getDeviceSpecs()
    
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    // Task list from database
    val allTasks: StateFlow<List<Task>> = repository.allTasks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // TTS speaker callback (to be set by MainActivity)
    var speakCallback: ((String) -> Unit)? = null
    var stopSpeakingCallback: (() -> Unit)? = null

    // Speech-to-Text callbacks (to be set by MainActivity)
    var startListeningCallback: (() -> Unit)? = null
    var stopListeningCallback: (() -> Unit)? = null
    var startPassiveListeningCallback: (() -> Unit)? = null
    var stopPassiveListeningCallback: (() -> Unit)? = null

    private val _isPassiveListening = MutableStateFlow(false)
    val isPassiveListening: StateFlow<Boolean> = _isPassiveListening.asStateFlow()

    private val _wakeWordModeEnabled = MutableStateFlow(true)
    val wakeWordModeEnabled: StateFlow<Boolean> = _wakeWordModeEnabled.asStateFlow()

    private val _isNotificationAccessGranted = MutableStateFlow(false)
    val isNotificationAccessGranted: StateFlow<Boolean> = _isNotificationAccessGranted.asStateFlow()

    private val _isOverlayPermissionGranted = MutableStateFlow(false)
    val isOverlayPermissionGranted: StateFlow<Boolean> = _isOverlayPermissionGranted.asStateFlow()

    private val _isBackgroundServiceRunning = MutableStateFlow(false)
    val isBackgroundServiceRunning: StateFlow<Boolean> = _isBackgroundServiceRunning.asStateFlow()

    fun checkOverlayPermission(context: Context) {
        _isOverlayPermissionGranted.value = Settings.canDrawOverlays(context)
        _isBackgroundServiceRunning.value = MayeraForegroundService.isServiceRunning
    }

    fun checkNotificationAccess() {
        val context = getApplication<Application>()
        _isNotificationAccessGranted.value = NotificationEvents.isNotificationAccessGranted(context)
    }

    private val _partialSpeechText = MutableStateFlow("")
    val partialSpeechText: StateFlow<String> = _partialSpeechText.asStateFlow()

    fun startVoiceRecognition() {
        stopSpeaking()
        _partialSpeechText.value = "Initializing microphone..."
        _isListening.value = true
        startListeningCallback?.invoke()
    }

    fun stopVoiceRecognition() {
        _isListening.value = false
        stopListeningCallback?.invoke()
    }

    fun startPassiveListening() {
        if (_wakeWordModeEnabled.value) {
            _isPassiveListening.value = true
            startPassiveListeningCallback?.invoke()
        }
    }

    fun stopPassiveListening() {
        _isPassiveListening.value = false
        stopPassiveListeningCallback?.invoke()
    }

    fun setPassiveListening(passive: Boolean) {
        _isPassiveListening.value = passive
    }

    fun setWakeWordModeEnabled(enabled: Boolean) {
        _wakeWordModeEnabled.value = enabled
        if (enabled) {
            startPassiveListening()
        } else {
            stopPassiveListening()
        }
    }

    fun setPartialSpeechText(text: String) {
        _partialSpeechText.value = text
    }

    // Is API key missing or default placeholder?
    val isApiKeyMissing: Boolean
        get() = !ApiKeyManager.isApiKeyConfigured(getApplication())

    init {
        // Fetch location at startup
        refreshLocation()
        
        // Check notification access
        checkNotificationAccess()
        
        // Check overlay permission
        checkOverlayPermission(getApplication())

        // Listen to notification events and announce them in Bengali
        viewModelScope.launch {
            NotificationEvents.events.collect { event ->
                handleNotificationEvent(event)
            }
        }
    }

    private fun handleNotificationEvent(event: MayaNotificationEvent) {
        val announcement = when (event.type) {
            NotificationType.CALL -> {
                if (event.appName == "Phone Call") {
                    "${event.sender} আপনাকে কল করছেন।"
                } else {
                    "${event.sender} আপনাকে ${event.appName} এ কল করছেন।"
                }
            }
            NotificationType.MESSAGE -> {
                "${event.sender} আপনাকে ${event.appName} এ লিখেছেন: \"${event.content}\"।"
            }
            else -> {
                "${event.appName} থেকে নতুন নোটিফিকেশন এসেছে: ${event.sender}। ${event.content}"
            }
        }

        // Add to chat messages so user sees it visually
        val typeLabel = when (event.type) {
            NotificationType.CALL -> "কল"
            NotificationType.MESSAGE -> "বার্তা"
            else -> "নোটিফিকেশন"
        }
        val notificationMsg = ChatMessage(
            text = "🔔 [$typeLabel] ${event.sender} (${event.appName}): ${event.content}",
            isUser = false
        )
        _chatMessages.value = _chatMessages.value + notificationMsg

        // Play announcement out loud immediately
        speakCallback?.invoke(announcement)
    }

    fun refreshLocation() {
        phoneManager.getLastLocation { location ->
            _currentLocation.value = location
        }
    }

    fun setListening(listening: Boolean) {
        _isListening.value = listening
    }

    fun setSpeaking(speaking: Boolean) {
        _isSpeaking.value = speaking
    }

    fun stopSpeaking() {
        _isSpeaking.value = false
        stopSpeakingCallback?.invoke()
    }

    private fun unescapeJsonString(str: String): String {
        return str.replace("\\n", "\n")
                  .replace("\\t", "\t")
                  .replace("\\\"", "\"")
                  .replace("\\\\", "\\")
    }

    // Process a user message
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // 1. Add user message
        val userMsg = ChatMessage(text = text, isUser = true)
        _chatMessages.value = _chatMessages.value + userMsg

        // Intercept Voice Actions (Answer Call / Reply to Message)
        val lowerText = text.lowercase().trim()
        val context = getApplication<Application>()

        // Check for answering call
        val isAnswerCommand = lowerText.contains("answer") || 
                              lowerText.contains("pick up") ||
                              lowerText.contains("রিসিভ") || 
                              lowerText.contains("কল ধরো") || 
                              lowerText.contains("ফোন ধরো") ||
                              lowerText.contains("রিসিভ কর") ||
                              lowerText.contains("গ্রহণ কর")

        if (isAnswerCommand) {
            val success = NotificationEvents.answerLastCall(context)
            val responseText = if (success) {
                "আমি কলটি রিসিভ করছি।"
            } else {
                "দুঃখিত, এই মুহূর্তে রিসিভ করার মতো কোনো রিং বা সক্রিয় কল পাওয়া যায়নি।"
            }
            _chatMessages.value = _chatMessages.value + ChatMessage(text = responseText, isUser = false)
            speakCallback?.invoke(responseText)
            return
        }

        // Check for replying to message
        var replyMessage: String? = null
        val replyKeywords = listOf("reply with", "reply", "উত্তর দাও", "বলো যে", "উত্তর লেখো", "উত্তর পাঠাও", "পাঠাও")
        for (keyword in replyKeywords) {
            val idx = lowerText.indexOf(keyword)
            if (idx != -1) {
                replyMessage = text.substring(idx + keyword.length).trim()
                // Strip colon or leading spaces/symbols
                replyMessage = replyMessage.replace(Regex("^[:\\-\\s,]+"), "").trim()
                if (replyMessage.isNotEmpty()) {
                    break
                }
            }
        }

        if (replyMessage != null && replyMessage.isNotEmpty()) {
            val success = NotificationEvents.replyToLastNotification(context, replyMessage)
            val responseText = if (success) {
                "আমি উত্তর পাঠিয়ে দিয়েছি: \"$replyMessage\""
            } else {
                "দুঃখিত, উত্তর পাঠানোর জন্য কোনো সক্রিয় বার্তা নোটিফিকেশন পাওয়া যায়নি।"
            }
            _chatMessages.value = _chatMessages.value + ChatMessage(text = responseText, isUser = false)
            speakCallback?.invoke(responseText)
            return
        }

        // 2. Query Gemini
        viewModelScope.launch {
            _isGenerating.value = true
            
            // Add a placeholder message for Mayera that will be filled in real-time
            val aiMessageIndex = _chatMessages.value.size
            _chatMessages.value = _chatMessages.value + ChatMessage(text = "ভাবছি...", isUser = false)
            
            try {
                // Get fresh context variables
                val battery = batteryData.value
                val sensors = sensorData.value
                val loc = currentLocation.value
                val tasks = allTasks.value
                val specs = deviceSpecs
                val contacts = withContext(Dispatchers.IO) {
                    phoneManager.getContacts().take(15)
                }
                val contactsList = contacts.joinToString(", ") { "${it.name} (${it.number})" }
                    .ifEmpty { "No contacts available or permission denied" }

                val batteryStr = "${battery.level}% (${battery.status}, temp: ${battery.temperature}°C, health: ${battery.health})"
                val sensorsStr = "Accel(x=${"%.2f".format(sensors.accelX)}, y=${"%.2f".format(sensors.accelY)}, z=${"%.2f".format(sensors.accelZ)}), " +
                                 "Light=${sensors.lightLux} lx, Bearing=${"%.1f".format(sensors.compassBearing)}°"
                val locationStr = if (loc != null) "Lat=${loc.latitude}, Lng=${loc.longitude} (accuracy: ${loc.accuracy}m)" else "Location unavailable"
                val tasksSummary = tasks.filter { !it.completed }.joinToString("; ") { "${it.title} [${it.category}]" }
                    .ifEmpty { "No incomplete tasks" }

                val systemInstructionText = """
                    You are Mayera (মায়েরা), a highly advanced, ultra-responsive, respectful, and warm female AI Voice and Personal Assistant.
                    You MUST respond in fluent, natural, and colloquial Bengali (চলতি বাংলা) like a highly respectful, loyal, and supportive female AI companion.
                    
                    CRITICAL RULES FOR IDENTITY AND ADDRESSING:
                    1. Name Change: Your name is "Mayera" (মায়েরা). You must always introduce yourself only as Mayera.
                    2. User Title: You MUST strictly address the user as "Boss" (বস) in EVERY response and in EVERY sentence you speak or write. Never call the user by name or any generic/informal title.
                    3. Tone & Personality: Maintain an extremely respectful, loyal, devoted, and supportive female AI persona. Your Bengali must be smooth, polite, natural, and affectionate yet highly professional.
                    
                    Keep your answers extremely concise, fast, and action-oriented. Do not use unnecessary filler words.
                    
                    You have full system permissions and can automate tasks on the user's phone directly using command triggers.
                    If the user wants you to perform an action, you MUST execute it immediately by prepending the appropriate command at the very start of your response.
                    
                    Supported action commands (always start your response with the command if requested):
                    1. Direct Phone Call:
                       [CALL: PhoneNumber] - Call a phone number. Look up names in the contacts list below.
                    2. Send Direct SMS:
                       [SMS: PhoneNumber | MessageText] - Send SMS text to a number.
                    3. Send WhatsApp Message:
                       [WHATSAPP: PhoneNumber | MessageText] - Send a message on WhatsApp.
                    4. Add Task / To-Do item:
                       [ADD_TASK: Title | Category | Description] - Add a task. Category must be: Work, Personal, Health, Shopping, Other.
                    5. Launch any App:
                       [LAUNCH_APP: AppName] - Launch an installed app by its name (e.g., YouTube, Facebook, WhatsApp, Maps).
                    6. Go back home/Close app:
                       [GO_HOME] - Go back to the home screen.
                    7. Toggle Flashlight:
                       [FLASHLIGHT: ON] or [FLASHLIGHT: OFF] - Turn the device flashlight on or off.
                    8. Screen Recorder:
                       [SCREEN_RECORDER] - Trigger the built-in screen recorder settings.
                    
                    Current Phone Status:
                    - Battery status: $batteryStr
                    - Sensor status: $sensorsStr
                    - Current Location: $locationStr
                    - Device specifications: $specs
                    - Top Contacts: $contactsList
                    
                    Example response for "কল করো রাকিবকে":
                    [CALL: 01712345678] আমি রাকিবকে কল করছি, বস।
                    
                    Keep spoken feedback warm, brief, and in fluent Bengali. Do not explain the commands or write them in the speech text. Just output the command tag, and then write your brief Bengali confirmation addressing the user as Boss.
                """.trimIndent()

                // Create conversation history
                val contents = _chatMessages.value.dropLast(1).takeLast(10).map { msg ->
                    Content(parts = listOf(Part(text = msg.text)))
                }

                val request = GenerateContentRequest(
                    contents = contents,
                    systemInstruction = Content(parts = listOf(Part(text = systemInstructionText))),
                    generationConfig = GenerationConfig(temperature = 0.5f)
                )

                val apiKey = ApiKeyManager.getApiKey(getApplication())
                var fullStreamedText = ""
                
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
                                    fullStreamedText += unescaped
                                    
                                    // Update the UI message in real-time
                                    withContext(Dispatchers.Main) {
                                        val currentList = _chatMessages.value.toMutableList()
                                        if (aiMessageIndex < currentList.size) {
                                            var displayStream = fullStreamedText
                                            if (displayStream.startsWith("[")) {
                                                val closeIndex = displayStream.indexOf("]")
                                                if (closeIndex != -1) {
                                                    displayStream = displayStream.substring(closeIndex + 1).trim()
                                                } else {
                                                    displayStream = "কাজটি সম্পন্ন করছি..."
                                                }
                                            }
                                            currentList[aiMessageIndex] = ChatMessage(text = displayStream, isUser = false)
                                            _chatMessages.value = currentList
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Parse and execute final commands
                var cleanAiText = fullStreamedText.trim()
                if (cleanAiText.startsWith("[")) {
                    val endIndex = cleanAiText.indexOf("]")
                    if (endIndex != -1) {
                        val commandTag = cleanAiText.substring(1, endIndex)
                        cleanAiText = cleanAiText.substring(endIndex + 1).trim()
                        
                        try {
                            val colonIndex = commandTag.indexOf(":")
                            if (colonIndex != -1) {
                                val cmdType = commandTag.substring(0, colonIndex).trim().uppercase()
                                val cmdArgs = commandTag.substring(colonIndex + 1).trim()
                                
                                when (cmdType) {
                                    "CALL" -> {
                                        phoneManager.makeCall(cmdArgs)
                                    }
                                    "SMS" -> {
                                        val parts = cmdArgs.split("|").map { it.trim() }
                                        val number = parts.getOrNull(0) ?: ""
                                        val msg = parts.getOrNull(1) ?: ""
                                        if (number.isNotEmpty()) {
                                            phoneManager.sendSMS(number, msg)
                                        }
                                    }
                                    "WHATSAPP" -> {
                                        val parts = cmdArgs.split("|").map { it.trim() }
                                        val number = parts.getOrNull(0) ?: ""
                                        val msg = parts.getOrNull(1) ?: ""
                                        if (number.isNotEmpty()) {
                                            phoneManager.sendWhatsAppMessage(number, msg)
                                        }
                                    }
                                    "LAUNCH_APP" -> {
                                        phoneManager.launchApp(cmdArgs)
                                    }
                                    "FLASHLIGHT" -> {
                                        val turnOn = cmdArgs.equals("ON", ignoreCase = true)
                                        phoneManager.toggleFlashlight(turnOn)
                                    }
                                    "ADD_TASK" -> {
                                        val parts = cmdArgs.split("|").map { it.trim() }
                                        val title = parts.getOrNull(0) ?: "New Task"
                                        val category = parts.getOrNull(1) ?: "Personal"
                                        val description = parts.getOrNull(2) ?: ""
                                        val steps = "• Step 1: Initial preparation\n• Step 2: Core action items\n• Step 3: Complete & verify"
                                        repository.insert(
                                            Task(
                                                title = title,
                                                category = category,
                                                description = description,
                                                suggestedSteps = steps
                                            )
                                        )
                                    }
                                }
                            } else {
                                when (commandTag.uppercase()) {
                                    "GO_HOME" -> phoneManager.goHome()
                                    "SCREEN_RECORDER" -> phoneManager.openScreenRecorder()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AssistantViewModel", "Failed to parse command: $commandTag", e)
                        }
                    }
                }

                if (cleanAiText.isEmpty()) {
                    cleanAiText = "কাজটি সম্পন্ন করা হয়েছে।"
                }

                // Final UI Update with parsed clean text
                val finalCleanText = cleanAiText
                withContext(Dispatchers.Main) {
                    val currentList = _chatMessages.value.toMutableList()
                    if (aiMessageIndex < currentList.size) {
                        currentList[aiMessageIndex] = ChatMessage(text = finalCleanText, isUser = false)
                        _chatMessages.value = currentList
                    }
                }

                // 5. Trigger TTS speech of the clean confirmation
                speakCallback?.invoke(finalCleanText)

            } catch (e: Exception) {
                Log.e("AssistantViewModel", "Error communicating with Gemini API", e)
                val errMsgText = "দুঃখিত, সংযোগে সমস্যা হয়েছে। অনুগ্রহ করে আপনার ইন্টারনেট সংযোগ চেক করুন এবং আপনার Gemini API কী সঠিক কিনা তা নিশ্চিত করুন।"
                withContext(Dispatchers.Main) {
                    val currentList = _chatMessages.value.toMutableList()
                    if (aiMessageIndex < currentList.size) {
                        currentList[aiMessageIndex] = ChatMessage(text = errMsgText, isUser = false)
                        _chatMessages.value = currentList
                    }
                }
                speakCallback?.invoke(errMsgText)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    // Task management methods
    fun addTask(title: String, category: String, description: String, suggestedSteps: String = "") {
        viewModelScope.launch {
            repository.insert(
                Task(
                    title = title,
                    category = category,
                    description = description,
                    suggestedSteps = suggestedSteps
                )
            )
        }
    }

    fun toggleTaskStatus(id: Int, completed: Boolean) {
        viewModelScope.launch {
            repository.updateStatus(id, completed)
        }
    }

    fun deleteTask(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    fun deleteCompletedTasks() {
        viewModelScope.launch {
            repository.deleteCompleted()
        }
    }

    fun askAiToOrganizeTask(task: Task) {
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                val prompt = "Provide exactly 4 detailed, actionable chronological steps for the task: '${task.title}' (Category: ${task.category}, Description: ${task.description}). Return them as a neat bulleted list."
                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                    systemInstruction = Content(parts = listOf(Part(text = "You are a professional task organizer AI. Return only a clean, bulleted list of 4 action steps.")))
                )
                val apiKey = ApiKeyManager.getApiKey(getApplication())
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.generateContent(
                        model = "gemini-3.5-flash",
                        apiKey = apiKey,
                        request = request
                    )
                }
                val resultText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!resultText.isNullOrBlank()) {
                    repository.update(task.copy(suggestedSteps = resultText.trim()))
                }
            } catch (e: Exception) {
                Log.e("AssistantViewModel", "Error organizing task with AI", e)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun clearChat() {
        _chatMessages.value = listOf(
            ChatMessage(
                text = "চ্যাট হিস্ট্রি মুছে ফেলা হয়েছে। আমি আপনাকে সাহায্য করতে প্রস্তুত!",
                isUser = false
            )
        )
        stopSpeaking()
    }

    override fun onCleared() {
        super.onCleared()
        phoneManager.cleanup()
    }
}
