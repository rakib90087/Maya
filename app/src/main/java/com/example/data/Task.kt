package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String = "",
    val category: String = "Personal",
    val completed: Boolean = false,
    val dueDate: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val suggestedSteps: String = "" // Newline-separated list of suggested sub-tasks from AI
)
