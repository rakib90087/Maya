package com.example.ui

import android.app.Application
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
                text = "হ্যালো! আমি মায়া, আপনার ভয়েস অ্যাসিস্ট্যান্ট। আমাকে আপনার কাজ পরিচালনা করতে, ফোনের স্ট্যাটাস চেক করতে বা যেকোনো বিষয়ে কথা বলতে বলুন!",
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
        get() = BuildConfig.GEMINI_API_KEY.isEmpty() || 
                BuildConfig.GEMINI_API_KEY == "MY_GEMINI_API_KEY" || 
                BuildConfig.GEMINI_API_KEY == "placeholder"

    init {
        // Fetch location at startup
        refreshLocation()
        
        // Check notification access
        checkNotificationAccess()

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
            try {
                // Get fresh context variables
                val battery = batteryData.value
                val sensors = sensorData.value
                val loc = currentLocation.value
                val tasks = allTasks.value
                val specs = deviceSpecs

                val batteryStr = "${battery.level}% (${battery.status}, temp: ${battery.temperature}°C, health: ${battery.health})"
                val sensorsStr = "Accel(x=${"%.2f".format(sensors.accelX)}, y=${"%.2f".format(sensors.accelY)}, z=${"%.2f".format(sensors.accelZ)}), " +
                                 "Light=${sensors.lightLux} lx, Bearing=${"%.1f".format(sensors.compassBearing)}°"
                val locationStr = if (loc != null) "Lat=${loc.latitude}, Lng=${loc.longitude} (accuracy: ${loc.accuracy}m)" else "Location unavailable"
                val tasksSummary = tasks.filter { !it.completed }.joinToString("; ") { "${it.title} [${it.category}]" }
                    .ifEmpty { "No incomplete tasks" }

                val systemInstructionText = """
                    You are Maya, an advanced AI Voice and Personal Assistant who is incredibly warm, helpful, and natural.
                    You MUST respond in fluent, natural, and colloquial Bengali (চলতি বাংলা) like a close human friend or a polite assistant. Avoid robotic or literal translations. Speak naturally and concisely so your answer is perfect to be read out loud.
                    
                    You have access to the user's phone telemetry and sensors.
                    Current status parameters:
                    - Battery status: $batteryStr
                    - Current device sensors: $sensorsStr
                    - Location: $locationStr
                    - Device specifications: $specs
                    - Incomplete tasks in user's list: $tasksSummary

                    If the user asks you to add a task, do a task, create a todo, or set a reminder, you can automatically add it to their daily tasks list. To do so, you MUST start your response with a single-line command in this exact format:
                    [ADD_TASK: Title | Category | Description]
                    Where:
                    - Title is the short summary of the task (in English or Bengali depending on context, preferably keep title clean).
                    - Category is one of: Work, Personal, Health, Shopping, Other (default to Personal if unsure).
                    - Description is any extra details.
                    For example: [ADD_TASK: Inspect car tires | Health | Inspect all tires and check pressure]
                    Then, on the next lines, speak normally, warm, and concisely in Bengali confirming you've added the task.
                    
                    You have full capability to announce incoming notifications, SMS, WhatsApp messages, and phone calls. If the user asks about notifications or how they can control/answer/reply, explain that:
                    1. They can answer incoming phone calls by speaking commands like "রিসিভ করো", "কল ধরো", or "Answer call".
                    2. They can reply to the last message notification (e.g. WhatsApp, SMS) by speaking "উত্তর দাও [message]" or "Reply with [message]".
                    
                    Always keep your spoken responses concise, highly engaging, helpful, and completely in natural Bengali, as they will be spoken out loud via text-to-speech.
                """.trimIndent()

                // Create conversation history
                val contents = _chatMessages.value.takeLast(10).map { msg ->
                    Content(parts = listOf(Part(text = msg.text)))
                }

                val request = GenerateContentRequest(
                    contents = contents,
                    systemInstruction = Content(parts = listOf(Part(text = systemInstructionText))),
                    generationConfig = GenerationConfig(temperature = 0.7f)
                )

                val apiKey = BuildConfig.GEMINI_API_KEY
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.generateContent(
                        model = "gemini-3.5-flash",
                        apiKey = apiKey,
                        request = request
                    )
                }

                val aiRawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: "I'm sorry, I couldn't generate a response."

                // 3. Process AI raw response
                var cleanAiText = aiRawText.trim()
                if (cleanAiText.startsWith("[ADD_TASK:")) {
                    val endIndex = cleanAiText.indexOf("]")
                    if (endIndex != -1) {
                        val command = cleanAiText.substring(10, endIndex)
                        cleanAiText = cleanAiText.substring(endIndex + 1).trim()
                        
                        // Parse command parameters separated by "|"
                        val parts = command.split("|").map { it.trim() }
                        val title = parts.getOrNull(0) ?: "New Task"
                        val category = parts.getOrNull(1) ?: "Personal"
                        val description = parts.getOrNull(2) ?: ""

                        // Generate detailed suggested sub-steps using AI in background or list them nicely
                        // For maximum responsiveness, we can generate a friendly default sub-steps structure
                        val steps = "• Step 1: Initial preparation\n• Step 2: Core action items\n• Step 3: Complete & verify"

                        // Insert to database
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

                // 4. Add AI message
                val aiMsg = ChatMessage(text = cleanAiText, isUser = false)
                _chatMessages.value = _chatMessages.value + aiMsg

                // 5. Trigger TTS speech
                speakCallback?.invoke(cleanAiText)

            } catch (e: Exception) {
                Log.e("AssistantViewModel", "Error communicating with Gemini API", e)
                val errMsg = ChatMessage(
                    text = "Sorry, I had trouble connecting. Please check your internet connection or verify your Gemini API key: ${e.localizedMessage}",
                    isUser = false
                )
                _chatMessages.value = _chatMessages.value + errMsg
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
                val apiKey = BuildConfig.GEMINI_API_KEY
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
