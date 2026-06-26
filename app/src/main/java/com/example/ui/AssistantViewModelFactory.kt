package com.example.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.PhoneFeatureManager
import com.example.data.TaskRepository

class AssistantViewModelFactory(
    private val application: Application,
    private val repository: TaskRepository,
    private val phoneManager: PhoneFeatureManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
            return AssistantViewModel(application, repository, phoneManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
