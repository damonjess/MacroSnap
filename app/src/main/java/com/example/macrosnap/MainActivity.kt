package com.example.macrosnap

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class PendingMealDraft(
    val result: NutritionResult,
    val source: String,
    val editingLog: MealLogEntity? = null
)

class MainActivity : ComponentActivity() {
    private val openScannerSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getInstance(this)
        val repo = NutritionRepository()

        if (intent?.action == MacroSnapWidget.ACTION_OPEN_SCANNER) {
            openScannerSignal.intValue++
        }

        setContent {
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    window.statusBarColor = BgDark.toArgb()
                    window.navigationBarColor = BgDark.toArgb()
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                }
            }
            MaterialTheme(colorScheme = MacroSnapColorScheme) {
                MacroSnapApp(
                    db = db,
                    repo = repo,
                    openScannerTrigger = openScannerSignal.intValue
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MacroSnapWidget.ACTION_OPEN_SCANNER) {
            openScannerSignal.intValue++
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroSnapApp(
    db: AppDatabase,
    repo: NutritionRepository,
    openScannerTrigger: Int = 0
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("macrosnap_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val dao = remember { db.mealLogDao() }
    val stepHelper = remember { StepCounterHelper(context) }

    var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
    var baseTdee by remember { mutableIntStateOf(prefs.getInt("base_tdee", 2200)) }
    var targetProtein by remember { mutableIntStateOf(prefs.getInt("target_protein", 160)) }
    var targetCarbs by remember { mutableIntStateOf(prefs.getInt("target_carbs", 220)) }
    var targetFat by remember { mutableIntStateOf(prefs.getInt("target_fat", 70)) }
    val targetFiber = remember { prefs.getInt("target_fiber", 30) }
    val targetWaterMl = remember { prefs.getInt("target_water", 2500) }

    if (!onboarded) {
        OnboardingScreen(
            initialWeightKg = prefs.getFloat("weight_kg", 108f).toInt(),
            initialHeightCm = prefs.getInt("height_cm", 178),
            initialAge = prefs.getInt("age", 40),
            onSaveProfile = { w, h, age, tdee, p, c, f ->
                prefs.edit()
                    .putBoolean("onboarded", true)
                    .putFloat("weight_kg", w.toFloat())
                    .putInt("height_cm", h)
                    .putInt("age", age)
                    .putInt("base_tdee", tdee)
                    .putInt("target_protein", p)
                    .putInt("target_carbs", c)
                    .putInt("target_fat", f)
                    .apply()
                baseTdee = tdee
                targetProtein = p
                targetCarbs = c
                targetFat = f
                onboarded = true
                scope.launch { MacroSnapWidget.pushWidgetUpdate(context) }
            }
        )
        return
    }

    val todayEpochDay = LocalDate.now().toEpochDay()
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Today, 1 = Trends
    var selectedEpochDay by remember { mutableLongStateOf(todayEpochDay) }
    val dayLogs by dao.observeLogsForDay(selectedEpochDay).collectAsState(initial = emptyList())
    val dayMetricsState by dao.observeMetricsForDay(selectedEpochDay).collectAsState(initial = null)
    val metrics = dayMetricsState ?: DailyMetricsEntity(dateEpochDay = selectedEpochDay)
    val recentLogs by dao.observeRecentLogs().collectAsState(initial = emptyList())

    var showCamera by remember { mutableStateOf(false) }
    var cameraLabelOcrMode by remember { mutableStateOf(false) }
    var showActivityDialog by remember { mutableStateOf(false) }
    var showQuickAddDialog by remember { mutableStateOf(false) }
    var quickAddInitialManual by remember { mutableStateOf(false) }
    var showRecentDialog by remember { mutableStateOf(false) }
    var showWeightDialog by remember { mutableStateOf(false) }
    var missingBarcodePrompt by remember { mutableStateOf<String?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("Analyzing nutrition & ingredients...") }
    var pendingDraft by remember { mutableStateOf<PendingMealDraft?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCamera = true
        else errorMessage = "Camera permission is required to scan meals and barcodes."
    }

    fun launchScanner(labelOcr: Boolean = false) {
        cameraLabelOcrMode = labelOcr
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) showCamera = true
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Handle widget "Scan" button launch
    LaunchedEffect(openScannerTrigger) {
        if (openScannerTrigger > 0) {
            selectedTab = 0
            selectedEpochDay = LocalDate.now().toEpochDay()
            launchScanner(labelOcr = false)
        }
    }

    suspend fun syncTodayStepsFromSensor() {
        if (!stepHelper.isAvailable) return
        val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasPerm) return

        val steps = stepHelper.readTodaySteps() ?: return
        val today = LocalDate.now().toEpochDay()
        val existing = dao.getMetricsForDayOnce(today) ?: DailyMetricsEntity(dateEpochDay = today)
        if (steps > existing.steps) {
            val active = StepCounterHelper.caloriesForSteps(steps)
            dao.upsertMetrics(existing.copy(steps = steps, activeCalories = active))
            MacroSnapWidget.pushWidgetUpdate(context)
        }
    }

    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { syncTodayStepsFromSensor() }
        }
    }

    // Auto-sync hardware step sensor on launch when viewing Today
    LaunchedEffect(Unit) {
        syncTodayStepsFromSensor()
    }

    if (showCamera) {
        CameraScannerScreen(
            initialLabelOcrMode = cameraLabelOcrMode,
            onPhotoCaptured = { bitmap, userHint, isLabelOcr ->
                showCamera = false
                isLoading = true
                loadingMessage = if (isLabelOcr) {
                    "Reading Nutrition Facts label..."
                } else {
                    "Analyzing meal portions & macros..."
                }
                errorMessage = null
                scope.launch {
                    val savedPhotoPath = withContext(Dispatchers.IO) {
                        NutritionRepository.saveMealThumbnail(context, bitmap)
                    }
                    repo.analyzeMealPhoto(bitmap, userHint = userHint, isNutritionLabelOcr = isLabelOcr)
                        .onSuccess { result ->
                            pendingDraft = PendingMealDraft(
                                result = result.copy(photoPath = savedPhotoPath),
                                source = if (isLabelOcr) "LABEL_OCR" else "AI_PHOTO"
                            )
                        }
                        .onFailure { errorMessage = it.message }
                    isLoading = false
                }
            },
            onBarcodeDetected = { barcode ->
                showCamera = false
                isLoading = true
                loadingMessage = "Looking up barcode $barcode..."
                errorMessage = null
                scope.launch {
                    repo.lookupBarcode(barcode)
                        .onSuccess { result ->
                            pendingDraft = PendingMealDraft(result = result, source = "BARCODE")
                        }
                        .onFailure { err ->
                            if (err is BarcodeNotFoundException) {
                                missingBarcodePrompt = barcode
                            } else {
                                errorMessage = err.message
                            }
                        }
                    isLoading = false
                }
            },
            onClose = { showCamera = false }
        )
        return
    }

    val dynamicCalorieTarget = baseTdee + metrics.activeCalories
    val totalCals = dayLogs.sumOf { it.calories }
    val totalProtein = dayLogs.sumOf { it.proteinGrams }.toInt()
    val totalCarbs = dayLogs.sumOf { it.carbsGrams }.toInt()
    val totalFat = dayLogs.sumOf { it.fatGrams }.toInt()
    val totalFiber = dayLogs.sumOf { it.fiberGrams }.toInt()

    val selectedDate = LocalDate.ofEpochDay(selectedEpochDay)
    val dateLabel = if (selectedEpochDay == todayEpochDay) {
        "Today"
    } else {
        selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(listOf(AccentEmerald, AccentCyan))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = BgDark, modifier = Modifier.size(20.dp))
                        }
                        Text("MacroSnap", fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val shareText = buildString {
                            appendLine("📊 MacroSnap Summary ($dateLabel)")
                            appendLine("🔥 Calories: $totalCals / $dynamicCalorieTarget kcal")
                            appendLine("🥩 Protein: ${totalProtein}g / ${targetProtein}g")
                            appendLine("🍞 Carbs: ${totalCarbs}g / ${targetCarbs}g")
                            appendLine("🥑 Fat: ${totalFat}g / ${targetFat}g")
                            appendLine("🌾 Fiber: ${totalFiber}g / ${targetFiber}g")
                            appendLine("💧 Water: ${metrics.waterMl} / $targetWaterMl ml")
                            append("👟 Steps: ${metrics.steps} (+${metrics.activeCalories} kcal)")
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Progress"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = TextSecondary)
                    }
                    IconButton(onClick = { onboarded = false }) {
                        Icon(Icons.Default.Tune, contentDescription = "Targets", tint = TextSecondary)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = CardDark) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Today, contentDescription = null) },
                    label = { Text("Today", fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentEmerald,
                        selectedTextColor = AccentEmerald,
                        indicatorColor = AccentEmerald.copy(alpha = 0.16f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null) },
                    label = { Text("Trends", fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentEmerald,
                        selectedTextColor = AccentEmerald,
                        indicatorColor = AccentEmerald.copy(alpha = 0.16f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { launchScanner(labelOcr = false) },
                    containerColor = AccentEmerald,
                    contentColor = Color(0xFF042F2E),
                    shape = RoundedCornerShape(20.dp),
                    icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                    text = { Text("Scan Meal / Barcode", fontWeight = FontWeight.Bold) }
                )
            }
        }
    ) { padding ->
        if (selectedTab == 1) {
            TrendsScreen(
                dao = dao,
                baseTdee = baseTdee,
                targetProtein = targetProtein,
                todayWeightKg = metrics.weightKg,
                onLogWeightClick = { showWeightDialog = true },
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 104.dp)
            ) {
                // Date Navigation Bar
                item {
                    Surface(
                        color = CardDark,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { selectedEpochDay-- }) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Day", tint = TextSecondary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.CalendarToday, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(16.dp))
                                Text(dateLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { if (selectedEpochDay < todayEpochDay) selectedEpochDay++ },
                                enabled = selectedEpochDay < todayEpochDay
                            ) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "Next Day",
                                    tint = if (selectedEpochDay < todayEpochDay) TextSecondary else TextSecondary.copy(alpha = 0.25f)
                                )
                            }
                        }
                    }
                }

                // Calorie Ring + Macro Progress Card
                item {
                    Surface(
                        color = CardDark,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatColumn(
                                    label = "Eaten",
                                    value = "$totalCals",
                                    unit = "kcal",
                                    color = AccentEmerald,
                                    modifier = Modifier.weight(1f)
                                )

                                val progress = (totalCals.toFloat() / dynamicCalorieTarget.coerceAtLeast(1)).coerceIn(0f, 1f)
                                val animatedProgress by animateFloatAsState(
                                    targetValue = progress,
                                    animationSpec = tween(durationMillis = 700),
                                    label = "calRing"
                                )
                                val remaining = dynamicCalorieTarget - totalCals

                                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                                    Canvas(modifier = Modifier.size(136.dp)) {
                                        val strokeWidth = 14.dp.toPx()
                                        drawArc(
                                            color = CardElevated,
                                            startAngle = 135f,
                                            sweepAngle = 270f,
                                            useCenter = false,
                                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                        )
                                        drawArc(
                                            brush = Brush.sweepGradient(listOf(AccentEmerald, AccentCyan, AccentEmerald)),
                                            startAngle = 135f,
                                            sweepAngle = 270f * animatedProgress,
                                            useCenter = false,
                                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "$remaining",
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (remaining >= 0) TextPrimary else Color(0xFFF87171)
                                        )
                                        Text(
                                            text = if (remaining >= 0) "kcal left" else "kcal over",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = "Goal $dynamicCalorieTarget",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary.copy(alpha = 0.7f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                StatColumn(
                                    label = "Burned",
                                    value = "+${metrics.activeCalories}",
                                    unit = "kcal",
                                    color = BurnOrange,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MacroMiniCard("Protein", totalProtein, targetProtein, ProteinBlue, Modifier.weight(1f))
                                    MacroMiniCard("Carbs", totalCarbs, targetCarbs, CarbsGreen, Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MacroMiniCard("Fat", totalFat, targetFat, FatAmber, Modifier.weight(1f))
                                    MacroMiniCard("Fiber", totalFiber, targetFiber, FiberPurple, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // Hydration & Activity Cards
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            color = CardDark,
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(WaterBlue.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.WaterDrop, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(18.dp))
                                    }
                                    Column {
                                        Text("Hydration", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text("${metrics.waterMl} / $targetWaterMl ml", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    }
                                }

                                SmoothProgressBar(
                                    progress = (metrics.waterMl.toFloat() / targetWaterMl).coerceIn(0f, 1f),
                                    color = WaterBlue,
                                    height = 7.dp
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        onClick = {
                                            scope.launch {
                                                dao.upsertMetrics(metrics.copy(waterMl = (metrics.waterMl - 250).coerceAtLeast(0)))
                                                MacroSnapWidget.pushWidgetUpdate(context)
                                            }
                                        },
                                        color = CardElevated,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                            Text("-250", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextSecondary)
                                        }
                                    }
                                    Surface(
                                        onClick = {
                                            scope.launch {
                                                dao.upsertMetrics(metrics.copy(waterMl = metrics.waterMl + 250))
                                                MacroSnapWidget.pushWidgetUpdate(context)
                                            }
                                        },
                                        color = WaterBlue,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                            Text("+250 ml", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        Surface(
                            onClick = { showActivityDialog = true },
                            color = CardDark,
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(BurnOrange.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = BurnOrange, modifier = Modifier.size(18.dp))
                                    }
                                    Column {
                                        Text("Activity", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text("${metrics.steps} steps", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                    }
                                }

                                SmoothProgressBar(
                                    progress = (metrics.steps.toFloat() / 10000f).coerceIn(0f, 1f),
                                    color = BurnOrange,
                                    height = 7.dp
                                )

                                Surface(
                                    onClick = { showActivityDialog = true },
                                    color = BurnOrange.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, BurnOrange.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                        Text(
                                            text = if (metrics.activeCalories > 0) "+${metrics.activeCalories} kcal • Edit" else "Log / Sync Steps",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = BurnOrange
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Fast Logging & Weight Check-In Strip
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickActionButton(
                            icon = Icons.Default.AutoAwesome,
                            label = "Quick Add",
                            tint = AccentEmerald,
                            onClick = {
                                quickAddInitialManual = false
                                showQuickAddDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionButton(
                            icon = Icons.Default.History,
                            label = "Favorites",
                            tint = FatAmber,
                            onClick = { showRecentDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionButton(
                            icon = Icons.Default.MonitorWeight,
                            label = metrics.weightKg?.let { "${"%.1f".format(it)} kg" } ?: "Weight",
                            tint = FiberPurple,
                            onClick = { showWeightDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (isLoading) {
                    item {
                        Surface(
                            color = CardDark,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = AccentEmerald)
                                Text(loadingMessage, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                errorMessage?.let { msg ->
                    item {
                        Surface(
                            color = Color(0xFF451A1A),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(msg, color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { errorMessage = null }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color(0xFFFCA5A5), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Logged Meals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${dayLogs.size} entries", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                }

                if (dayLogs.isEmpty()) {
                    item {
                        Surface(
                            color = CardDark.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Restaurant, contentDescription = null, tint = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                                Text("No meals logged for $dateLabel", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(
                                    "Tap 'Scan Meal / Barcode' or 'Quick Add' to log your food.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                } else {
                    // Group logs by MealCategory with per-category calorie subtotals
                    val grouped = MealCategory.entries.mapNotNull { cat ->
                        val catLogs = dayLogs.filter { MealCategory.fromString(it.category) == cat }
                        if (catLogs.isEmpty()) null else cat to catLogs
                    }

                    grouped.forEach { (category, catLogs) ->
                        item(key = "header_${category.name}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = category.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentCyan
                                )
                                Text(
                                    text = "${catLogs.sumOf { it.calories }} kcal",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        items(catLogs, key = { it.id }) { log ->
                            MealLogCard(
                                log = log,
                                onClick = {
                                    pendingDraft = PendingMealDraft(
                                        result = NutritionResult(
                                            mealName = log.name,
                                            calories = log.calories,
                                            proteinGrams = log.proteinGrams,
                                            carbsGrams = log.carbsGrams,
                                            fatGrams = log.fatGrams,
                                            fiberGrams = log.fiberGrams,
                                            confidenceNote = log.notes,
                                            subItems = repo.decodeSubItems(log.subItemsJson),
                                            photoPath = log.photoPath
                                        ),
                                        source = log.source,
                                        editingLog = log
                                    )
                                },
                                onToggleFavorite = {
                                    scope.launch {
                                        dao.setFavoriteByName(log.name, !log.isFavorite)
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        dao.delete(log)
                                        MacroSnapWidget.pushWidgetUpdate(context)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Activity & Hardware Step Sync Dialog
    if (showActivityDialog) {
        var stepsText by remember { mutableStateOf(if (metrics.steps == 0) "" else metrics.steps.toString()) }
        var activeCalsText by remember { mutableStateOf(if (metrics.activeCalories == 0) "" else metrics.activeCalories.toString()) }

        AlertDialog(
            containerColor = CardDark,
            onDismissRequest = { showActivityDialog = false },
            title = { Text("Activity & TDEE Boost", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Steps auto-estimate active burn (~40 kcal per 1,000 steps) and expand today's calorie target.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    if (stepHelper.isAvailable && selectedEpochDay == todayEpochDay) {
                        FilledTonalButton(
                            onClick = {
                                val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.ACTIVITY_RECOGNITION
                                    ) == PackageManager.PERMISSION_GRANTED
                                } else true

                                if (!hasPerm) {
                                    @Suppress("InlinedApi")
                                    activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                } else {
                                    scope.launch {
                                        stepHelper.readTodaySteps()?.let { s ->
                                            stepsText = s.toString()
                                            activeCalsText = StepCounterHelper.caloriesForSteps(s).toString()
                                        } ?: run {
                                            errorMessage = "Step sensor did not return a reading yet."
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = BurnOrange.copy(alpha = 0.2f),
                                contentColor = BurnOrange
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sync Phone Step Sensor", fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = {
                            stepsText = it
                            val s = it.toIntOrNull() ?: 0
                            activeCalsText = StepCounterHelper.caloriesForSteps(s).toString()
                        },
                        label = { Text("Daily Steps") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = activeCalsText,
                        onValueChange = { activeCalsText = it },
                        label = { Text("Active Calories Burned (kcal)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        dao.upsertMetrics(
                            metrics.copy(
                                steps = stepsText.toIntOrNull() ?: 0,
                                activeCalories = activeCalsText.toIntOrNull() ?: 0
                            )
                        )
                        MacroSnapWidget.pushWidgetUpdate(context)
                        showActivityDialog = false
                    }
                }) { Text("Save Activity", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showActivityDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Weight Check-In Dialog
    if (showWeightDialog) {
        var weightText by remember {
            mutableStateOf(
                (metrics.weightKg ?: prefs.getFloat("weight_kg", 108f)).let { "%.1f".format(it) }
            )
        }
        AlertDialog(
            containerColor = CardDark,
            onDismissRequest = { showWeightDialog = false },
            title = { Text("Log Body Weight ($dateLabel)", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text("Weight (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val w = weightText.toFloatOrNull()
                        if (w != null && w > 0f) {
                            scope.launch {
                                dao.upsertMetrics(metrics.copy(weightKg = w))
                                if (selectedEpochDay == todayEpochDay) {
                                    prefs.edit().putFloat("weight_kg", w).apply()
                                }
                                showWeightDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FiberPurple, contentColor = BgDark)
                ) { Text("Save Weight", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showWeightDialog = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }

    // Quick Add / AI Text Description Dialog
    if (showQuickAddDialog) {
        QuickAddMealDialog(
            initialManualMode = quickAddInitialManual,
            onDismiss = { showQuickAddDialog = false },
            onAnalyzeText = { description ->
                showQuickAddDialog = false
                isLoading = true
                loadingMessage = "Estimating macros from description..."
                errorMessage = null
                scope.launch {
                    repo.analyzeMealText(description)
                        .onSuccess { result ->
                            pendingDraft = PendingMealDraft(result = result, source = "AI_TEXT")
                        }
                        .onFailure { errorMessage = it.message }
                    isLoading = false
                }
            },
            onManualSubmit = { result, category ->
                showQuickAddDialog = false
                scope.launch {
                    dao.insert(
                        MealLogEntity(
                            dateEpochDay = selectedEpochDay,
                            name = result.mealName,
                            calories = result.calories,
                            proteinGrams = result.proteinGrams,
                            carbsGrams = result.carbsGrams,
                            fatGrams = result.fatGrams,
                            fiberGrams = result.fiberGrams,
                            source = "MANUAL",
                            category = category.name
                        )
                    )
                    MacroSnapWidget.pushWidgetUpdate(context)
                }
            }
        )
    }

    // Recent & Favorites Re-log Dialog
    if (showRecentDialog) {
        RecentAndFavoritesDialog(
            recentLogs = recentLogs,
            onDismiss = { showRecentDialog = false },
            onRelog = { template ->
                scope.launch {
                    dao.insert(
                        template.copy(
                            id = 0,
                            dateEpochDay = selectedEpochDay,
                            timestampMillis = System.currentTimeMillis(),
                            category = MealCategory.defaultForTimestamp().name
                        )
                    )
                    MacroSnapWidget.pushWidgetUpdate(context)
                    showRecentDialog = false
                }
            },
            onToggleFavorite = { item ->
                scope.launch {
                    dao.setFavoriteByName(item.name, !item.isFavorite)
                }
            }
        )
    }

    // Barcode Not Found -> Nutrition Label OCR Fallback Dialog
    missingBarcodePrompt?.let { barcode ->
        AlertDialog(
            containerColor = CardDark,
            onDismissRequest = { missingBarcodePrompt = null },
            title = { Text("Barcode Not Found", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "Product ($barcode) isn't in Open Food Facts yet. Snap the package's Nutrition Facts label instead and AI will read the macros automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        missingBarcodePrompt = null
                        launchScanner(labelOcr = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF042F2E))
                ) { Text("Scan Nutrition Label", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    missingBarcodePrompt = null
                    quickAddInitialManual = true
                    showQuickAddDialog = true
                }) { Text("Manual Entry", color = TextSecondary) }
            }
        )
    }

    // Review / Edit Meal Dialog
    pendingDraft?.let { draft ->
        ReviewNutritionDialog(
            initial = draft.result,
            isBarcode = draft.source == "BARCODE",
            initialCategory = draft.editingLog?.let { MealCategory.fromString(it.category) }
                ?: MealCategory.defaultForTimestamp(),
            isEditing = draft.editingLog != null,
            onDismiss = { pendingDraft = null },
            onConfirm = { edited, category ->
                scope.launch {
                    val encodedSubItems = repo.encodeSubItems(edited.subItems)
                    val existing = draft.editingLog
                    if (existing != null) {
                        dao.update(
                            existing.copy(
                                name = edited.mealName,
                                calories = edited.calories,
                                proteinGrams = edited.proteinGrams,
                                carbsGrams = edited.carbsGrams,
                                fatGrams = edited.fatGrams,
                                fiberGrams = edited.fiberGrams,
                                category = category.name,
                                notes = edited.confidenceNote,
                                subItemsJson = encodedSubItems
                            )
                        )
                    } else {
                        dao.insert(
                            MealLogEntity(
                                dateEpochDay = selectedEpochDay,
                                name = edited.mealName,
                                calories = edited.calories,
                                proteinGrams = edited.proteinGrams,
                                carbsGrams = edited.carbsGrams,
                                fatGrams = edited.fatGrams,
                                fiberGrams = edited.fiberGrams,
                                source = draft.source,
                                notes = edited.confidenceNote,
                                category = category.name,
                                photoPath = edited.photoPath,
                                subItemsJson = encodedSubItems
                            )
                        )
                    }
                    MacroSnapWidget.pushWidgetUpdate(context)
                    pendingDraft = null
                }
            }
        )
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = CardDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MealLogCard(
    log: MealLogEntity,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    val thumbBitmap: Bitmap? = remember(log.photoPath) {
        val path = log.photoPath ?: return@remember null
        val file = File(path)
        if (file.exists()) BitmapFactory.decodeFile(path) else null
    }

    Surface(
        onClick = onClick,
        color = CardDark,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap.asImageBitmap(),
                    contentDescription = log.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                val (badgeColor, badgeIcon) = when (log.source) {
                    "BARCODE" -> AccentCyan to Icons.Default.QrCode2
                    "LABEL_OCR" -> AccentCyan to Icons.Default.DocumentScanner
                    "AI_TEXT" -> AccentEmerald to Icons.Default.AutoAwesome
                    "MANUAL" -> FiberPurple to Icons.Default.Edit
                    else -> AccentEmerald to Icons.Default.CameraAlt
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = badgeIcon, contentDescription = null, tint = badgeColor)
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(log.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${log.calories} kcal", style = MaterialTheme.typography.labelMedium, color = AccentEmerald, fontWeight = FontWeight.Bold)
                    Text("P ${log.proteinGrams.toInt()}g", style = MaterialTheme.typography.labelMedium, color = ProteinBlue)
                    Text("C ${log.carbsGrams.toInt()}g", style = MaterialTheme.typography.labelMedium, color = CarbsGreen)
                    Text("F ${log.fatGrams.toInt()}g", style = MaterialTheme.typography.labelMedium, color = FatAmber)
                }
            }

            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (log.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (log.isFavorite) FatAmber else TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, unit: String, color: Color, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = color)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun MacroMiniCard(label: String, current: Int, target: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = CardElevated.copy(alpha = 0.65f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("$current / ${target}g", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            SmoothProgressBar(
                progress = (current.toFloat() / target.coerceAtLeast(1)).coerceIn(0f, 1f),
                color = color,
                height = 6.dp
            )
        }
    }
}

@Composable
private fun SmoothProgressBar(progress: Float, color: Color, height: Dp, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(targetValue = progress, animationSpec = tween(500), label = "bar")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun OnboardingScreen(
    initialWeightKg: Int,
    initialHeightCm: Int,
    initialAge: Int,
    onSaveProfile: (weight: Int, height: Int, age: Int, tdee: Int, protein: Int, carbs: Int, fat: Int) -> Unit
) {
    var weightStr by remember { mutableStateOf(initialWeightKg.toString()) }
    var heightStr by remember { mutableStateOf(initialHeightCm.toString()) }
    var ageStr by remember { mutableStateOf(initialAge.toString()) }
    var goalIndex by remember { mutableIntStateOf(0) }

    val w = weightStr.toIntOrNull() ?: 108
    val h = heightStr.toIntOrNull() ?: 178
    val a = ageStr.toIntOrNull() ?: 40

    val bmr = (10 * w) + (6.25 * h) - (5 * a) + 5
    val maintenance = (bmr * 1.35).toInt()
    val goalDelta = when (goalIndex) {
        0 -> -400
        1 -> 0
        else -> 300
    }
    val calculatedTdee = (maintenance + goalDelta).coerceAtLeast(1400)
    val calcProtein = (w * 2.0).toInt()
    val calcFat = (w * 0.9).toInt()
    val calcCarbs = ((calculatedTdee - (calcProtein * 4) - (calcFat * 9)) / 4).coerceAtLeast(50)

    Scaffold(containerColor = BgDark) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(AccentEmerald, AccentCyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = BgDark, modifier = Modifier.size(28.dp))
                }
                Column {
                    Text("MacroSnap Setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Text("Personalize your daily TDEE & macro targets", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }

            Surface(
                color = CardDark,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Body Metrics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = weightStr,
                            onValueChange = { weightStr = it },
                            label = { Text("Weight (kg)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = heightStr,
                            onValueChange = { heightStr = it },
                            label = { Text("Height (cm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = ageStr,
                            onValueChange = { ageStr = it },
                            label = { Text("Age") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select Your Goal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                GoalOptionCard(
                    title = "Lose Fat",
                    subtitle = "Sustainable calorie deficit for fat loss",
                    badge = "-400 kcal",
                    icon = Icons.Default.TrendingDown,
                    selected = goalIndex == 0,
                    onClick = { goalIndex = 0 }
                )
                GoalOptionCard(
                    title = "Maintain Weight",
                    subtitle = "Stay at your current body weight & fuel workouts",
                    badge = "Baseline",
                    icon = Icons.Default.Balance,
                    selected = goalIndex == 1,
                    onClick = { goalIndex = 1 }
                )
                GoalOptionCard(
                    title = "Build Muscle",
                    subtitle = "Controlled surplus for lean strength & size",
                    badge = "+300 kcal",
                    icon = Icons.Default.FitnessCenter,
                    selected = goalIndex == 2,
                    onClick = { goalIndex = 2 }
                )
            }

            Surface(
                color = CardDark,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.5.dp, Brush.linearGradient(listOf(AccentEmerald, AccentCyan))),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Daily Baseline Target", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            Text("$calculatedTdee kcal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = AccentEmerald)
                        }
                        Surface(color = AccentEmerald.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
                            Text(
                                "+ Dynamic Step Burn",
                                color = AccentEmerald,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TargetBadge("Protein", "${calcProtein}g", ProteinBlue, Modifier.weight(1f))
                        TargetBadge("Carbs", "${calcCarbs}g", CarbsGreen, Modifier.weight(1f))
                        TargetBadge("Fat", "${calcFat}g", FatAmber, Modifier.weight(1f))
                    }
                }
            }

            Button(
                onClick = { onSaveProfile(w, h, a, calculatedTdee, calcProtein, calcCarbs, calcFat) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald, contentColor = Color(0xFF042F2E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text("Launch Dashboard", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun GoalOptionCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) AccentEmerald.copy(alpha = 0.12f) else CardDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) AccentEmerald else Color.White.copy(alpha = 0.06f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) AccentEmerald else CardElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) BgDark else TextSecondary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Surface(
                color = if (selected) AccentEmerald else CardElevated,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) BgDark else TextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TargetBadge(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = CardElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.ExtraBold, color = color, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}
