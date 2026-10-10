package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast action: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val pendingResult = goAsync()
            try {
                HabitReminderReceiver.rescheduleAllHabitReminders(context)
                Log.d("BootReceiver", "Triggered rescheduling of all habit reminders")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error while rescheduling alarms", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
