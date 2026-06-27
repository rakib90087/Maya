package com.example.data

import android.content.Context
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ApiKeyManager {
    private const val PREFS_NAME = "mayera_prefs"
    private const val KEY_GEMINI_API = "gemini_api_key"

    fun getApiKey(context: Context): String {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = sharedPrefs.getString(KEY_GEMINI_API, null)
        if (!savedKey.isNullOrBlank()) {
            return savedKey
        }
        // Fallback to BuildConfig if it's a valid key
        val buildConfigKey = BuildConfig.GEMINI_API_KEY
        if (isValidBuildConfigKey(buildConfigKey)) {
            return buildConfigKey
        }
        return ""
    }

    fun saveApiKey(context: Context, key: String) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_GEMINI_API, key).apply()
    }

    fun isApiKeyConfigured(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    suspend fun validateApiKey(key: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Perform a very cheap test request to Gemini API generateContent
                val testRequest = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = "Hello"))))
                )
                // Use RetrofitClient to send the request
                val response = RetrofitClient.service.generateContent(
                    model = "gemini-1.5-flash", // Using the fast, default model
                    apiKey = key,
                    request = testRequest
                )
                val textResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                !textResponse.isNullOrEmpty()
            } catch (e: Exception) {
                android.util.Log.e("ApiKeyManager", "API Key verification failed", e)
                false
            }
        }
    }

    private fun isValidBuildConfigKey(key: String): Boolean {
        return key.isNotBlank() &&
                key != "MY_GEMINI_API_KEY" &&
                key != "placeholder" &&
                key != "null"
    }
}

