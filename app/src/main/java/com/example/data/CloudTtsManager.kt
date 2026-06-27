package com.example.data

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class CloudTtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var localTts: TextToSpeech? = null
    private var isLocalTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentPlayJob: Job? = null

    private var onStartCallback: (() -> Unit)? = null
    private var onCompleteCallback: (() -> Unit)? = null

    init {
        // Initialize local TTS fallback
        localTts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale("bn", "BD")
            var result = localTts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = localTts?.setLanguage(Locale("bn"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    localTts?.setLanguage(Locale.getDefault())
                }
            }
            localTts?.setPitch(1.08f)
            localTts?.setSpeechRate(0.92f)
            isLocalTtsReady = true

            localTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainScope.launch { onStartCallback?.invoke() }
                }

                override fun onDone(utteranceId: String?) {
                    mainScope.launch { onCompleteCallback?.invoke() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainScope.launch { onCompleteCallback?.invoke() }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    mainScope.launch { onCompleteCallback?.invoke() }
                }
            })
        }
    }

    fun speak(
        text: String,
        onStart: () -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        this.onStartCallback = onStart
        this.onCompleteCallback = onComplete

        // Stop any current playback/speech
        stopSpeaking()

        currentPlayJob = mainScope.launch {
            val apiKey = ApiKeyManager.getApiKey(context)
            if (apiKey.isBlank() || apiKey == "null") {
                Log.w("CloudTtsManager", "API Key is missing or invalid. Falling back to Local TTS.")
                speakLocal(text)
                return@launch
            }

            onStart()
            
            // Try Cloud TTS
            val audioBytes = tryCloudTts(text, apiKey)
            if (audioBytes != null) {
                playAudioBytes(audioBytes, onComplete)
            } else {
                Log.e("CloudTtsManager", "Cloud TTS synthesis failed. Falling back to Local TTS.")
                speakLocal(text)
            }
        }
    }

    private suspend fun tryCloudTts(text: String, apiKey: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            // We try Neural2 first, then fallback to Wavenet
            val voicesToTry = listOf("bn-BD-Neural2-A", "bn-BD-Wavenet-A")
            for (voiceName in voicesToTry) {
                try {
                    val url = "https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey"
                    val jsonRequest = JSONObject().apply {
                        put("input", JSONObject().put("text", text))
                        put("voice", JSONObject().apply {
                            put("languageCode", "bn-BD")
                            put("name", voiceName)
                            put("ssmlGender", "FEMALE")
                        })
                        put("audioConfig", JSONObject().apply {
                            put("audioEncoding", "MP3")
                        })
                    }

                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val requestBody = jsonRequest.toString().toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            val jsonResponse = JSONObject(responseBody)
                            val base64Audio = jsonResponse.optString("audioContent", "")
                            if (base64Audio.isNotEmpty()) {
                                Log.d("CloudTtsManager", "Cloud TTS synthesis succeeded with voice: $voiceName")
                                return@withContext Base64.decode(base64Audio, Base64.DEFAULT)
                            }
                        } else {
                            Log.e("CloudTtsManager", "Cloud TTS API returned error code ${response.code} for voice $voiceName: ${response.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CloudTtsManager", "Exception during Cloud TTS call with voice $voiceName", e)
                }
            }
            null
        }
    }

    private fun playAudioBytes(audioBytes: ByteArray, onComplete: () -> Unit) {
        try {
            val tempFile = File.createTempFile("mayera_tts_", ".mp3", context.cacheDir)
            tempFile.deleteOnExit()
            FileOutputStream(tempFile).use { fos ->
                fos.write(audioBytes)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    onComplete()
                    try {
                        tempFile.delete()
                    } catch (e: Exception) {
                        Log.e("CloudTtsManager", "Error deleting temp file", e)
                    }
                }
                start()
            }
        } catch (e: Exception) {
            Log.e("CloudTtsManager", "Error playing Cloud TTS audio", e)
            onComplete()
        }
    }

    private fun speakLocal(text: String) {
        if (localTts != null && isLocalTtsReady) {
            localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MayeraSpeechFallback")
        } else {
            Log.e("CloudTtsManager", "Local TTS not initialized yet or failed.")
            onCompleteCallback?.invoke()
        }
    }

    fun stopSpeaking() {
        currentPlayJob?.cancel()
        currentPlayJob = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e("CloudTtsManager", "Error stopping MediaPlayer", e)
        }
        mediaPlayer = null

        try {
            localTts?.stop()
        } catch (e: Exception) {
            Log.e("CloudTtsManager", "Error stopping local TTS", e)
        }
    }

    fun destroy() {
        mainScope.cancel()
        stopSpeaking()
        localTts?.shutdown()
        localTts = null
        isLocalTtsReady = false
    }
}
