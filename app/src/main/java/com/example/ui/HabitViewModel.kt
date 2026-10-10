package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.receiver.HabitReminderReceiver
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter

class HabitViewModel(
    application: Application,
    private val repository: HabitRepository
) : AndroidViewModel(application) {

    // Streams from database
    val allHabits: StateFlow<List<Habit>> = repository.allHabits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCompletions: StateFlow<List<HabitCompletion>> = repository.allCompletions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI state that resolves details (streaks, week progress, checked state) for non-archived habits
    val activeHabitWithStats: StateFlow<List<HabitWithStats>> = combine(allHabits, allCompletions) { habits, completions ->
        habits.filter { !it.isArchived }.map { habit ->
            calculateStatsForHabit(habit, completions)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI state for archived habits
    val archivedHabitsWithStats: StateFlow<List<HabitWithStats>> = combine(allHabits, allCompletions) { habits, completions ->
        habits.filter { it.isArchived }.map { habit ->
            calculateStatsForHabit(habit, completions)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Overall stats
    val overallStats: StateFlow<OverallStats> = activeHabitWithStats.map { activeHabits ->
        if (activeHabits.isEmpty()) {
            OverallStats(0, 0, 0, 0f, 0)
        } else {
            val total = activeHabits.size
            val bestStreak = activeHabits.maxOfOrNull { it.bestStreak } ?: 0
            val currentStreak = activeHabits.maxOfOrNull { it.currentStreak } ?: 0
            val avgWeekProgress = activeHabits.map { it.weekProgress }.average().toFloat()
            val achievedCount = activeHabits.count { it.isGoalAchieved }
            OverallStats(total, bestStreak, currentStreak, avgWeekProgress, achievedCount)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverallStats(0, 0, 0, 0f, 0))

    // DB Operations
    fun addHabit(
        name: String,
        description: String?,
        emoji: String,
        frequency: String,
        targetDays: List<Int>,
        notifyEnabled: Boolean,
        notifyHour: Int,
        notifyMinute: Int,
        startDate: String? = null,
        endDate: String? = null
    ) {
        viewModelScope.launch {
            val targetDaysStr = targetDays.sorted().joinToString(",")
            val habit = Habit(
                name = name,
                description = description,
                emoji = emoji,
                frequency = frequency,
                targetDays = targetDaysStr,
                notifyEnabled = notifyEnabled,
                notifyHour = notifyHour,
                notifyMinute = notifyMinute,
                startDate = startDate ?: LocalDate.now().toString(),
                endDate = endDate
            )
            val newId = repository.insertHabit(habit)
            val insertedHabit = habit.copy(id = newId)
            HabitReminderReceiver.scheduleHabitReminder(getApplication(), insertedHabit)
        }
    }

    fun updateHabit(
        id: Long,
        name: String,
        description: String?,
        emoji: String,
        frequency: String,
        targetDays: List<Int>,
        notifyEnabled: Boolean,
        notifyHour: Int,
        notifyMinute: Int,
        isArchived: Boolean,
        startDate: String? = null,
        endDate: String? = null
    ) {
        viewModelScope.launch {
            val targetDaysStr = targetDays.sorted().joinToString(",")
            val habit = Habit(
                id = id,
                name = name,
                description = description,
                emoji = emoji,
                frequency = frequency,
                targetDays = targetDaysStr,
                notifyEnabled = notifyEnabled,
                notifyHour = notifyHour,
                notifyMinute = notifyMinute,
                isArchived = isArchived,
                startDate = startDate,
                endDate = endDate
            )
            repository.updateHabit(habit)
            HabitReminderReceiver.scheduleHabitReminder(getApplication(), habit)
        }
    }

    fun deleteHabit(id: Long) {
        viewModelScope.launch {
            repository.deleteHabitById(id)
            HabitReminderReceiver.cancelHabitReminder(getApplication(), id)
        }
    }

    fun archiveHabit(habit: Habit, archive: Boolean) {
        viewModelScope.launch {
            val updated = habit.copy(isArchived = archive)
            repository.updateHabit(updated)
            HabitReminderReceiver.scheduleHabitReminder(getApplication(), updated)
        }
    }

    fun updateHabitReminderTime(habit: Habit, hour: Int, minute: Int) {
        viewModelScope.launch {
            val updated = habit.copy(
                notifyEnabled = true,
                notifyHour = hour,
                notifyMinute = minute
            )
            repository.updateHabit(updated)
            HabitReminderReceiver.scheduleHabitReminder(getApplication(), updated)
        }
    }

    // Toggle completion for today
    fun toggleCompletionToday(habit: Habit, isCompletedNow: Boolean) {
        viewModelScope.launch {
            val todayStr = LocalDate.now().toString()
            if (isCompletedNow) {
                repository.completeHabit(habit.id, todayStr)
            } else {
                repository.uncompleteHabit(habit.id, todayStr)
            }
        }
    }

    // Toggle completion for specific date
    fun toggleCompletionForDate(habitId: Long, date: LocalDate, complete: Boolean) {
        viewModelScope.launch {
            val dateStr = date.toString()
            if (complete) {
                repository.completeHabit(habitId, dateStr)
            } else {
                repository.uncompleteHabit(habitId, dateStr)
            }
        }
    }

    // Helper to calculate statistics for a given habit
    private fun calculateStatsForHabit(habit: Habit, completions: List<HabitCompletion>): HabitWithStats {
        val habitCompletions = completions.filter { it.habitId == habit.id }.map { it.dateStr }.toSet()
        val targetDays = parseTargetDays(habit.frequency, habit.targetDays)

        val today = LocalDate.now()
        val todayStr = today.toString()
        val isCompletedToday = habitCompletions.contains(todayStr)

        val habitStartDate = habit.startDate?.let {
            try { LocalDate.parse(it) } catch (e: Exception) { null }
        }

        // 1. Current streak calculation (back from today/yesterday)
        var currentStreak = 0
        var tempDate = today
        for (i in 0..365) {
            if (habitStartDate != null && tempDate.isBefore(habitStartDate)) {
                break
            }
            val dayOfWeekInt = getDayOfWeekInt(tempDate.dayOfWeek)
            val isTarget = targetDays.contains(dayOfWeekInt)
            val isCompleted = habitCompletions.contains(tempDate.toString())

            if (isCompleted) {
                currentStreak++
            } else {
                if (isTarget) {
                    if (tempDate == today) {
                        // If today is not completed yet, the streak is still safe, check yesterday
                    } else {
                        // Missed a target day, streak breaks
                        break
                    }
                }
            }
            tempDate = tempDate.minusDays(1)
        }

        // 2. Best streak calculation (chronological scan from earliest completion to today)
        val parsedDates = habitCompletions.mapNotNull {
            try { LocalDate.parse(it) } catch (e: Exception) { null }
        }.sorted()

        var bestStreak = 0
        if (parsedDates.isNotEmpty()) {
            val earliestDate = parsedDates.first()
            var dateCursor = earliestDate
            var runningStreak = 0

            while (!dateCursor.isAfter(today)) {
                val cursorDayOfWeek = getDayOfWeekInt(dateCursor.dayOfWeek)
                val cursorIsTarget = targetDays.contains(cursorDayOfWeek)
                val cursorCompleted = habitCompletions.contains(dateCursor.toString())

                if (cursorCompleted) {
                    runningStreak++
                    if (runningStreak > bestStreak) {
                        bestStreak = runningStreak
                    }
                } else {
                    if (cursorIsTarget) {
                        if (dateCursor != today) {
                            runningStreak = 0
                        }
                    }
                }
                dateCursor = dateCursor.plusDays(1)
            }
        }
        if (currentStreak > bestStreak) {
            bestStreak = currentStreak
        }

        // 3. Weekly progress percentage (past 7 days including today)
        var totalScheduledTargetDays = 0
        var completedScheduledDays = 0
        for (offset in 0..6) {
            val day = today.minusDays(offset.toLong())
            val isAfterOrEqualStart = habitStartDate == null || !day.isBefore(habitStartDate)
            val dayOfWeekInt = getDayOfWeekInt(day.dayOfWeek)
            if (targetDays.contains(dayOfWeekInt) && isAfterOrEqualStart) {
                totalScheduledTargetDays++
                if (habitCompletions.contains(day.toString())) {
                    completedScheduledDays++
                }
            }
        }
        val weekProgress = if (totalScheduledTargetDays > 0) {
            completedScheduledDays.toFloat() / totalScheduledTargetDays
        } else {
            0.0f
        }

        val isGoalAchieved = habit.endDate?.let { endStr ->
            try {
                val end = LocalDate.parse(endStr)
                today.isAfter(end)
            } catch (e: Exception) {
                false
            }
        } ?: false

        val isUpcoming = habit.startDate?.let { startStr ->
            try {
                val start = LocalDate.parse(startStr)
                today.isBefore(start)
            } catch (e: Exception) {
                false
            }
        } ?: false

        val daysRemaining: Long? = habit.endDate?.let { endStr ->
            try {
                val end = LocalDate.parse(endStr)
                if (today.isAfter(end)) 0L else java.time.temporal.ChronoUnit.DAYS.between(today, end)
            } catch (e: Exception) {
                null
            }
        }

        val totalGoalDays: Long? = if (habit.startDate != null && habit.endDate != null) {
            try {
                val start = LocalDate.parse(habit.startDate)
                val end = LocalDate.parse(habit.endDate)
                val days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
                if (days > 0) days else 1L
            } catch (e: Exception) {
                null
            }
        } else null

        val goalProgress: Float? = if (totalGoalDays != null && habit.startDate != null && habit.endDate != null) {
            try {
                val start = LocalDate.parse(habit.startDate)
                val end = LocalDate.parse(habit.endDate)
                if (today.isBefore(start)) 0f
                else if (today.isAfter(end)) 1f
                else {
                    val passed = java.time.temporal.ChronoUnit.DAYS.between(start, today) + 1
                    (passed.toFloat() / totalGoalDays).coerceIn(0f, 1f)
                }
            } catch (e: Exception) {
                null
            }
        } else null

        return HabitWithStats(
            habit = habit,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            weekProgress = weekProgress,
            isCompletedToday = isCompletedToday,
            isGoalAchieved = isGoalAchieved,
            isUpcoming = isUpcoming,
            daysRemaining = daysRemaining,
            totalGoalDays = totalGoalDays,
            goalProgress = goalProgress
        )
    }

    private fun parseTargetDays(frequency: String, targetDaysStr: String): Set<Int> {
        return if (frequency == "DAILY") {
            (1..7).toSet()
        } else {
            targetDaysStr.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
        }
    }

    private fun getDayOfWeekInt(dayOfWeek: DayOfWeek): Int {
        return when (dayOfWeek) {
            DayOfWeek.MONDAY -> 1
            DayOfWeek.TUESDAY -> 2
            DayOfWeek.WEDNESDAY -> 3
            DayOfWeek.THURSDAY -> 4
            DayOfWeek.FRIDAY -> 5
            DayOfWeek.SATURDAY -> 6
            DayOfWeek.SUNDAY -> 7
        }
    }
}

// Data structures
data class HabitWithStats(
    val habit: Habit,
    val currentStreak: Int,
    val bestStreak: Int,
    val weekProgress: Float, // 0.0f to 1.0f
    val isCompletedToday: Boolean,
    val isGoalAchieved: Boolean = false,
    val isUpcoming: Boolean = false,
    val daysRemaining: Long? = null,
    val totalGoalDays: Long? = null,
    val goalProgress: Float? = null
)

data class OverallStats(
    val totalHabits: Int,
    val bestStreak: Int,
    val currentStreak: Int,
    val weekProgressPercentage: Float, // 0.0f to 1.0f
    val achievedGoalsCount: Int = 0
)

// Factory
class HabitViewModelFactory(
    private val application: Application,
    private val repository: HabitRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HabitViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HabitViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
