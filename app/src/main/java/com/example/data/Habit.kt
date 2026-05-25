package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    val emoji: String,
    val frequency: String, // "DAILY" or "WEEKLY"
    val targetDays: String, // Comma-separated list of active days (e.g. "1,2,3,4,5,6,7" where 1 = Mon, 7 = Sun)
    val notifyEnabled: Boolean = false,
    val notifyHour: Int = 8,
    val notifyMinute: Int = 0,
    val isArchived: Boolean = false
)

@Entity(tableName = "habit_completions", primaryKeys = ["habitId", "dateStr"])
data class HabitCompletion(
    val habitId: Long,
    val dateStr: String // "YYYY-MM-DD" style
)
