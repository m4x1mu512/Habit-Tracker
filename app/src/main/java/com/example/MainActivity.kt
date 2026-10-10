package com.example

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.data.HabitDatabase
import com.example.data.HabitRepository
import com.example.receiver.HabitReminderReceiver
import com.example.ui.HabitViewModel
import com.example.ui.HabitViewModelFactory
import com.example.ui.MainHabitApp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Enable full edge-to-edge drawing
        enableEdgeToEdge()

        // 2. Request Notification Permission for Android 13+ (Tiramisu / API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = "android.permission.POST_NOTIFICATIONS"
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), 101)
                Log.d("HabitTracker", "Requested POST_NOTIFICATIONS permission.")
            }
        }

        // 3. Initialize Local Room Database and Repository
        val database = HabitDatabase.getDatabase(this)
        val repository = HabitRepository(database.habitDao())

        // 4. Instantiate HabitViewModel using its Factory
        val factory = HabitViewModelFactory(application, repository)
        val viewModel = ViewModelProvider(this, factory)[HabitViewModel::class.java]

        // 5. Purge all legacy alarm clocks so no alarm icon appears in status bar, and refresh active habit alarms
        CoroutineScope(Dispatchers.IO).launch {
            try {
                HabitReminderReceiver.cancelAllLegacyAlarms(applicationContext)
                val habits = database.habitDao().getAllHabits().first()
                for (habit in habits) {
                    if (habit.notifyEnabled && !habit.isArchived) {
                        HabitReminderReceiver.scheduleHabitReminder(applicationContext, habit)
                    }
                }
            } catch (e: Exception) {
                Log.e("HabitTracker", "Could not purge legacy alarms or refresh reminders", e)
            }
        }

        // 6. Set Jetpack Compose UI Content
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainHabitApp(viewModel)
                }
            }
        }
    }
}
