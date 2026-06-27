package com.example.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceManager(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    
    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _isPassiveListening = MutableStateFlow(false)
    val isPassiveListening = _isPassiveListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText = _partialText.asStateFlow()

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onWakeWordDetectedCallback: ((String?) -> Unit)? = null

    fun init(
        onResult: (String) -> Unit, 
        onError: (String) -> Unit,
        onWakeWordDetected: ((String?) -> Unit)? = null
    ) {
        onResultCallback = onResult
        onErrorCallback = onError
        onWakeWordDetectedCallback = onWakeWordDetected
    }

    fun startListening() {
        stopPassiveListeningOnly()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onErrorCallback?.invoke("Speech recognition is not available on this device.")
            return
        }

        _isListening.value = true
        _partialText.value = "Mayera is listening..."
        
        setupSpeechRecognizer(isPassive = false)

        val intent = createSpeechIntent()
        speechRecognizer?.startListening(intent)
    }

    fun startPassiveListening() {
        if (_isListening.value || _isPassiveListening.value) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return
        }

        _isPassiveListening.value = true
        _partialText.value = "Waiting for 'Mayera'..."
        
        setupSpeechRecognizer(isPassive = true)

        val intent = createSpeechIntent()
        speechRecognizer?.startListening(intent)
    }

    private fun createSpeechIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD") // Prefer Bengali for fluent voice commands
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    private fun setupSpeechRecognizer(isPassive: Boolean) {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (isPassive) {
                        _isPassiveListening.value = true
                    } else {
                        _isListening.value = true
                        _partialText.value = "Mayera is listening..."
                    }
                }

                override fun onBeginningOfSpeech() {
                    if (!isPassive) {
                        _partialText.value = "Recording voice..."
                    }
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    if (!isPassive) {
                        _isListening.value = false
                        _partialText.value = "Analyzing speech..."
                    }
                }

                override fun onError(error: Int) {
                    if (isPassive) {
                        _isPassiveListening.value = false
                        // Restart passive listening if we are still supposed to be passive
                        if (isPassiveListening.value) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (_isPassiveListening.value && !_isListening.value) {
                                    _isPassiveListening.value = false
                                    startPassiveListening()
                                }
                            }, 1000)
                        }
                    } else {
                        _isListening.value = false
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network issue"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server connection error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                            else -> "Speech error: $error"
                        }
                        onErrorCallback?.invoke(errorMessage)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val speechText = matches[0]
                        if (isPassive) {
                            _isPassiveListening.value = false
                            val query = extractQuery(speechText)
                            if (query != null) {
                                Log.d("VoiceManager", "Wake word detected with query: $query")
                                onWakeWordDetectedCallback?.invoke(query)
                            } else {
                                // Restart passive listening if "Mayera" was not found
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    if (!_isListening.value) {
                                        startPassiveListening()
                                    }
                                }, 500)
                            }
                        } else {
                            _isListening.value = false
                            onResultCallback?.invoke(speechText)
                        }
                    } else {
                        if (isPassive) {
                            _isPassiveListening.value = false
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (!_isListening.value) {
                                    startPassiveListening()
                                }
                            }, 500)
                        } else {
                            _isListening.value = false
                            onErrorCallback?.invoke("Could not recognize any matches.")
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        if (isPassive) {
                            val speechText = matches[0]
                            val query = extractQuery(speechText)
                            if (query != null) {
                                speechRecognizer?.stopListening()
                                _isPassiveListening.value = false
                                Log.d("VoiceManager", "Wake word detected partially with query: $query")
                                onWakeWordDetectedCallback?.invoke(query)
                            }
                        } else {
                            _partialText.value = matches[0]
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun extractQuery(text: String): String? {
        val lowerText = text.lowercase().trim()
        val wakeWords = listOf("mayera", "maya", "মায়েরা", "মায়েরা", "মায়া", "মায়া")
        for (wakeWord in wakeWords) {
            val index = lowerText.indexOf(wakeWord)
            if (index != -1) {
                // Return query text or empty string if just name
                val afterWakeWord = text.substring(index + wakeWord.length).trim()
                return afterWakeWord.replace(Regex("^[,\\s:\\-\\?]+"), "").trim()
            }
        }
        return null
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    fun stopPassiveListening() {
        _isPassiveListening.value = false
        speechRecognizer?.stopListening()
    }

    private fun stopPassiveListeningOnly() {
        _isPassiveListening.value = false
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
