package com.example.data

import kotlinx.coroutines.flow.Flow

class HabitRepository(private val habitDao: HabitDao) {

    val allHabits: Flow<List<Habit>> = habitDao.getAllHabits()
    val allCompletions: Flow<List<HabitCompletion>> = habitDao.getAllCompletionsFlow()

    fun getCompletionsForHabitFlow(habitId: Long): Flow<List<HabitCompletion>> {
        return habitDao.getCompletionsForHabitFlow(habitId)
    }

    suspend fun insertHabit(habit: Habit): Long {
        return habitDao.insertHabit(habit)
    }

    suspend fun updateHabit(habit: Habit) {
        habitDao.updateHabit(habit)
    }

    suspend fun deleteHabitById(id: Long) {
        habitDao.deleteHabitById(id)
        habitDao.deleteCompletionsByHabitId(id)
    }

    suspend fun completeHabit(habitId: Long, dateStr: String) {
        habitDao.insertCompletion(HabitCompletion(habitId, dateStr))
    }

    suspend fun uncompleteHabit(habitId: Long, dateStr: String) {
        habitDao.deleteCompletion(habitId, dateStr)
    }
}
