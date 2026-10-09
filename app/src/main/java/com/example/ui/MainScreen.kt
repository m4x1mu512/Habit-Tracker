package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.Habit
import androidx.compose.ui.res.painterResource
import com.example.R
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.*
import kotlin.random.Random
import android.provider.Settings
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.app.AlarmManager
import android.os.Build
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

// --- CONFETTI PHYSICS ENGINE ---
data class Particle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val color: Color,
    val size: Float,
    val rotation: Float,
    val rotationSpeed: Float,
    val alpha: Float = 1.0f
)

class ConfettiState {
    var particles by mutableStateOf(listOf<Particle>())
    var isActive by mutableStateOf(false)

    fun trigger() {
        val colors = listOf(
            Color(0xFF4285F4), // Blue
            Color(0xFF34A853), // Green
            Color(0xFFFBBC05), // Yellow
            Color(0xFFEA4335), // Red
            Color(0xFF9C27B0), // Purple
            Color(0xFF00BCD4)  // Cyan
        )
        // Spawn 80 particles heading downwards with slight dispersal
        val list = List(85) {
            Particle(
                x = Random.nextFloat() * 1000f,
                y = -50f,
                vx = (Random.nextFloat() - 0.5f) * 16f,
                vy = Random.nextFloat() * 12f + 8f,
                color = colors.random(),
                size = Random.nextFloat() * 18f + 12f,
                rotation = Random.nextFloat() * 360f,
                rotationSpeed = (Random.nextFloat() - 0.5f) * 12f
            )
        }
        particles = list
        isActive = true
    }

    fun update() {
        if (!isActive) return
        var allDead = true
        particles = particles.map { p ->
            val ny = p.y + p.vy
            val nx = p.x + p.vx
            val nvy = p.vy + 0.35f // Gravity
            val nrot = p.rotation + p.rotationSpeed
            val nalpha = (p.alpha - 0.005f).coerceAtLeast(0f)
            
            if (ny < 2200f && nalpha > 0f) allDead = false
            p.copy(x = nx, y = ny, vy = nvy, rotation = nrot, alpha = nalpha)
        }
        if (allDead) {
            isActive = false
            particles = emptyList()
        }
    }
}

// --- APP ENTRY COMPOSE VIEW ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHabitApp(viewModel: HabitViewModel) {
    val activeHabits by viewModel.activeHabitWithStats.collectAsState()
    val archivedHabits by viewModel.archivedHabitsWithStats.collectAsState()
    val completions by viewModel.allCompletions.collectAsState()
    val overallStats by viewModel.overallStats.collectAsState()

    val context = LocalContext.current
    var hasExactAlarmPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasExactAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    // Handle back button on secondary screens
    BackHandler(enabled = pagerState.currentPage != 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(0, animationSpec = tween(350, easing = FastOutSlowInEasing))
        }
    }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var habitToEdit by remember { mutableStateOf<Habit?>(null) }
    
    // Deletion Confirmation Dialog
    var habitToDelete by remember { mutableStateOf<Habit?>(null) }
    var quickReminderHabit by remember { mutableStateOf<Habit?>(null) }

    // Confetti manager
    val confettiState = remember { ConfettiState() }
    
    if (confettiState.isActive) {
        LaunchedEffect(Unit) {
            while (confettiState.isActive) {
                withFrameNanos {
                    confettiState.update()
                }
            }
        }
    }

    val isDark = isSystemInDarkTheme()
    val navBorderCol = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0)
    val navBgCol = if (isDark) Color(0xFF131416) else Color(0xFFFFFFFF)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = pagerState.currentPage,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally { width -> width / 3 } + fadeIn(tween(250))).togetherWith(
                                    slideOutHorizontally { width -> -width / 3 } + fadeOut(tween(200))
                                )
                            } else {
                                (slideInHorizontally { width -> -width / 3 } + fadeIn(tween(250))).togetherWith(
                                    slideOutHorizontally { width -> width / 3 } + fadeOut(tween(200))
                                )
                            }
                        },
                        label = "titleTransition"
                    ) { pageIndex ->
                        Text(
                            text = when (pageIndex) {
                                0 -> "Мои привычки"
                                1 -> "Статистика"
                                else -> "Архив"
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 24.sp,
                            color = if (isDark) Color.White else Color(0xFF1B1B1F)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = navBgCol,
                modifier = Modifier.drawBehind {
                    drawLine(
                        color = navBorderCol,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        if (pagerState.currentPage != 0) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(0, animationSpec = tween(350, easing = FastOutSlowInEasing))
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Привычки") },
                    label = { Text("Привычки") },
                    modifier = Modifier.testTag("nav_tab_habits")
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        if (pagerState.currentPage != 1) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1, animationSpec = tween(350, easing = FastOutSlowInEasing))
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = "Статистика") },
                    label = { Text("Статистика") },
                    modifier = Modifier.testTag("nav_tab_stats")
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = {
                        if (pagerState.currentPage != 2) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(2, animationSpec = tween(350, easing = FastOutSlowInEasing))
                            }
                        }
                    },
                    icon = { Icon(painter = painterResource(R.drawable.ic_archive_books), contentDescription = "Архив") },
                    label = { Text("Архив") },
                    modifier = Modifier.testTag("nav_tab_archive")
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = pagerState.currentPage == 0,
                enter = scaleIn(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
                exit = scaleOut(animationSpec = tween(180)) + fadeOut(animationSpec = tween(180))
            ) {
                FloatingActionButton(
                    onClick = {
                        habitToEdit = null
                        showAddEditDialog = true
                    },
                    containerColor = Color(0xFF4285F4),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("add_habit_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить привычку", modifier = Modifier.size(28.dp))
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main Content Area with smooth swipe and finger tracking
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                val absOffset = pageOffset.absoluteValue.coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Depth and layering: subtle scale & alpha so adjacent screen is beautifully visible
                            val scale = 1f - (absOffset * 0.04f)
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - (absOffset * 0.2f)
                        }
                ) {
                    when (page) {
                        0 -> Column(modifier = Modifier.fillMaxSize()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasExactAlarmPermission) {
                                ExactAlarmPermissionBanner(context) {
                                    hasExactAlarmPermission = true
                                }
                            }
                            HabitsTabContent(
                                habits = activeHabits,
                                onToggle = { item, checked ->
                                    viewModel.toggleCompletionToday(item.habit, checked)
                                    // Play Confetti if checking completed AND new/current streak achieves >= 7 days!
                                    if (checked) {
                                        val potentialStreak = item.currentStreak + (if (!item.isCompletedToday) 1 else 0)
                                        if (potentialStreak >= 7) {
                                            confettiState.trigger()
                                        }
                                    }
                                },
                                onEdit = { habit ->
                                    habitToEdit = habit
                                    showAddEditDialog = true
                                },
                                onEditTime = { habit ->
                                    quickReminderHabit = habit
                                },
                                onArchive = { habit ->
                                    viewModel.archiveHabit(habit, true)
                                },
                                onDeleteRequest = { habit ->
                                    habitToDelete = habit
                                }
                            )
                        }
                        1 -> StatisticsTabContent(
                            overallStats = overallStats,
                            activeHabits = activeHabits,
                            completions = completions
                        )
                        2 -> ArchiveTabContent(
                            archivedHabits = archivedHabits,
                            onUnarchive = { habit ->
                                viewModel.archiveHabit(habit, false)
                            },
                            onDeleteRequest = { habit ->
                                habitToDelete = habit
                            }
                        )
                    }
                }
            }

            // Confetti Overlay Layer
            if (confettiState.isActive) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    confettiState.particles.forEach { p ->
                        val sizeRaw = p.size
                        rotate(p.rotation, pivot = Offset(p.x, p.y)) {
                            drawRect(
                                color = p.color.copy(alpha = p.alpha),
                                topLeft = Offset(p.x - sizeRaw / 2, p.y - sizeRaw / 2),
                                size = Size(sizeRaw, sizeRaw)
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Add / Edit Custom Dialog
    if (showAddEditDialog) {
        AddEditHabitDialog(
            habit = habitToEdit,
            onDismiss = { showAddEditDialog = false },
            onSave = { name, desc, emoji, freq, targetDays, notify, hour, minute, startDate, endDate ->
                if (habitToEdit == null) {
                    viewModel.addHabit(name, desc, emoji, freq, targetDays, notify, hour, minute, startDate, endDate)
                } else {
                    viewModel.updateHabit(
                        id = habitToEdit!!.id,
                        name = name,
                        description = desc,
                        emoji = emoji,
                        frequency = freq,
                        targetDays = targetDays,
                        notifyEnabled = notify,
                        notifyHour = hour,
                        notifyMinute = minute,
                        isArchived = habitToEdit!!.isArchived,
                        startDate = startDate,
                        endDate = endDate
                    )
                }
                showAddEditDialog = false
            }
        )
    }

    // Deletion confirmation
    habitToDelete?.let { habit ->
        AlertDialog(
            onDismissRequest = { habitToDelete = null },
            title = { Text("Удалить привычку?") },
            text = { Text("Вы уверены, что хотите безвозвратно удалить привычку «${habit.emoji} ${habit.name}» и всю её историю выполнения?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteHabit(habit.id)
                        habitToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { habitToDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Quick Reminder Dial Picker Dialog for habit from list
    quickReminderHabit?.let { habit ->
        HabitTimePickerDialog(
            initialHour = habit.notifyHour,
            initialMinute = habit.notifyMinute,
            onDismissRequest = { quickReminderHabit = null },
            onConfirm = { hour, minute ->
                viewModel.updateHabitReminderTime(habit, hour, minute)
                quickReminderHabit = null
            }
        )
    }
}

// --- TAB 1: PASSIVE/ACTIVE HABITS ---
@Composable
fun HabitsTabContent(
    habits: List<HabitWithStats>,
    onToggle: (HabitWithStats, Boolean) -> Unit,
    onEdit: (Habit) -> Unit,
    onEditTime: (Habit) -> Unit,
    onArchive: (Habit) -> Unit,
    onDeleteRequest: (Habit) -> Unit
) {
    if (habits.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Пусто",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Ещё нет привычек",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Нажмите на кнопку «+», чтобы создать вашу первую полезную привычку!",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
        ) {
            items(habits, key = { it.habit.id }) { item ->
                HabitCardItem(
                    item = item,
                    onToggle = { checked -> onToggle(item, checked) },
                    onEdit = { onEdit(item.habit) },
                    onEditTime = { onEditTime(item.habit) },
                    onArchive = { onArchive(item.habit) },
                    onDelete = { onDeleteRequest(item.habit) }
                )
            }
        }
    }
}

// --- INDIVIDUAL HABIT CARD ---
@Composable
fun HabitCardItem(
    item: HabitWithStats,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onEditTime: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()

    val completed = item.isCompletedToday
    val isAchieved = item.isGoalAchieved

    val containerColor = if (isAchieved) {
        if (isDark) Color(0xFF1E211A) else Color(0xFFFBFDFB)
    } else if (completed) {
        if (isDark) Color(0xFF0F2615) else Color(0xFFF2FAF5)
    } else {
        if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
    }

    val borderColor = if (isAchieved) {
        if (isDark) Color(0xFFF59E0B).copy(alpha = 0.5f) else Color(0xFFF59E0B).copy(alpha = 0.4f)
    } else if (completed) {
        if (isDark) Color(0xFF34A853).copy(alpha = 0.3f) else Color(0xFF34A853).copy(alpha = 0.2f)
    } else {
        if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0)
    }

    val emojiBgColor = if (isAchieved) {
        if (isDark) Color(0xFF352B14) else Color(0xFFFEF3C7)
    } else if (completed) {
        if (isDark) Color(0xFF1B3B23) else Color(0xFFFFFFFF)
    } else {
        val hashCodeId = item.habit.name.hashCode() % 3
        if (isDark) {
            when (hashCodeId) {
                0 -> Color(0xFF1A263B)
                1 -> Color(0xFF2B2117)
                else -> Color(0xFF112E20)
            }
        } else {
            when (hashCodeId) {
                0 -> Color(0xFFE1EFFF)
                1 -> Color(0xFFFFF4E1)
                else -> Color(0xFFE8F5E9)
            }
        }
    }

    val titleColor = if (isDark) Color(0xFFE8EAED) else Color(0xFF1B1B1F)

    val seriesTextColor = if (isAchieved) {
        if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
    } else if (completed) {
        if (isDark) Color(0xFF81C995) else Color(0xFF34A853)
    } else {
        if (isDark) Color.Gray else Color(0xFF64748B)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .border(
                width = if (isAchieved) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(28.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (completed || isAchieved) 0.dp else 1.dp
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 20.dp)
            ) {
                // Goal Achieved celebratory banner
                if (isAchieved) {
                    Row(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isDark) Color(0xFF3A2D06) else Color(0xFFFEF3C7))
                            .border(
                                width = 1.dp,
                                color = if (isDark) Color(0xFFF59E0B).copy(alpha = 0.6f) else Color(0xFFFDE68A),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🏆", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Цель достигнута",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
                        )
                        item.habit.endDate?.let { endStr ->
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• завершено $endStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isDark) Color(0xFFFBBF24).copy(alpha = 0.75f) else Color(0xFFB45309).copy(alpha = 0.75f)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Emoji Icon bubble (rounded-2xl)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(emojiBgColor)
                            .then(
                                if (completed && !isDark) {
                                    Modifier.border(0.5.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = item.habit.emoji, fontSize = 24.sp)
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = item.habit.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAchieved) {
                                "Цель выполнена • Серия: ${item.bestStreak} дн. 🏅"
                            } else {
                                "Серия: ${item.currentStreak} дн." + if (item.currentStreak > 0) " 🔥" else ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (completed || isAchieved) FontWeight.Bold else FontWeight.Medium,
                            color = seriesTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!isAchieved && item.habit.endDate != null) {
                            val daysLeft = item.daysRemaining ?: 0L
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isDark) Color(0xFF1E293B) else Color(0xFFEFF6FF))
                                    .border(0.5.dp, if (isDark) Color(0xFF3B82F6).copy(alpha = 0.3f) else Color(0xFFBFDBFE), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (daysLeft == 0L) "Последний день срока!" else "Осталось: $daysLeft ${getDaysPlural(daysLeft)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Action controls matching interactive mockup
                    if (isAchieved) {
                        // Inactive status badge replacing button
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (isDark) Color(0xFF382A07) else Color(0xFFFEF3C7),
                            border = BorderStroke(1.dp, if (isDark) Color(0xFFF59E0B).copy(alpha = 0.5f) else Color(0xFFFDE68A)),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Цель достигнута",
                                    tint = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Цель достигнута",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
                                )
                            }
                        }
                    } else if (completed) {
                        IconButton(
                            onClick = { onToggle(false) },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF34A853))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Отметить выполненное",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = { onToggle(true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4285F4),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                text = "Выполнить",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Menu Trigger
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Меню",
                            tint = if (isDark) Color.LightGray else Color.Gray
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                        if (isAchieved) {
                            DropdownMenuItem(
                                text = { Text("Продлить / изменить срок") },
                                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, tint = Color(0xFFF59E0B)) },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Время напоминания") },
                                leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null, tint = Color(0xFFFF9800)) },
                                onClick = {
                                    showMenu = false
                                    onEditTime()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Редактировать") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Архивировать") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onArchive()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            if (!item.habit.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.habit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color.LightGray.copy(alpha = 0.7f) else Color(0xFF64748B),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Week progress section
            val progressPercent = (item.weekProgress * 100).toInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$progressPercent% за неделю",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (completed) {
                        if (isDark) Color(0xFF81C995) else Color(0xFF34A853)
                    } else {
                        Color(0xFF4285F4)
                    }
                )
                Text(
                    text = "Еженедельная цель",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color.Gray else Color(0xFF94A3B8)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress Bar
            LinearProgressIndicator(
                progress = { item.weekProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (completed) Color(0xFF34A853) else Color(0xFF4285F4),
                trackColor = if (completed) {
                    if (isDark) Color(0xFF1E3524) else Color(0xFFE2F0E7)
                } else {
                    if (isDark) Color(0xFF2C2D30) else Color(0xFFF1F5F9)
                }
            )
        }

        if (item.habit.notifyEnabled) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isDark) Color(0xFF2C2218) else Color(0xFFFFF7ED))
                    .border(
                        width = 1.dp,
                        color = if (isDark) Color(0xFFE65100).copy(alpha = 0.3f) else Color(0xFFFFE3B3),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onEditTime() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Alarm,
                    contentDescription = "Напоминание: нажмите для настройки времени",
                    tint = if (isDark) Color(0xFFFF9800) else Color(0xFFE65100),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                val timeFormattedStr = String.format(Locale.getDefault(), "%02d:%02d", item.habit.notifyHour, item.habit.notifyMinute)
                Text(
                    text = timeFormattedStr,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFFF9800) else Color(0xFFE65100)
                )
            }
        }
    }
}
}

// --- TAB 2: STATISTICS & DIAGRAMS ---
@Composable
fun StatisticsTabContent(
    overallStats: OverallStats,
    activeHabits: List<HabitWithStats>,
    completions: List<com.example.data.HabitCompletion>
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Overall Analytics Cards GRID
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, cardBorder, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Общие показатели",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.White else Color(0xFF1B1B1F)
                )
                Spacer(modifier = Modifier.height(14.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBox(value = "${overallStats.totalHabits}", label = "Всего привычек", modifier = Modifier.weight(1f))
                    StatBox(value = "${overallStats.currentStreak} дн.", label = "Общая серия", modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBox(value = "${overallStats.bestStreak} дн.", label = "Лучшая серия", modifier = Modifier.weight(1f))
                    StatBox(value = "${(overallStats.weekProgressPercentage * 100).toInt()}%", label = "Прогресс за неделю", modifier = Modifier.weight(1f))
                }
                if (overallStats.achievedGoalsCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatBox(
                            value = "${overallStats.achievedGoalsCount} 🏆",
                            label = "Достигнуто целей",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Weekly Bar Chart (Custom dynamic design with consistent theme)
        WeeklyBarChartCard(completions = completions)

        // Month Calendar
        CalendarCard(completions = completions)
    }
}

@Composable
fun StatBox(value: String, label: String, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val boxBg = if (isDark) Color(0xFF2B2D31) else Color(0xFFF8F9FF)
    val boxBorder = if (isDark) Color.White.copy(alpha = 0.05f) else Color(0xFFF1F5F9)
    Card(
        modifier = modifier
            .padding(4.dp)
            .border(1.dp, boxBorder, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = boxBg)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4285F4)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                color = if (isDark) Color.LightGray else Color(0xFF64748B)
            )
        }
    }
}

// --- CUSTOM CANVAS WEEK BAR CHART ---
@Composable
fun WeeklyBarChartCard(completions: List<com.example.data.HabitCompletion>) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorder, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "График за последние 7 суток",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color.White else Color(0xFF1B1B1F)
            )
            Spacer(modifier = Modifier.height(16.dp))

            val today = LocalDate.now()
            val past7Days = (0..6).map { today.minusDays(it.toLong()) }.reversed()
            val data = past7Days.map { date ->
                completions.count { it.dateStr == date.toString() }
            }
            val maxCount = (data.maxOrNull() ?: 1).coerceAtLeast(1)

            val primaryColor = Color(0xFF4285F4)
            val accentColor = Color(0xFF34A853)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                past7Days.forEachIndexed { index, date ->
                    val completionCount = data[index]
                    val fillPercent = completionCount.toFloat() / maxCount

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "$completionCount",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (completionCount > 0) primaryColor else Color.Gray.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(120.dp)
                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                .background(if (isDark) Color(0xFF2C2D31) else Color(0xFFF1F5F9)),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fillPercent.coerceAtLeast(0.01f))
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                if (completionCount >= 2) accentColor else primaryColor,
                                                primaryColor.copy(alpha = 0.7f)
                                            )
                                        )
                                    )
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        val dayLabel = when (date.dayOfWeek) {
                            java.time.DayOfWeek.MONDAY -> "Пн"
                            java.time.DayOfWeek.TUESDAY -> "Вт"
                            java.time.DayOfWeek.WEDNESDAY -> "Ср"
                            java.time.DayOfWeek.THURSDAY -> "Чт"
                            java.time.DayOfWeek.FRIDAY -> "Пт"
                            java.time.DayOfWeek.SATURDAY -> "Сб"
                            java.time.DayOfWeek.SUNDAY -> "Вс"
                        }
                        Text(
                            text = dayLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) Color.LightGray else Color(0xFF64748B)
                        )
                    }
                }
            }
        }
    }
}

// --- CURRENT MONTH CALENDAR ---
@Composable
fun CalendarCard(completions: List<com.example.data.HabitCompletion>) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE2E8F0)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, cardBorder, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            val now = LocalDate.now()
            val currentMonth = YearMonth.now()
            
            Text(
                "Календарь за ${currentMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag("ru")).replaceFirstChar { it.uppercase() }} ${currentMonth.year}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color.White else Color(0xFF1B1B1F)
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                val daysRu = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
                daysRu.forEach { dayName ->
                    Text(
                        text = dayName,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color.LightGray else Color(0xFF64748B)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            val firstOfMonth = currentMonth.atDay(1)
            val length = currentMonth.lengthOfMonth()
            val startDayOfWeek = firstOfMonth.dayOfWeek.value
            
            val daysInGrid = mutableListOf<LocalDate?>()
            for (i in 1 until startDayOfWeek) {
                daysInGrid.add(null)
            }
            for (i in 1..length) {
                daysInGrid.add(currentMonth.atDay(i))
            }

            val chunkedGrid = daysInGrid.chunked(7)
            chunkedGrid.forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    week.forEach { date ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            if (date != null) {
                                val completedCount = completions.count { it.dateStr == date.toString() }
                                val isToday = date == now

                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                completedCount >= 2 -> Color(0xFF34A853)
                                                completedCount == 1 -> Color(0xFF4285F4).copy(alpha = 0.4f)
                                                isToday -> (if (isDark) Color.White else Color.Black).copy(alpha = 0.1f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .border(
                                            width = if (isToday) 1.5.dp else 0.dp,
                                            color = if (isToday) Color(0xFF4285F4) else Color.Transparent,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${date.dayOfMonth}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (completedCount > 0 || isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            completedCount >= 2 -> Color.White
                                            completedCount == 1 -> if (isDark) Color.White else Color.Black
                                            else -> if (isDark) Color.LightGray else Color.Black
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF4285F4).copy(alpha = 0.4f)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("1 выполнено", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, color = if (isDark) Color.LightGray else Color(0xFF64748B))
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF34A853)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("2+ выполнено", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, color = if (isDark) Color.LightGray else Color(0xFF64748B))
            }
        }
    }
}

// --- TAB 3: ARCHIVE DIRECTORY ---
@Composable
fun ArchiveTabContent(
    archivedHabits: List<HabitWithStats>,
    onUnarchive: (Habit) -> Unit,
    onDeleteRequest: (Habit) -> Unit
) {
    if (archivedHabits.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Пусто",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Архив пуст",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Архивированные привычки временно скрываются с экрана, сохраняя ваши прошлые успехи.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
        ) {
            items(archivedHabits, key = { it.habit.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.habit.emoji, fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.habit.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Text(
                                "Серия: ${item.currentStreak} дн. | Лучшая: ${item.bestStreak}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }

                        // Restore Action
                        IconButton(onClick = { onUnarchive(item.habit) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Разархивировать", tint = MaterialTheme.colorScheme.secondary)
                        }
                        
                        // Delete Action
                        IconButton(onClick = { onDeleteRequest(item.habit) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// --- FULL FORM DIALOG FOR NEW/EDIT HABIT ---
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddEditHabitDialog(
    habit: Habit?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        desc: String?,
        emoji: String,
        frequency: String,
        targetDays: List<Int>,
        notify: Boolean,
        notifyHour: Int,
        notifyMinute: Int,
        startDate: String?,
        endDate: String?
    ) -> Unit
) {
    var name by remember { mutableStateOf(habit?.name ?: "") }
    var description by remember { mutableStateOf(habit?.description ?: "") }
    var selectedEmoji by remember { mutableStateOf(habit?.emoji ?: "💧") }
    var frequency by remember { mutableStateOf(habit?.frequency ?: "DAILY") } // "DAILY" or "WEEKLY"
    
    // Day selections for Specific Days (1..7 matching Mon..Sun)
    val parsedDays = habit?.targetDays?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(1, 2, 3, 4, 5, 6, 7)
    var targetDays by remember { mutableStateOf(parsedDays.toSet()) }

    val today = remember { LocalDate.now() }
    var isIndefinite by remember { mutableStateOf(habit?.endDate == null) }
    var targetEndDate by remember {
        mutableStateOf(
            habit?.endDate?.let {
                try { LocalDate.parse(it) } catch (e: Exception) { null }
            } ?: today.plusDays(21)
        )
    }
    var showDatePicker by remember { mutableStateOf(false) }

    var notifyEnabled by remember { mutableStateOf(habit?.notifyEnabled ?: false) }
    var notifyHour by remember { mutableStateOf(habit?.notifyHour ?: 8) }
    var notifyMinute by remember { mutableStateOf(habit?.notifyMinute ?: 0) }
    var showTimePicker by remember { mutableStateOf(false) }

    val emojis = listOf("💧", "🏃‍♂️", "📚", "🧘", "💊", "🛏️", "🥗", "🎸", "🧹", "🎯")
    val daysOfWeekRu = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    var nameError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title
                Text(
                    text = if (habit == null) "Новая привычка" else "Редактирование",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // Habit Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = it.isBlank()
                    },
                    label = { Text("Название привычки *") },
                    isError = nameError,
                    supportingText = { if (nameError) Text("Название обязательно для заполнения", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Optional description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание (опционально)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                // Emoji Selection GRID (horizontal lists or wrapped flows)
                Text(
                    "Выберите иконку (эмодзи)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                
                // Wrap in flowing row
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    emojis.forEach { emoji ->
                        val isSelected = selectedEmoji == emoji
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { selectedEmoji = emoji },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 20.sp)
                        }
                    }
                }

                // Goal Target Frequency selection
                Text(
                    "Регулярность цели",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilterChip(
                        selected = frequency == "DAILY",
                        onClick = { frequency = "DAILY" },
                        label = { Text("Каждый день") }
                    )
                    FilterChip(
                        selected = frequency == "WEEKLY",
                        onClick = { frequency = "WEEKLY" },
                        label = { Text("По дням недели") }
                    )
                }

                // If "Specific Days" selected, show Mon-Sun grid selection checkboxes
                if (frequency == "WEEKLY") {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Дни выполнения:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            (1..7).forEach { dayNum ->
                                val isSelected = targetDays.contains(dayNum)
                                val dayLabel = daysOfWeekRu[dayNum - 1]
                                
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable {
                                            targetDays = if (isSelected) {
                                                if (targetDays.size > 1) targetDays - dayNum else targetDays
                                            } else {
                                                targetDays + dayNum
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dayLabel,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Goal Duration Selection (Срок активности привычки)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Срок действия привычки",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = isIndefinite,
                            onClick = { isIndefinite = true },
                            label = { Text("Бессрочно") },
                            leadingIcon = if (isIndefinite) {
                                { Icon(Icons.Default.AllInclusive, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = !isIndefinite,
                            onClick = { isIndefinite = false },
                            label = { Text("Выбрать срок") },
                            leadingIcon = if (!isIndefinite) {
                                { Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }

                    if (!isIndefinite) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSystemInDarkTheme()) Color(0xFF1E1F22) else Color(0xFFF8F9FF))
                                .border(1.dp, if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.05f) else Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "Количество дней (быстрый выбор):",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val presets = listOf(7L to "7 дней", 14L to "14 дней", 21L to "21 день", 30L to "30 дней", 60L to "60 дней", 100L to "100 дней")
                                presets.forEach { (days, label) ->
                                    val presetDate = today.plusDays(days)
                                    val isSelected = targetEndDate == presetDate
                                    SuggestionChip(
                                        onClick = { targetEndDate = presetDate },
                                        label = { Text(label, fontSize = 12.sp) },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                            labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        ),
                                        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            val daysFromToday = ChronoUnit.DAYS.between(today, targetEndDate).coerceAtLeast(0)
                            val dateFormatterRu = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
                            val formattedDate = targetEndDate.format(dateFormatterRu)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                    .clickable { showDatePicker = true }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CalendarMonth,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Активна до: $formattedDate",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (daysFromToday == 0L) "Завершается сегодня" else "Срок: $daysFromToday ${getDaysPlural(daysFromToday)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                FilledTonalButton(
                                    onClick = { showDatePicker = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Календарь", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (showDatePicker) {
                        HabitDatePickerDialog(
                            initialDate = targetEndDate,
                            onDismissRequest = { showDatePicker = false },
                            onDateSelected = { pickedDate ->
                                targetEndDate = pickedDate
                                showDatePicker = false
                            }
                        )
                    }
                }

                // Notification toggle Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Напоминания",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", notifyHour, notifyMinute)
                        Text(
                            "Пуш-ремайндер в $timeFormatted",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = notifyEnabled,
                        onCheckedChange = { notifyEnabled = it }
                    )
                }

                if (notifyEnabled) {
                    // Interactive Time selection trigger block
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSystemInDarkTheme()) Color(0xFF1E1F22) else Color(0xFFF8F9FF))
                            .border(1.dp, if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.05f) else Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                            .clickable { showTimePicker = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Время напоминания",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSystemInDarkTheme()) Color.LightGray else Color(0xFF1F2937)
                                )
                                Text(
                                    "Выбрать на циферблате",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", notifyHour, notifyMinute)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = timeFormatted,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                if (showTimePicker) {
                    HabitTimePickerDialog(
                        initialHour = notifyHour,
                        initialMinute = notifyMinute,
                        onDismissRequest = { showTimePicker = false },
                        onConfirm = { h, m ->
                            notifyHour = h
                            notifyMinute = m
                            showTimePicker = false
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions Save/Cancel buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                nameError = true
                            } else {
                                onSave(
                                    name,
                                    description.takeIf { it.isNotBlank() },
                                    selectedEmoji,
                                    frequency,
                                    if (frequency == "DAILY") (1..7).toList() else targetDays.toList(),
                                    notifyEnabled,
                                    notifyHour,
                                    notifyMinute,
                                    habit?.startDate ?: today.toString(),
                                    if (isIndefinite) null else targetEndDate.toString()
                                )
                            }
                        }
                    ) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}

// --- CALENDAR DATE PICKER DIALOG ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDatePickerDialog(
    initialDate: LocalDate,
    onDismissRequest: () -> Unit,
    onDateSelected: (LocalDate) -> Unit
) {
    val initialMillis = remember(initialDate) {
        initialDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis
    )

    DatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val selectedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        onDateSelected(selectedDate)
                    } ?: onDismissRequest()
                }
            ) {
                Text("Выбрать", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Отмена")
            }
        }
    ) {
        DatePicker(
            state = datePickerState,
            title = {
                Text(
                    text = "Срок окончания привычки",
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            headline = {
                Text(
                    text = "Выберите дату на календаре",
                    modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

fun getDaysPlural(days: Long): String {
    val d = days.toInt()
    val mod10 = d % 10
    val mod100 = d % 100
    return when {
        mod100 in 11..14 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    var isDialMode by remember { mutableStateOf(true) }
    var selectedHour by remember { mutableIntStateOf(initialHour) }
    var selectedMinute by remember { mutableIntStateOf(initialMinute) }
    var stateKey by remember { mutableIntStateOf(0) }

    val timePickerState = key(stateKey) {
        rememberTimePickerState(
            initialHour = selectedHour,
            initialMinute = selectedMinute,
            is24Hour = true
        )
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with title, description and mode switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Время напоминания",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color.White else Color(0xFF1F2937)
                            )
                            Text(
                                text = if (isDialMode) "Крутите стрелки циферблата" else "Ввод времени цифрами",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDark) Color.LightGray else Color(0xFF64748B)
                            )
                        }
                    }

                    // Mode switch icon button (Clock dial <-> Keyboard)
                    IconButton(
                        onClick = {
                            selectedHour = timePickerState.hour
                            selectedMinute = timePickerState.minute
                            isDialMode = !isDialMode
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color(0xFF2B2D31) else Color(0xFFF1F5F9))
                    ) {
                        Icon(
                            imageVector = if (isDialMode) Icons.Default.Keyboard else Icons.Default.Schedule,
                            contentDescription = if (isDialMode) "Переключить на ввод с клавиатуры" else "Переключить на циферблат",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf(
                        Triple("🌅", "08:00", Pair(8, 0)),
                        Triple("☀️", "13:00", Pair(13, 0)),
                        Triple("🌆", "19:00", Pair(19, 0)),
                        Triple("🌙", "21:30", Pair(21, 30))
                    )
                    presets.forEach { (emoji, label, time) ->
                        val isSelected = timePickerState.hour == time.first && timePickerState.minute == time.second
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else if (isDark) Color(0xFF2B2D31) else Color(0xFFF1F5F9),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    selectedHour = time.first
                                    selectedMinute = time.second
                                    stateKey++
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(emoji, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else if (isDark) Color.White else Color(0xFF1F2937)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Time picker container with clock face (циферблат)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isDark) Color(0xFF17181A) else Color(0xFFF8FAFC))
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDialMode) {
                        TimePicker(
                            state = timePickerState,
                            colors = TimePickerDefaults.colors(
                                clockDialColor = if (isDark) Color(0xFF26282D) else Color(0xFFEBF1FD),
                                selectorColor = MaterialTheme.colorScheme.primary,
                                clockDialSelectedContentColor = Color.White,
                                clockDialUnselectedContentColor = if (isDark) Color.LightGray else Color(0xFF334155),
                                timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                timeSelectorUnselectedContainerColor = if (isDark) Color(0xFF26282D) else Color(0xFFE2E8F0),
                                timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                timeSelectorUnselectedContentColor = if (isDark) Color.White else Color(0xFF1E293B)
                            )
                        )
                    } else {
                        TimeInput(
                            state = timePickerState,
                            colors = TimePickerDefaults.colors(
                                timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                timeSelectorUnselectedContainerColor = if (isDark) Color(0xFF26282D) else Color(0xFFE2E8F0),
                                timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                timeSelectorUnselectedContentColor = if (isDark) Color.White else Color(0xFF1E293B)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Minute adjustments
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Сдвиг минут:",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color.Gray else Color(0xFF64748B)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(-15, -5, 5, 15).forEach { offset ->
                            val sign = if (offset > 0) "+$offset" else "$offset"
                            SuggestionChip(
                                onClick = {
                                    var newMin = timePickerState.minute + offset
                                    var newHour = timePickerState.hour
                                    if (newMin < 0) {
                                        newMin += 60
                                        newHour = (newHour - 1 + 24) % 24
                                    } else if (newMin >= 60) {
                                        newMin -= 60
                                        newHour = (newHour + 1) % 24
                                    }
                                    selectedHour = newHour
                                    selectedMinute = newMin
                                    stateKey++
                                },
                                label = { Text(sign, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Actions: Cancel & Confirm buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(
                            text = "Отмена",
                            color = if (isDark) Color.LightGray else Color(0xFF4B5563)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(timePickerState.hour, timePickerState.minute)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format(
                                Locale.getDefault(),
                                "Выбрать %02d:%02d",
                                timePickerState.hour,
                                timePickerState.minute
                            ),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExactAlarmPermissionBanner(
    context: Context,
    onPermissionGranted: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF3B1E1E) else Color(0xFFFDE8E8)
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isDark) Color(0xFF9B2C2C).copy(alpha = 0.4f) else Color(0xFFF8B4B4)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Предупреждение",
                tint = if (isDark) Color(0xFFF8B4B4) else Color(0xFF9B2C2C),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Разрешите точные напоминания",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFFDE8E8) else Color(0xFF9B2C2C)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Чтобы напоминания приходили строго вовремя, приложению требуется системное разрешение на показ точных будильников.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color(0xFFF8B4B4) else Color(0xFF7F1D1D)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (ex: Exception) {
                                Log.e("HabitTracker", "Failed to open settings", ex)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0xFF9B2C2C) else Color(0xFFE02424),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Предоставить разрешение", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
