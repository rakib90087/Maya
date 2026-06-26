package com.example.data

import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {
    val allTasks: Flow<List<Task>> = taskDao.getAllTasks()

    fun getTasksByCategory(category: String): Flow<List<Task>> = taskDao.getTasksByCategory(category)

    suspend fun insert(task: Task): Long = taskDao.insertTask(task)

    suspend fun update(task: Task) = taskDao.updateTask(task)

    suspend fun deleteById(id: Int) = taskDao.deleteTaskById(id)

    suspend fun updateStatus(id: Int, completed: Boolean) = taskDao.updateTaskStatus(id, completed)

    suspend fun deleteCompleted() = taskDao.deleteCompletedTasks()
}
