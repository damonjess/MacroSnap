package com.example.macrosnap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun ReviewNutritionDialog(
    initial: NutritionResult,
    isBarcode: Boolean,
    initialCategory: MealCategory = MealCategory.defaultForTimestamp(),
    isEditing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (result: NutritionResult, category: MealCategory) -> Unit
) {
    var name by remember { mutableStateOf(initial.mealName) }
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var portionGrams by remember { mutableStateOf("100") }
    var multiplier by remember { mutableFloatStateOf(1f) }
    var subItems by remember { mutableStateOf(initial.subItems) }

    var cals by remember { mutableStateOf(initial.calories.toString()) }
    var protein by remember { mutableStateOf("%.1f".format(initial.proteinGrams)) }
    var carbs by remember { mutableStateOf("%.1f".format(initial.carbsGrams)) }
    var fat by remember { mutableStateOf("%.1f".format(initial.fatGrams)) }
    var fiber by remember { mutableStateOf("%.1f".format(initial.fiberGrams)) }

    fun applyTotalsFromSubItemsAndMultiplier(items: List<DetectedFoodItem>, mult: Float) {
        if (items.isNotEmpty()) {
            val active = items.filter { it.included }
            cals = (active.sumOf { it.calories } * mult).roundToInt().toString()
            protein = "%.1f".format(active.sumOf { it.proteinGrams } * mult)
            carbs = "%.1f".format(active.sumOf { it.carbsGrams } * mult)
            fat = "%.1f".format(active.sumOf { it.fatGrams } * mult)
            fiber = "%.1f".format(active.sumOf { it.fiberGrams } * mult)
        } else {
            cals = (initial.calories * mult).roundToInt().toString()
            protein = "%.1f".format(initial.proteinGrams * mult)
            carbs = "%.1f".format(initial.carbsGrams * mult)
            fat = "%.1f".format(initial.fatGrams * mult)
            fiber = "%.1f".format(initial.fiberGrams * mult)
        }
    }

    AlertDialog(
        containerColor = CardDark,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) "Edit Meal Log" else "Confirm Nutrition",
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Food Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Meal Category Selector
                Text("Meal Category", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MealCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.displayName, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CardElevated,
                                labelColor = TextSecondary,
                                selectedContainerColor = AccentEmerald,
                                selectedLabelColor = Color(0xFF042F2E)
                            )
                        )
                    }
                }

                // Portion scaling: grams for barcode, multiplier chips for AI/manual
                if (isBarcode && !isEditing) {
                    OutlinedTextField(
                        value = portionGrams,
                        onValueChange = { newGrams ->
                            portionGrams = newGrams
                            val g = newGrams.toDoubleOrNull()
                            if (g != null) {
                                val ratio = g / 100.0
                                cals = (initial.calories * ratio).toInt().toString()
                                protein = "%.1f".format(initial.proteinGrams * ratio)
                                carbs = "%.1f".format(initial.carbsGrams * ratio)
                                fat = "%.1f".format(initial.fatGrams * ratio)
                                fiber = "%.1f".format(initial.fiberGrams * ratio)
                            }
                        },
                        label = { Text("Portion Weight (g) — scales from 100g") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Portion Multiplier", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.5f to "0.5x", 1f to "1x", 1.5f to "1.5x", 2f to "2x").forEach { (m, label) ->
                            FilterChip(
                                selected = multiplier == m,
                                onClick = {
                                    multiplier = m
                                    applyTotalsFromSubItemsAndMultiplier(subItems, m)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = CardElevated,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = Color(0xFF042F2E)
                                )
                            )
                        }
                    }
                }

                // Multi-item breakdown toggles
                if (subItems.isNotEmpty()) {
                    Surface(
                        color = CardElevated,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Detected Items (${subItems.count { it.included }}/${subItems.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = AccentEmerald
                            )
                            subItems.forEachIndexed { idx, item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val updated = subItems.toMutableList()
                                            updated[idx] = item.copy(included = !item.included)
                                            subItems = updated
                                            applyTotalsFromSubItemsAndMultiplier(updated, multiplier)
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = item.included,
                                        onCheckedChange = { checked ->
                                            val updated = subItems.toMutableList()
                                            updated[idx] = item.copy(included = checked)
                                            subItems = updated
                                            applyTotalsFromSubItemsAndMultiplier(updated, multiplier)
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = AccentEmerald)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${item.name} (${item.grams}g)",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (item.included) TextPrimary else TextSecondary
                                        )
                                        Text(
                                            "${item.calories} kcal • P ${item.proteinGrams.toInt()}g C ${item.carbsGrams.toInt()}g F ${item.fatGrams.toInt()}g",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cals, onValueChange = { cals = it },
                        label = { Text("Calories") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fiber, onValueChange = { fiber = it },
                        label = { Text("Fiber (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = protein, onValueChange = { protein = it },
                        label = { Text("Protein (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = carbs, onValueChange = { carbs = it },
                        label = { Text("Carbs (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fat, onValueChange = { fat = it },
                        label = { Text("Fat (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (initial.nutriscore != "?" || initial.novaGroup > 0 || initial.ingredients.isNotBlank()) {
                    Surface(
                        color = CardElevated,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Quality & Ingredients", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                val scoreColor = when (initial.nutriscore) {
                                    "A", "B" -> Color(0xFF10B981)
                                    "C" -> Color(0xFFF59E0B)
                                    "D", "E" -> Color(0xFFEF4444)
                                    else -> TextSecondary
                                }
                                Surface(color = scoreColor.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        text = "Nutri-Score ${initial.nutriscore}",
                                        color = scoreColor,
                                        fontWeight = FontWeight.ExtraBold,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                                if (initial.novaGroup > 0) {
                                    Surface(color = Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp)) {
                                        Text(
                                            text = "NOVA ${initial.novaGroup}",
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = if (initial.additivesCount > 0) "⚠️ ${initial.additivesCount} Additives detected" else "✅ Additive-free",
                                color = if (initial.additivesCount > 0) Color(0xFFF87171) else AccentEmerald,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (initial.ingredients.isNotBlank()) {
                                Text(
                                    text = initial.ingredients,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (initial.confidenceNote.isNotBlank()) {
                    Text(initial.confidenceNote, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (isBarcode && !isEditing) "$name (${portionGrams}g)" else name
                    onConfirm(
                        initial.copy(
                            mealName = finalName.ifBlank { "Meal" },
                            calories = cals.toIntOrNull() ?: initial.calories,
                            proteinGrams = protein.toDoubleOrNull() ?: initial.proteinGrams,
                            carbsGrams = carbs.toDoubleOrNull() ?: initial.carbsGrams,
                            fatGrams = fat.toDoubleOrNull() ?: initial.fatGrams,
                            fiberGrams = fiber.toDoubleOrNull() ?: initial.fiberGrams,
                            subItems = subItems
                        ),
                        selectedCategory
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald, contentColor = Color(0xFF042F2E))
            ) { Text(if (isEditing) "Update Log" else "Save Log", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
fun QuickAddMealDialog(
    initialManualMode: Boolean = false,
    onDismiss: () -> Unit,
    onAnalyzeText: (String) -> Unit,
    onManualSubmit: (NutritionResult, MealCategory) -> Unit
) {
    var manualMode by remember { mutableStateOf(initialManualMode) }
    var description by remember { mutableStateOf("") }

    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MealCategory.defaultForTimestamp()) }
    var cals by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var fiber by remember { mutableStateOf("") }

    AlertDialog(
        containerColor = CardDark,
        onDismissRequest = onDismiss,
        title = { Text("Quick Add Meal", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !manualMode,
                        onClick = { manualMode = false },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("AI Describe") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CardElevated,
                            selectedContainerColor = AccentEmerald,
                            selectedLabelColor = Color(0xFF042F2E)
                        )
                    )
                    FilterChip(
                        selected = manualMode,
                        onClick = { manualMode = true },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Manual Macros") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CardElevated,
                            selectedContainerColor = AccentCyan,
                            selectedLabelColor = Color(0xFF042F2E)
                        )
                    )
                }

                if (!manualMode) {
                    Text(
                        "Describe what you ate in plain English and AI will break down portions & macros.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Meal Description") },
                        placeholder = { Text("e.g. 2 scrambled eggs, 1 slice sourdough with butter, black coffee") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Food / Meal Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MealCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(cat.displayName, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = CardElevated,
                                    selectedContainerColor = AccentEmerald,
                                    selectedLabelColor = Color(0xFF042F2E)
                                )
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cals, onValueChange = { cals = it },
                            label = { Text("Calories") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = fiber, onValueChange = { fiber = it },
                            label = { Text("Fiber (g)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = protein, onValueChange = { protein = it },
                            label = { Text("Protein") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = carbs, onValueChange = { carbs = it },
                            label = { Text("Carbs") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = fat, onValueChange = { fat = it },
                            label = { Text("Fat") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!manualMode) {
                        if (description.isNotBlank()) onAnalyzeText(description.trim())
                    } else {
                        onManualSubmit(
                            NutritionResult(
                                mealName = name.ifBlank { "Manual Meal" },
                                calories = cals.toIntOrNull() ?: 0,
                                proteinGrams = protein.toDoubleOrNull() ?: 0.0,
                                carbsGrams = carbs.toDoubleOrNull() ?: 0.0,
                                fatGrams = fat.toDoubleOrNull() ?: 0.0,
                                fiberGrams = fiber.toDoubleOrNull() ?: 0.0
                            ),
                            category
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald, contentColor = Color(0xFF042F2E))
            ) {
                Text(if (!manualMode) "Estimate with AI" else "Add Meal", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
fun RecentAndFavoritesDialog(
    recentLogs: List<MealLogEntity>,
    onDismiss: () -> Unit,
    onRelog: (MealLogEntity) -> Unit,
    onToggleFavorite: (MealLogEntity) -> Unit
) {
    val distinctMeals = remember(recentLogs) {
        recentLogs.distinctBy { it.name.trim().lowercase() }
    }

    AlertDialog(
        containerColor = CardDark,
        onDismissRequest = onDismiss,
        title = { Text("Recent & Favorites", fontWeight = FontWeight.ExtraBold) },
        text = {
            if (distinctMeals.isEmpty()) {
                Text(
                    "Log a few meals first, then star your favorites here for 1-tap re-logging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(distinctMeals, key = { it.id }) { item ->
                        Surface(
                            color = CardElevated,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = { onToggleFavorite(item) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = "Favorite",
                                        tint = if (item.isFavorite) FatAmber else TextSecondary
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${item.calories} kcal • P ${item.proteinGrams.toInt()}g C ${item.carbsGrams.toInt()}g F ${item.fatGrams.toInt()}g",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                FilledTonalIconButton(
                                    onClick = { onRelog(item) },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = AccentEmerald.copy(alpha = 0.2f),
                                        contentColor = AccentEmerald
                                    )
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Log Again")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = AccentEmerald, fontWeight = FontWeight.Bold) }
        }
    )
}
