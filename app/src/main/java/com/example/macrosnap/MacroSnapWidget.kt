package com.example.macrosnap

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.LocalDate

class MacroSnapWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MacroSnapWidget()
}

class MacroSnapWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode: SizeMode = SizeMode.Responsive(RESPONSIVE_BUCKETS)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = AppDatabase.getInstance(context)
        val dao = db.mealLogDao()
        val today = LocalDate.now().toEpochDay()
        val prefs = context.getSharedPreferences("macrosnap_prefs", Context.MODE_PRIVATE)

        val baseTdee = prefs.getInt("base_tdee", 2200)
        val targetProtein = prefs.getInt("target_protein", 160)
        val targetCarbs = prefs.getInt("target_carbs", 220)
        val targetFat = prefs.getInt("target_fat", 70)

        val initialLogs = dao.getLogsForDayOnce(today)
        val initialMetrics = dao.getMetricsForDayOnce(today)

        provideContent {
            val rawState = currentState<Preferences>()
            // Ignore pushed state from a previous day so the widget never shows yesterday's totals.
            val state = rawState.takeIf { it[KEY_EPOCH_DAY] == today }
            val calsEaten = state?.get(KEY_CALS_EATEN) ?: initialLogs.sumOf { it.calories }
            val calsTarget = state?.get(KEY_CALS_TARGET) ?: (baseTdee + (initialMetrics?.activeCalories ?: 0))
            val protein = state?.get(KEY_PROTEIN) ?: initialLogs.sumOf { it.proteinGrams }.toInt()
            val carbs = state?.get(KEY_CARBS) ?: initialLogs.sumOf { it.carbsGrams }.toInt()
            val fat = state?.get(KEY_FAT) ?: initialLogs.sumOf { it.fatGrams }.toInt()
            val remaining = calsTarget - calsEaten

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val scanIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_SCANNER
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(Color(0xFF0B0F17))
                        .cornerRadius(20.dp)
                        .padding(14.dp)
                        .clickable(actionStartActivity(openAppIntent)),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                text = "MacroSnap Today",
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF94A3B8)),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                            Text(
                                text = if (remaining >= 0) "$remaining kcal left" else "${-remaining} kcal over",
                                style = TextStyle(
                                    color = ColorProvider(if (remaining >= 0) Color(0xFF10B981) else Color(0xFFF87171)),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        Box(
                            modifier = GlanceModifier
                                .background(Color(0xFF10B981))
                                .cornerRadius(12.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clickable(actionStartActivity(scanIntent)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "📷 Scan",
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF042F2E)),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.height(10.dp))

                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
                    ) {
                        WidgetMacroPill(
                            label = "Protein",
                            value = "${protein}/${targetProtein}g",
                            color = Color(0xFF38BDF8),
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        WidgetMacroPill(
                            label = "Carbs",
                            value = "${carbs}/${targetCarbs}g",
                            color = Color(0xFF34D399),
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        WidgetMacroPill(
                            label = "Fat",
                            value = "${fat}/${targetFat}g",
                            color = Color(0xFFFBBF24),
                            modifier = GlanceModifier.defaultWeight()
                        )
                    }
                }
            }
        }
    }

    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable
    ) {
        val fallback = RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "MacroSnap: Tap to open")
        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, fallback)
    }

    companion object {
        const val ACTION_OPEN_SCANNER = "com.example.macrosnap.ACTION_OPEN_SCANNER"
        val KEY_REVISION = intPreferencesKey("widget_revision")
        val KEY_EPOCH_DAY = longPreferencesKey("widget_epoch_day")
        val KEY_CALS_EATEN = intPreferencesKey("cals_eaten")
        val KEY_CALS_TARGET = intPreferencesKey("cals_target")
        val KEY_PROTEIN = intPreferencesKey("protein_g")
        val KEY_CARBS = intPreferencesKey("carbs_g")
        val KEY_FAT = intPreferencesKey("fat_g")

        private val RESPONSIVE_BUCKETS = setOf(
            DpSize(180.dp, 110.dp),
            DpSize(260.dp, 110.dp),
            DpSize(260.dp, 180.dp)
        )

        suspend fun pushWidgetUpdate(context: Context) {
            runCatching {
                val db = AppDatabase.getInstance(context)
                val dao = db.mealLogDao()
                val today = LocalDate.now().toEpochDay()
                val logs = dao.getLogsForDayOnce(today)
                val metrics = dao.getMetricsForDayOnce(today)
                val prefs = context.getSharedPreferences("macrosnap_prefs", Context.MODE_PRIVATE)
                val baseTdee = prefs.getInt("base_tdee", 2200)

                val calsEaten = logs.sumOf { it.calories }
                val calsTarget = baseTdee + (metrics?.activeCalories ?: 0)
                val protein = logs.sumOf { it.proteinGrams }.toInt()
                val carbs = logs.sumOf { it.carbsGrams }.toInt()
                val fat = logs.sumOf { it.fatGrams }.toInt()

                val manager = GlanceAppWidgetManager(context)
                val widget = MacroSnapWidget()
                for (glanceId in manager.getGlanceIds(MacroSnapWidget::class.java)) {
                    updateAppWidgetState(context, glanceId) { state ->
                        state[KEY_REVISION] = (state[KEY_REVISION] ?: 0) + 1
                        state[KEY_EPOCH_DAY] = today
                        state[KEY_CALS_EATEN] = calsEaten
                        state[KEY_CALS_TARGET] = calsTarget
                        state[KEY_PROTEIN] = protein
                        state[KEY_CARBS] = carbs
                        state[KEY_FAT] = fat
                    }
                    widget.update(context, glanceId)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetMacroPill(
    label: String,
    value: String,
    color: Color,
    modifier: GlanceModifier = GlanceModifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF151D2A))
            .cornerRadius(10.dp)
            .padding(vertical = 6.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Text(
            text = value,
            style = TextStyle(
                color = ColorProvider(color),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(Color(0xFF94A3B8)),
                fontSize = 10.sp
            )
        )
    }
}
