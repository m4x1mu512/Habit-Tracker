package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.HabitDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device reboot completed, rescheduling alarms")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = HabitDatabase.getDatabase(context)
                    // Schedule daily reminder
                    HabitReminderReceiver.scheduleDailyReminder(context)
                    // Obtain all and schedule active ones
                    val habits = db.habitDao().getAllHabits().first()
                    for (habit in habits) {
                        if (habit.notifyEnabled && !habit.isArchived) {
                            HabitReminderReceiver.scheduleHabitReminder(context, habit)
                        }
                    }
                    Log.d("BootReceiver", "Successfully rescheduled all alarms after boot")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error while rescheduling alarms after boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
