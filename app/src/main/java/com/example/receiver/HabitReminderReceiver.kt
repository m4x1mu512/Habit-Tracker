package com.example.receiver

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.Habit
import com.example.data.HabitDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.time.LocalDate

class HabitReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getLongExtra("HABIT_ID", -1L)
        Log.d("HabitReminder", "Alarm triggered! habitId=$habitId")
        
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = HabitDatabase.getDatabase(context)
                val todayStr = LocalDate.now().toString()
                val todayDayOfWeek = LocalDate.now().dayOfWeek.value // 1..7 (Mon..Sun)

                val isExpiredHabit: (Habit) -> Boolean = { h ->
                    h.endDate != null && try {
                        LocalDate.now().isAfter(LocalDate.parse(h.endDate))
                    } catch (e: Exception) { false }
                }

                val isHabitStarted: (Habit) -> Boolean = { h ->
                    h.startDate == null || try {
                        !LocalDate.now().isBefore(LocalDate.parse(h.startDate))
                    } catch (e: Exception) { true }
                }

                if (habitId != -1L) {
                    val habit = db.habitDao().getHabitById(habitId)
                    if (habit != null && !habit.isArchived && habit.notifyEnabled && !isExpiredHabit(habit)) {
                        scheduleHabitReminder(context, habit)
                    }
                    val completions = db.habitDao().getCompletionsForHabit(habitId)
                    val isCompletedToday = completions.any { it.dateStr == todayStr }

                    if (habit != null && !habit.isArchived && habit.notifyEnabled && !isCompletedToday && !isExpiredHabit(habit) && isHabitStarted(habit)) {
                        val activeToday = if (habit.frequency == "DAILY") {
                            true
                        } else {
                            val activeDays = habit.targetDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                            activeDays.contains(todayDayOfWeek)
                        }

                        if (activeToday) {
                            showNotification(
                                context,
                                habitId.toInt() + 1000,
                                "Пришло время для привычки!",
                                "${habit.emoji} Напоминание: ${habit.name}"
                            )
                        }
                    }
                } else {
                    cancelDailyReminder(context)
                    val habits = db.habitDao().getAllHabits().first()
                    val completions = db.habitDao().getAllCompletionsFlow().first()
                    
                    val incompleteNotifyHabits = habits.filter { habit ->
                        val isExpired = habit.endDate != null && try {
                            LocalDate.now().isAfter(LocalDate.parse(habit.endDate))
                        } catch (e: Exception) { false }
                        val isStarted = habit.startDate == null || try {
                            !LocalDate.now().isBefore(LocalDate.parse(habit.startDate))
                        } catch (e: Exception) { true }
                        !habit.isArchived && habit.notifyEnabled && !isExpired && isStarted && !completions.any { it.habitId == habit.id && it.dateStr == todayStr }
                    }.filter { habit ->
                        if (habit.frequency == "DAILY") {
                            true
                        } else {
                            val activeDays = habit.targetDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                            activeDays.contains(todayDayOfWeek)
                        }
                    }

                    if (incompleteNotifyHabits.isNotEmpty()) {
                        val habitNames = incompleteNotifyHabits.joinToString(", ") { "${it.emoji} ${it.name}" }
                        showNotification(
                            context,
                            1001,
                            "Регулярное напоминание!",
                            "Не забудьте сегодня: $habitNames"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("HabitReminder", "Error launching notification check", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, notificationId: Int, title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "habit_reminder_channel_exact_v1"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Точные напоминания о привычках",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Гарантированные напоминания о привычках минута в минуту"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(pendingIntent, false) // Displays high priority Heads-Up banner
            .build()

        notificationManager.notify(notificationId, notification)
    }

    companion object {
        private fun setExactAlarm(context: Context, alarmManager: AlarmManager, triggerAtMillis: Long, pendingIntent: PendingIntent) {
            // Cancel any previous alarm with this PendingIntent
            try {
                alarmManager.cancel(pendingIntent)
            } catch (e: Exception) {
                // Ignore
            }

            val delayMillis = triggerAtMillis - System.currentTimeMillis()
            if (delayMillis < 0) {
                Log.w("HabitReminder", "Trigger time is in the past: $triggerAtMillis")
                return
            }

            // Priority 1: System AlarmClock (setAlarmClock guarantees minute-by-minute exact wakeups even in deep Doze)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        alarmManager.canScheduleExactAlarms()
                    } else {
                        true
                    }

                    if (canExact) {
                        val showIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        val showPendingIntent = PendingIntent.getActivity(
                            context,
                            pendingIntent.hashCode(),
                            showIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent)
                        alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                        Log.d("HabitReminder", "Exact alarm scheduled via setAlarmClock at millis: $triggerAtMillis (delay: $delayMillis ms)")
                        return
                    }
                }
            } catch (e: SecurityException) {
                Log.w("HabitReminder", "SecurityException on setAlarmClock, falling back to setExactAndAllowWhileIdle", e)
            } catch (e: Exception) {
                Log.w("HabitReminder", "Exception on setAlarmClock, falling back", e)
            }

            // Priority 2: setExactAndAllowWhileIdle
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        alarmManager.canScheduleExactAlarms()
                    } else {
                        true
                    }
                    if (canExact) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                        Log.d("HabitReminder", "Exact alarm scheduled via setExactAndAllowWhileIdle at millis: $triggerAtMillis (delay: $delayMillis ms)")
                        return
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    Log.d("HabitReminder", "Exact alarm scheduled via setExact at millis: $triggerAtMillis")
                    return
                }
            } catch (e: SecurityException) {
                Log.w("HabitReminder", "SecurityException on setExactAndAllowWhileIdle, falling back", e)
            } catch (e: Exception) {
                Log.w("HabitReminder", "Exception on setExactAndAllowWhileIdle", e)
            }

            // Fallback if exact alarms are restricted
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
                Log.d("HabitReminder", "Fallback inexact alarm scheduled at millis: $triggerAtMillis")
            } catch (e: Exception) {
                Log.e("HabitReminder", "Failed to set alarm", e)
            }
        }

        fun rescheduleAllHabitReminders(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = HabitDatabase.getDatabase(context)
                    cancelAllLegacyAlarms(context)
                    val habits = db.habitDao().getAllHabits().first()
                    for (habit in habits) {
                        if (habit.notifyEnabled && !habit.isArchived) {
                            scheduleHabitReminder(context, habit)
                        } else {
                            cancelHabitReminder(context, habit.id)
                        }
                    }
                    Log.d("HabitReminder", "Successfully rescheduled all habit reminders (${habits.size} habits)")
                } catch (e: Exception) {
                    Log.e("HabitReminder", "Error rescheduling all habit reminders", e)
                }
            }
        }

        fun cancelAllLegacyAlarms(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            cancelDailyReminder(context)

            for (code in (0..100) + (1000..1100)) {
                try {
                    val broadcastIntent = Intent(context, HabitReminderReceiver::class.java).apply {
                        if (code >= 1000) putExtra("HABIT_ID", (code - 1000).toLong())
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        code,
                        broadcastIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    alarmManager.cancel(pi)
                    pi.cancel()
                } catch (e: Exception) {
                    // Ignore
                }

                try {
                    val activityIntent = Intent(context, MainActivity::class.java)
                    val showIntent = PendingIntent.getActivity(
                        context,
                        code,
                        activityIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    alarmManager.cancel(showIntent)
                    showIntent.cancel()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            Log.d("HabitReminder", "Purged all legacy alarm clocks from AlarmManager")
        }

        fun cancelDailyReminder(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, HabitReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()

            try {
                val showIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(showIntent)
                showIntent.cancel()
            } catch (e: Exception) {
                // Ignore
            }
            Log.d("HabitReminder", "Cancelled legacy daily reminder (code 0)")
        }

        fun scheduleHabitReminder(context: Context, habit: Habit) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, HabitReminderReceiver::class.java).apply {
                putExtra("HABIT_ID", habit.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                habit.id.toInt() + 1000, // offset from 0 callback
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val isExpired = habit.endDate != null && try {
                LocalDate.now().isAfter(LocalDate.parse(habit.endDate))
            } catch (e: Exception) { false }

            if (!habit.notifyEnabled || habit.isArchived || isExpired) {
                alarmManager.cancel(pendingIntent)
                Log.d("HabitReminder", "Cancelled alarm for habit ${habit.id}")
                return
            }

            // Active days of week: 1 (Mon) .. 7 (Sun)
            val activeDays = if (habit.frequency == "DAILY") {
                (1..7).toSet()
            } else {
                habit.targetDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { (1..7).toSet() }
            }

            val today = LocalDate.now()
            val startDate = habit.startDate?.let {
                try {
                    val parsed = LocalDate.parse(it)
                    if (parsed.isAfter(today)) parsed else today
                } catch (e: Exception) { today }
            } ?: today

            val nowMillis = System.currentTimeMillis()
            var triggerCalendar: Calendar? = null
            var candidateDate = startDate

            // Look forward up to 14 days to find the next active day whose scheduled time is in the future
            for (offset in 0..14) {
                val testDate = startDate.plusDays(offset.toLong())
                val dayOfWeek = testDate.dayOfWeek.value // 1..7
                if (activeDays.contains(dayOfWeek)) {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = nowMillis
                        set(Calendar.YEAR, testDate.year)
                        set(Calendar.MONTH, testDate.monthValue - 1)
                        set(Calendar.DAY_OF_MONTH, testDate.dayOfMonth)
                        set(Calendar.HOUR_OF_DAY, habit.notifyHour)
                        set(Calendar.MINUTE, habit.notifyMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }

                    // Must be at least 3 seconds in the future to avoid clock jitter re-trigger
                    if (cal.timeInMillis > nowMillis + 3000L) {
                        triggerCalendar = cal
                        candidateDate = testDate
                        break
                    }
                }
            }

            if (triggerCalendar == null) {
                Log.w("HabitReminder", "Could not find valid next trigger date for habit ${habit.id}")
                return
            }

            val isPastEndDate = habit.endDate?.let { endStr ->
                try {
                    candidateDate.isAfter(LocalDate.parse(endStr))
                } catch (e: Exception) { false }
            } ?: false

            if (isPastEndDate) {
                alarmManager.cancel(pendingIntent)
                Log.d("HabitReminder", "Habit ${habit.id} alarm date $candidateDate is past end date ${habit.endDate}, cancelled.")
                return
            }

            try {
                setExactAlarm(context, alarmManager, triggerCalendar.timeInMillis, pendingIntent)
                Log.d("HabitReminder", "Scheduled exact alarm for habit ${habit.id} (${habit.name}) on $candidateDate at ${habit.notifyHour}:${habit.notifyMinute}")
            } catch (e: Exception) {
                Log.e("HabitReminder", "Failed to schedule Alarm for habit ${habit.id}", e)
            }
        }

        fun cancelHabitReminder(context: Context, habitId: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, HabitReminderReceiver::class.java).apply {
                putExtra("HABIT_ID", habitId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                habitId.toInt() + 1000,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()

            try {
                val showIntent = PendingIntent.getActivity(
                    context,
                    habitId.toInt() + 1000,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(showIntent)
                showIntent.cancel()
            } catch (e: Exception) {
                // Ignore
            }
            Log.d("HabitReminder", "Cancelled alarm for habit $habitId")
        }
    }
}
