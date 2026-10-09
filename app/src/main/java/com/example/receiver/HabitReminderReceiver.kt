package com.example.receiver

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
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

                if (habitId != -1L) {
                    val habit = db.habitDao().getHabitById(habitId)
                    if (habit != null && !habit.isArchived && habit.notifyEnabled && !isExpiredHabit(habit)) {
                        scheduleHabitReminder(context, habit)
                    }
                    val completions = db.habitDao().getCompletionsForHabit(habitId)
                    val isCompletedToday = completions.any { it.dateStr == todayStr }

                    if (habit != null && !habit.isArchived && habit.notifyEnabled && !isCompletedToday && !isExpiredHabit(habit)) {
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
                    scheduleDailyReminder(context)
                    val habits = db.habitDao().getAllHabits().first()
                    val completions = db.habitDao().getAllCompletionsFlow().first()
                    
                    val incompleteNotifyHabits = habits.filter { habit ->
                        val isExpired = habit.endDate != null && try {
                            LocalDate.now().isAfter(LocalDate.parse(habit.endDate))
                        } catch (e: Exception) { false }
                        !habit.isArchived && habit.notifyEnabled && !isExpired && !completions.any { it.habitId == habit.id && it.dateStr == todayStr }
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
        val channelId = "habit_reminder_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Напоминания о привычках",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ежедневные уведомления о невыполненных привычках"
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
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    companion object {
        private fun setExactAlarm(alarmManager: AlarmManager, triggerAtMillis: Long, pendingIntent: PendingIntent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } catch (e: SecurityException) {
                Log.e("HabitReminder", "SecurityException scheduling exact alarm, falling back", e)
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e("HabitReminder", "Failed to set alarm", e)
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        }

        fun scheduleDailyReminder(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, HabitReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }

            try {
                setExactAlarm(alarmManager, calendar.timeInMillis, pendingIntent)
                Log.d("HabitReminder", "Daily exact alarm scheduled for 8:00 AM. Next run at: ${calendar.time}")
            } catch (e: Exception) {
                Log.e("HabitReminder", "Failed to schedule Alarm", e)
            }
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

            val calendar = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(Calendar.HOUR_OF_DAY, habit.notifyHour)
                set(Calendar.MINUTE, habit.notifyMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }

            try {
                setExactAlarm(alarmManager, calendar.timeInMillis, pendingIntent)
                Log.d("HabitReminder", "Scheduled exact alarm for habit ${habit.id} (${habit.name}) at ${habit.notifyHour}:${habit.notifyMinute}")
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
            Log.d("HabitReminder", "Cancelled alarm for habit $habitId")
        }
    }
}
