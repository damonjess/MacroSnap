package com.example.macrosnap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private data class DayTrendPoint(
    val epochDay: Long,
    val shortLabel: String,
    val calories: Int,
    val calorieTarget: Int,
    val protein: Int,
    val waterMl: Int,
    val activeCalories: Int,
    val weightKg: Float?
)

@Composable
fun TrendsScreen(
    dao: MealLogDao,
    baseTdee: Int,
    targetProtein: Int,
    todayWeightKg: Float?,
    onLogWeightClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var windowDays by remember { mutableIntStateOf(7) }
    val today = remember { LocalDate.now().toEpochDay() }
    val startDay = today - (windowDays - 1)

    val logs by dao.observeLogsSince(startDay).collectAsState(initial = emptyList())
    val metricsList by dao.observeMetricsSince(startDay).collectAsState(initial = emptyList())

    val logsByDay = remember(logs) { logs.groupBy { it.dateEpochDay } }
    val metricsByDay = remember(metricsList) { metricsList.associateBy { it.dateEpochDay } }

    val points = remember(windowDays, logsByDay, metricsByDay, baseTdee, today) {
        val dayFmt = DateTimeFormatter.ofPattern("E")
        val dateFmt = DateTimeFormatter.ofPattern("d/M")
        (0 until windowDays).map { offset ->
            val day = startDay + offset
            val dayLogs = logsByDay[day].orEmpty()
            val m = metricsByDay[day]
            val date = LocalDate.ofEpochDay(day)
            DayTrendPoint(
                epochDay = day,
                shortLabel = if (windowDays <= 7) date.format(dayFmt).take(2) else date.format(dateFmt),
                calories = dayLogs.sumOf { it.calories },
                calorieTarget = baseTdee + (m?.activeCalories ?: 0),
                protein = dayLogs.sumOf { it.proteinGrams }.toInt(),
                waterMl = m?.waterMl ?: 0,
                activeCalories = m?.activeCalories ?: 0,
                weightKg = m?.weightKg
            )
        }
    }

    val loggedDays = points.filter { it.calories > 0 }
    val avgCalories = if (loggedDays.isNotEmpty()) loggedDays.map { it.calories }.average().roundToInt() else 0
    val avgProtein = if (loggedDays.isNotEmpty()) loggedDays.map { it.protein }.average().roundToInt() else 0
    val totalBurned = points.sumOf { it.activeCalories }
    val avgWater = points.map { it.waterMl }.filter { it > 0 }.let {
        if (it.isNotEmpty()) it.average().roundToInt() else 0
    }

    val weightEntries = points.mapIndexedNotNull { idx, p -> p.weightKg?.let { idx to it } }
    val latestWeight = weightEntries.lastOrNull()?.second ?: todayWeightKg
    val weightDelta = if (weightEntries.size >= 2) {
        weightEntries.last().second - weightEntries.first().second
    } else null

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 104.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Nutrition & Weight Trends", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7, 14, 30).forEach { days ->
                        FilterChip(
                            selected = windowDays == days,
                            onClick = { windowDays = days },
                            label = { Text("${days}D", fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CardDark,
                                labelColor = TextSecondary,
                                selectedContainerColor = AccentEmerald,
                                selectedLabelColor = Color(0xFF042F2E)
                            )
                        )
                    }
                }
            }
        }

        // Summary Averages
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrendSummaryCard("Avg Calories", "$avgCalories kcal", "Goal ~$baseTdee", AccentEmerald, Modifier.weight(1f))
                TrendSummaryCard("Avg Protein", "${avgProtein}g", "Goal ${targetProtein}g", ProteinBlue, Modifier.weight(1f))
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrendSummaryCard("Active Burn", "+$totalBurned kcal", "Last $windowDays days", BurnOrange, Modifier.weight(1f))
                TrendSummaryCard("Avg Water", "$avgWater ml", "Hydrated days", WaterBlue, Modifier.weight(1f))
            }
        }

        // Calorie Adherence Bar Chart
        item {
            Surface(
                color = CardDark,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Calorie Adherence", fontWeight = FontWeight.Bold)
                            Text("Dashed line = daily TDEE target", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LegendDot(AccentEmerald, "On target")
                            LegendDot(Color(0xFFF87171), "Over")
                        }
                    }

                    val maxCal = max(baseTdee + 400, points.maxOfOrNull { max(it.calories, it.calorieTarget) } ?: baseTdee)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .padding(top = 6.dp)
                    ) {
                        val barCount = points.size.coerceAtLeast(1)
                        val slotWidth = size.width / barCount
                        val barWidth = (slotWidth * 0.58f).coerceAtMost(28.dp.toPx())

                        // Dashed baseline TDEE target line
                        val targetY = size.height * (1f - (baseTdee.toFloat() / maxCal.coerceAtLeast(1)))
                        drawLine(
                            color = TextSecondary.copy(alpha = 0.5f),
                            start = Offset(0f, targetY),
                            end = Offset(size.width, targetY),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                        )

                        points.forEachIndexed { index, pt ->
                            val frac = (pt.calories.toFloat() / maxCal.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val barHeight = (size.height * frac).coerceAtLeast(if (pt.calories > 0) 4.dp.toPx() else 2.dp.toPx())
                            val x = index * slotWidth + (slotWidth - barWidth) / 2f
                            val y = size.height - barHeight
                            val barColor = when {
                                pt.calories == 0 -> CardElevated
                                pt.calories > pt.calorieTarget -> Color(0xFFF87171)
                                else -> AccentEmerald
                            }
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            )
                        }
                    }

                    // Labels row (show every label for 7D, sampled for 14D/30D)
                    val labelStep = when {
                        windowDays <= 7 -> 1
                        windowDays <= 14 -> 2
                        else -> 5
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        points.forEachIndexed { idx, pt ->
                            if (idx % labelStep == 0 || idx == points.lastIndex) {
                                Text(pt.shortLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // Protein Adherence Bar Chart
        item {
            Surface(
                color = CardDark,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Protein Consistency", fontWeight = FontWeight.Bold)
                            Text("Target ${targetProtein}g / day", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        val hitDays = loggedDays.count { it.protein >= (targetProtein * 0.85f) }
                        Text(
                            "$hitDays / ${loggedDays.size.coerceAtLeast(1)} days ≥85%",
                            style = MaterialTheme.typography.labelMedium,
                            color = ProteinBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val maxProtein = max(targetProtein + 30, points.maxOfOrNull { it.protein } ?: targetProtein)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    ) {
                        val barCount = points.size.coerceAtLeast(1)
                        val slotWidth = size.width / barCount
                        val barWidth = (slotWidth * 0.58f).coerceAtMost(28.dp.toPx())

                        val targetY = size.height * (1f - (targetProtein.toFloat() / maxProtein.coerceAtLeast(1)))
                        drawLine(
                            color = ProteinBlue.copy(alpha = 0.5f),
                            start = Offset(0f, targetY),
                            end = Offset(size.width, targetY),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                        )

                        points.forEachIndexed { index, pt ->
                            val frac = (pt.protein.toFloat() / maxProtein.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val barHeight = (size.height * frac).coerceAtLeast(if (pt.protein > 0) 4.dp.toPx() else 2.dp.toPx())
                            val x = index * slotWidth + (slotWidth - barWidth) / 2f
                            val y = size.height - barHeight
                            drawRoundRect(
                                color = if (pt.protein == 0) CardElevated else ProteinBlue,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            )
                        }
                    }
                }
            }
        }

        // Body Weight & Moving Average Card
        item {
            Surface(
                color = CardDark,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(FiberPurple.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MonitorWeight, contentDescription = null, tint = FiberPurple, modifier = Modifier.size(18.dp))
                            }
                            Column {
                                Text("Body Weight Trend", fontWeight = FontWeight.Bold)
                                val deltaText = weightDelta?.let { d ->
                                    val sign = if (d >= 0f) "+" else ""
                                    " (${sign}${"%.1f".format(d)} kg in ${windowDays}d)"
                                } ?: ""
                                Text(
                                    text = latestWeight?.let { "${"%.1f".format(it)} kg$deltaText" } ?: "No weigh-ins yet",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = onLogWeightClick,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = FiberPurple.copy(alpha = 0.18f),
                                contentColor = FiberPurple
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Log Weight", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (weightEntries.size >= 2) {
                        val weights = weightEntries.map { it.second }
                        val minW = (weights.min() - 1f)
                        val maxW = (weights.max() + 1f).coerceAtLeast(minW + 2f)
                        val span = maxW - minW

                        // 3-point moving average for smoothed trend curve
                        val smoothed = weightEntries.mapIndexed { i, (dayIdx, _) ->
                            val slice = weightEntries.subList(max(0, i - 2), i + 1).map { it.second }
                            dayIdx to slice.average().toFloat()
                        }

                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .padding(vertical = 6.dp)
                        ) {
                            val rawPath = Path()
                            val avgPath = Path()
                            val denom = (points.size - 1).coerceAtLeast(1).toFloat()

                            weightEntries.forEachIndexed { idx, (dayIdx, w) ->
                                val x = (dayIdx / denom) * size.width
                                val y = size.height * (1f - ((w - minW) / span))
                                if (idx == 0) rawPath.moveTo(x, y) else rawPath.lineTo(x, y)
                                drawCircle(color = FiberPurple.copy(alpha = 0.55f), radius = 3.5.dp.toPx(), center = Offset(x, y))
                            }

                            smoothed.forEachIndexed { idx, (dayIdx, w) ->
                                val x = (dayIdx / denom) * size.width
                                val y = size.height * (1f - ((w - minW) / span))
                                if (idx == 0) avgPath.moveTo(x, y) else avgPath.lineTo(x, y)
                            }

                            drawPath(
                                path = rawPath,
                                color = FiberPurple.copy(alpha = 0.35f),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                            drawPath(
                                path = avgPath,
                                color = AccentCyan,
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LegendDot(FiberPurple.copy(alpha = 0.7f), "Daily weigh-in")
                            LegendDot(AccentCyan, "Moving average")
                        }
                    } else {
                        Text(
                            "Log at least 2 weigh-ins across different days to plot your weight curve & moving average.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendSummaryCard(
    title: String,
    value: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = CardDark,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = accent)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontSize = 10.sp)
    }
}
