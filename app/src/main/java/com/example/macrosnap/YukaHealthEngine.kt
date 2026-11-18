package com.example.macrosnap

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable
data class AdditiveDetail(
    val code: String,              // e.g. "E442"
    val name: String,              // e.g. "Ammonium phosphatides"
    val riskLevel: String,         // "HIGH_RISK", "LIMITED_RISK", "RISK_FREE"
    val functionTitle: String,     // e.g. "Texturizing agent"
    val functionDescription: String,
    val associatedRisks: List<String> = emptyList(),
    val scientificExplanation: String = ""
)

@Serializable
data class HealthierAlternative(
    val name: String,
    val brandOrStore: String = "",
    val estimatedScore: Int = 75,
    val whyBetter: String = ""
)

enum class NutrientImpactLevel(val color: Color) {
    GOOD_GREEN(Color(0xFF10B981)),
    LOW_YELLOW(Color(0xFFFACC15)),
    MODERATE_ORANGE(Color(0xFFF97316)),
    HIGH_RED(Color(0xFFEF4444))
}

data class YukaNutrientRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val valueText: String,
    val impact: NutrientImpactLevel,
    val isPositive: Boolean
)

object YukaHealthEngine {

    fun functionDefinitionFor(functionTitle: String): String = when (functionTitle.lowercase()) {
        "texturizing agent", "emulsifier", "stabilizer", "thickener" ->
            "Provides consistency, maintains or modifies the texture of the product"
        "preservative" ->
            "Slows down microbial growth and extends product shelf life"
        "sweetener" ->
            "Imparts a sweet taste without adding sugar calories"
        "acidity regulator", "acid" ->
            "Controls or alters the acidity and tartness of the food"
        "colour", "food colouring" ->
            "Adds or restores visual colour to the food product"
        "flavour enhancer" ->
            "Amplifies the existing savoury or sweet taste of the food"
        "raising agent" ->
            "Releases gas to increase volume and lighten dough or batter texture"
        "antioxidant" ->
            "Protects fats and vitamins from oxidation and rancidity"
        else ->
            "Used in food processing to modify texture, stability, or taste"
    }

    private val knownAdditives: Map<String, AdditiveDetail> = listOf(
        AdditiveDetail(
            code = "E442",
            name = "Ammonium phosphatides",
            riskLevel = "HIGH_RISK",
            functionTitle = "Texturizing agent",
            functionDescription = "Provides consistency, maintains or modifies the texture of the product",
            associatedRisks = listOf(
                "Cardiovascular diseases",
                "Adverse effects on the kidneys",
                "Disorders of bone mineralization"
            ),
            scientificExplanation = "This additive contains phosphorus, a mineral whose average intake exceeds recommendations and whose excess could impair bone mineralization, have a harmful effect on the kidneys, and increase the risk of cardiovascular diseases. This additive could also contain certain contaminants, such as heavy metals and erucic acid."
        ),
        AdditiveDetail(
            code = "E476",
            name = "Polyglycerol polyricinoleate",
            riskLevel = "LIMITED_RISK",
            functionTitle = "Texturizing agent",
            functionDescription = "Provides consistency, maintains or modifies the texture of the product",
            associatedRisks = listOf("Gut barrier irritation at high intake"),
            scientificExplanation = "Synthetic emulsifier made from castor oil and glycerol commonly used in chocolate coatings to reduce viscosity. Safe in small amounts, though high intake of synthetic emulsifiers may affect gut mucus layers."
        ),
        AdditiveDetail(
            code = "E500II",
            name = "Sodium bicarbonate",
            riskLevel = "LIMITED_RISK",
            functionTitle = "Texturizing agent",
            functionDescription = "Provides consistency, maintains or modifies the texture of the product",
            associatedRisks = listOf("Contributes minor dietary sodium"),
            scientificExplanation = "Commonly known as baking soda. Used as a raising and pH-adjusting agent in baked goods like pretzels and biscuits. Generally safe, with only minor sodium contribution."
        ),
        AdditiveDetail(
            code = "E500",
            name = "Sodium carbonates",
            riskLevel = "LIMITED_RISK",
            functionTitle = "Texturizing agent",
            functionDescription = "Provides consistency, maintains or modifies the texture of the product",
            associatedRisks = listOf("Contributes minor dietary sodium"),
            scientificExplanation = "Used as an acidity regulator and raising agent in baked pretzels and crackers."
        ),
        AdditiveDetail(
            code = "E322I",
            name = "Soy lecithin",
            riskLevel = "RISK_FREE",
            functionTitle = "Emulsifier",
            functionDescription = "Provides consistency, maintains or modifies the texture of the product",
            associatedRisks = emptyList(),
            scientificExplanation = "Natural phospholipid extracted from soybeans that keeps cocoa butter and fats smoothly blended. Poses no health risk."
        ),
        AdditiveDetail(
            code = "E322",
            name = "Lecithins",
            riskLevel = "RISK_FREE",
            functionTitle = "Emulsifier",
            functionDescription = "Provides consistency, maintains or modifies the texture of the product",
            associatedRisks = emptyList(),
            scientificExplanation = "Naturally occurring lipid extracted from sunflower seeds or soybeans used to bind water and fats together. Considered risk-free."
        ),
        AdditiveDetail(
            code = "E330",
            name = "Citric acid",
            riskLevel = "RISK_FREE",
            functionTitle = "Acidity regulator",
            functionDescription = "Controls or alters the acidity and tartness of the food",
            associatedRisks = emptyList(),
            scientificExplanation = "Organic acid naturally present in citrus fruits. Used to prevent oxidation and balance acidity. Risk-free."
        ),
        AdditiveDetail(
            code = "E300",
            name = "Ascorbic acid (Vitamin C)",
            riskLevel = "RISK_FREE",
            functionTitle = "Antioxidant",
            functionDescription = "Protects fats and vitamins from oxidation and rancidity",
            associatedRisks = emptyList(),
            scientificExplanation = "Vitamin C used as a natural antioxidant preservative. Risk-free."
        ),
        AdditiveDetail(
            code = "E471",
            name = "Mono- and diglycerides of fatty acids",
            riskLevel = "LIMITED_RISK",
            functionTitle = "Emulsifier",
            functionDescription = "Provides consistency, maintains or modifies the texture of the product",
            associatedRisks = listOf("Gut microbiome disruption", "Metabolic inflammation"),
            scientificExplanation = "Widely used synthetic emulsifier in ultra-processed breads and snacks. Observational studies link high intake of E471 to altered gut microbiota and higher cardiovascular risk."
        ),
        AdditiveDetail(
            code = "E407",
            name = "Carrageenan",
            riskLevel = "HIGH_RISK",
            functionTitle = "Texturizing agent",
            functionDescription = "Provides consistency, maintains or modifies the texture of the product",
            associatedRisks = listOf("Intestinal inflammation", "Gut permeability"),
            scientificExplanation = "Seaweed-derived thickener that has been linked in experimental studies to gastrointestinal inflammation, impaired glucose tolerance, and gut barrier irritation."
        ),
        AdditiveDetail(
            code = "E250",
            name = "Sodium nitrite",
            riskLevel = "HIGH_RISK",
            functionTitle = "Preservative",
            functionDescription = "Slows down microbial growth and extends product shelf life",
            associatedRisks = listOf("Colorectal cancer risk", "Nitrosamine formation"),
            scientificExplanation = "Curing salt used in processed meats. Can react with amino acids to form carcinogenic N-nitroso compounds (nitrosamines) in the digestive tract."
        ),
        AdditiveDetail(
            code = "E211",
            name = "Sodium benzoate",
            riskLevel = "HIGH_RISK",
            functionTitle = "Preservative",
            functionDescription = "Slows down microbial growth and extends product shelf life",
            associatedRisks = listOf("Hyperactivity", "Benzene formation with Vitamin C"),
            scientificExplanation = "Synthetic preservative used in soft drinks and sauces. Can form trace benzene when combined with ascorbic acid and is linked to hypersensitivity reactions."
        ),
        AdditiveDetail(
            code = "E338",
            name = "Phosphoric acid",
            riskLevel = "HIGH_RISK",
            functionTitle = "Acidity regulator",
            functionDescription = "Controls or alters the acidity and tartness of the food",
            associatedRisks = listOf(
                "Cardiovascular diseases",
                "Adverse effects on the kidneys",
                "Disorders of bone mineralization"
            ),
            scientificExplanation = "Inorganic phosphate acidulant heavily used in colas. Excess dietary phosphate impairs calcium absorption, stresses kidney filtration, and promotes vascular calcification."
        ),
        AdditiveDetail(
            code = "E450",
            name = "Diphosphates",
            riskLevel = "HIGH_RISK",
            functionTitle = "Texturizing agent",
            functionDescription = "Provides consistency, maintains or modifies the texture of the product",
            associatedRisks = listOf(
                "Cardiovascular diseases",
                "Adverse effects on the kidneys",
                "Disorders of bone mineralization"
            ),
            scientificExplanation = "Phosphate salts used as raising agents and stabilizers. High inorganic phosphorus intake is associated with kidney strain, bone demineralization, and arterial stiffness."
        ),
        AdditiveDetail(
            code = "E950",
            name = "Acesulfame K",
            riskLevel = "HIGH_RISK",
            functionTitle = "Sweetener",
            functionDescription = "Imparts a sweet taste without adding sugar calories",
            associatedRisks = listOf("Metabolic dysregulation", "Gut microbiome alteration"),
            scientificExplanation = "Artificial high-intensity sweetener frequently paired with sucralose or aspartame. Linked in cohort studies to vascular and gut microbiome changes."
        ),
        AdditiveDetail(
            code = "E951",
            name = "Aspartame",
            riskLevel = "HIGH_RISK",
            functionTitle = "Sweetener",
            functionDescription = "Imparts a sweet taste without adding sugar calories",
            associatedRisks = listOf("Possible carcinogenic hazard (IARC Group 2B)", "Metabolic effects"),
            scientificExplanation = "Artificial sweetener classified by the WHO's IARC as possibly carcinogenic to humans (Group 2B), with ongoing debate around long-term metabolic impacts."
        ),
        AdditiveDetail(
            code = "E955",
            name = "Sucralose",
            riskLevel = "HIGH_RISK",
            functionTitle = "Sweetener",
            functionDescription = "Imparts a sweet taste without adding sugar calories",
            associatedRisks = listOf("Gut microbiome disruption", "Glucose insulin sensitivity"),
            scientificExplanation = "Chlorinated artificial sweetener. Studies suggest regular consumption can alter gut bacteria and blunted insulin sensitivity."
        )
    ).associateBy { it.code.uppercase() }

    fun resolveAdditivesFromTags(tags: List<String>): List<AdditiveDetail> {
        return tags.mapNotNull { rawTag ->
            val cleaned = rawTag.substringAfterLast(":").trim().uppercase()
            if (cleaned.isBlank()) return@mapNotNull null
            knownAdditives[cleaned]
                ?: knownAdditives[cleaned.trimEnd { it.isLowerCase() || it in listOf('I', 'V') }]?.copy(code = cleaned)
                ?: AdditiveDetail(
                    code = cleaned,
                    name = "Food Additive ($cleaned)",
                    riskLevel = "LIMITED_RISK",
                    functionTitle = "Food processing additive",
                    functionDescription = "Provides consistency, maintains or modifies the texture or stability of the product",
                    associatedRisks = listOf("Processed food additive — tap 'Deep-Dive with AI' below for full toxicology"),
                    scientificExplanation = "Identified from product barcode metadata ($cleaned). Tap the AI Deep-Dive button to generate full chemical name and organ risk cards."
                )
        }
    }

    fun calculateYukaScore(
        calories100g: Int,
        saturates100g: Double,
        sugars100g: Double,
        salt100g: Double,
        protein100g: Double,
        fiber100g: Double,
        nutriscore: String,
        additives: List<AdditiveDetail>
    ): Pair<Int, String> {
        var score = when (nutriscore.uppercase()) {
            "A" -> 90
            "B" -> 74
            "C" -> 54
            "D" -> 34
            "E" -> 16
            else -> {
                var base = 85
                if (calories100g > 350) base -= 15
                if (calories100g > 500) base -= 15
                if (saturates100g > 5.0) base -= 15
                if (saturates100g > 10.0) base -= 15
                if (sugars100g > 15.0) base -= 15
                if (sugars100g > 30.0) base -= 20
                if (salt100g > 1.2) base -= 10
                if (salt100g > 2.0) base -= 15
                if (protein100g >= 8.0) base += 10
                if (fiber100g >= 3.0) base += 10
                base.coerceIn(5, 95)
            }
        }

        val highRiskCount = additives.count { it.riskLevel == "HIGH_RISK" }
        val limitedRiskCount = additives.count { it.riskLevel == "LIMITED_RISK" }

        score -= (highRiskCount * 25) + (limitedRiskCount * 8)

        if (highRiskCount > 0 && score > 49) {
            score = 49
        }

        if (highRiskCount > 0 && saturates100g >= 10.0 && sugars100g >= 30.0) {
            score = 0
        }

        val finalScore = score.coerceIn(0, 100)
        val label = when {
            finalScore >= 75 -> "Excellent"
            finalScore >= 50 -> "Good"
            finalScore >= 25 -> "Poor"
            else -> "Bad"
        }
        return finalScore to label
    }

    fun buildNegativesAndPositives(
        calories100g: Int,
        saturates100g: Double?,
        sugars100g: Double?,
        salt100g: Double?,
        protein100g: Double,
        fiber100g: Double
    ): Pair<List<YukaNutrientRow>, List<YukaNutrientRow>> {
        val negatives = mutableListOf<YukaNutrientRow>()
        val positives = mutableListOf<YukaNutrientRow>()

        saturates100g?.let { sat ->
            when {
                sat > 10.0 -> negatives += YukaNutrientRow("saturates", "Saturates", "Too fatty", "${"%.1f".format(sat)}g", NutrientImpactLevel.HIGH_RED, false)
                sat > 5.0 -> negatives += YukaNutrientRow("saturates", "Saturates", "A bit too fatty", "${"%.1f".format(sat)}g", NutrientImpactLevel.MODERATE_ORANGE, false)
                sat <= 1.5 -> positives += YukaNutrientRow("saturates", "Saturates", "Low saturated fat", "${"%.1f".format(sat)}g", NutrientImpactLevel.GOOD_GREEN, true)
            }
        }

        sugars100g?.let { sug ->
            when {
                sug > 22.5 -> negatives += YukaNutrientRow("sugar", "Sugar", "Too sweet", "${"%.1f".format(sug)}g", NutrientImpactLevel.HIGH_RED, false)
                sug > 12.5 -> negatives += YukaNutrientRow("sugar", "Sugar", "A bit too sweet", "${"%.1f".format(sug)}g", NutrientImpactLevel.MODERATE_ORANGE, false)
                sug <= 5.0 -> positives += YukaNutrientRow("sugar", "Sugar", "Low sugar", "${"%.1f".format(sug)}g", NutrientImpactLevel.GOOD_GREEN, true)
            }
        }

        when {
            calories100g >= 550 -> negatives += YukaNutrientRow("energy", "Energy", "Too caloric", "${calories100g}kcal", NutrientImpactLevel.HIGH_RED, false)
            calories100g >= 360 -> negatives += YukaNutrientRow("energy", "Energy", "A bit too caloric", "${calories100g}kcal", NutrientImpactLevel.MODERATE_ORANGE, false)
            calories100g in 1..160 -> positives += YukaNutrientRow("energy", "Energy", "Low calorie density", "${calories100g}kcal", NutrientImpactLevel.GOOD_GREEN, true)
        }

        salt100g?.let { s ->
            when {
                s > 2.2 -> negatives += YukaNutrientRow("salt", "Salt", "Too much sodium", "${"%.2f".format(s)}g", NutrientImpactLevel.HIGH_RED, false)
                s > 1.1 -> negatives += YukaNutrientRow("salt", "Salt", "A bit too much sodium", "${"%.2f".format(s)}g", NutrientImpactLevel.MODERATE_ORANGE, false)
                s <= 0.3 -> positives += YukaNutrientRow("salt", "Salt", "Low sodium", "${"%.2f".format(s)}g", NutrientImpactLevel.GOOD_GREEN, true)
            }
        }

        when {
            protein100g >= 8.0 -> positives += YukaNutrientRow("protein", "Protein", "Excellent quantity", "${"%.1f".format(protein100g)}g", NutrientImpactLevel.GOOD_GREEN, true)
            protein100g >= 4.0 -> positives += YukaNutrientRow("protein", "Protein", "Good quantity", "${"%.1f".format(protein100g)}g", NutrientImpactLevel.GOOD_GREEN, true)
        }

        when {
            fiber100g >= 6.0 -> positives += YukaNutrientRow("fibre", "Fibre", "Excellent fibre content", "${"%.1f".format(fiber100g)}g", NutrientImpactLevel.GOOD_GREEN, true)
            fiber100g >= 0.9 -> positives += YukaNutrientRow("fibre", "Fibre", "Some fibre", "${"%.1f".format(fiber100g)}g", NutrientImpactLevel.GOOD_GREEN, true)
        }

        return negatives to positives
    }
}

@Composable
fun YukaProductReportSection(
    productName: String,
    brandName: String,
    calories100g: Int,
    saturates100g: Double?,
    sugars100g: Double?,
    salt100g: Double?,
    protein100g: Double,
    fiber100g: Double,
    nutriscore: String,
    novaGroup: Int,
    rawIngredients: String,
    additives: List<AdditiveDetail>,
    aiScoreOverride: Int = -1,
    aiVerdict: String = "",
    ingredientFacts: List<IngredientFact> = emptyList(),
    alternatives: List<HealthierAlternative> = emptyList(),
    isLoadingAlternatives: Boolean = false,
    onRequestAlternatives: ((score: Int, negativesSummary: String) -> Unit)? = null,
    isAnalyzingAi: Boolean = false,
    onRunAiDeepDive: (() -> Unit)? = null
) {
    var additivesExpanded by remember { mutableStateOf(true) }
    var showAdditivesListModal by remember { mutableStateOf(false) }
    var selectedAdditiveForDetail by remember { mutableStateOf<AdditiveDetail?>(null) }
    var autoRequestedSwaps by remember { mutableStateOf(false) }

    val (calcScore, _) = remember(
        calories100g, saturates100g, sugars100g, salt100g, protein100g, fiber100g, nutriscore, additives
    ) {
        YukaHealthEngine.calculateYukaScore(
            calories100g = calories100g,
            saturates100g = saturates100g ?: 0.0,
            sugars100g = sugars100g ?: 0.0,
            salt100g = salt100g ?: 0.0,
            protein100g = protein100g,
            fiber100g = fiber100g,
            nutriscore = nutriscore,
            additives = additives
        )
    }

    val displayScore = if (aiScoreOverride in 0..100) aiScoreOverride else calcScore
    val displayLabel = when {
        displayScore >= 75 -> "Excellent"
        displayScore >= 50 -> "Good"
        displayScore >= 25 -> "Poor"
        else -> "Bad"
    }
    val scoreColor = when {
        displayScore >= 75 -> Color(0xFF10B981)
        displayScore >= 50 -> Color(0xFF34D399)
        displayScore >= 25 -> Color(0xFFF97316)
        else -> Color(0xFFEF4444)
    }

    val (negatives, positives) = remember(
        calories100g, saturates100g, sugars100g, salt100g, protein100g, fiber100g
    ) {
        YukaHealthEngine.buildNegativesAndPositives(
            calories100g = calories100g,
            saturates100g = saturates100g,
            sugars100g = sugars100g,
            salt100g = salt100g,
            protein100g = protein100g,
            fiber100g = fiber100g
        )
    }

    val negativesSummary = remember(negatives, additives) {
        buildList {
            if (additives.isNotEmpty()) {
                add("Additives: " + additives.joinToString(", ") { "${it.code} (${it.name})" })
            }
            negatives.forEach { add("${it.title}: ${it.valueText} (${it.subtitle})") }
        }.joinToString("; ")
    }

    // Automatically request 2-3 healthier UK supermarket alternatives whenever score < 50
    LaunchedEffect(displayScore, productName) {
        if (displayScore < 50 &&
            alternatives.isEmpty() &&
            !isLoadingAlternatives &&
            !autoRequestedSwaps &&
            productName.isNotBlank() &&
            onRequestAlternatives != null
        ) {
            autoRequestedSwaps = true
            onRequestAlternatives(displayScore, negativesSummary)
        }
    }

    val highRiskCount = additives.count { it.riskLevel == "HIGH_RISK" }
    val limitedRiskCount = additives.count { it.riskLevel == "LIMITED_RISK" }
    val riskFreeCount = additives.count { it.riskLevel == "RISK_FREE" }
    val hasNegativeAdditives = highRiskCount > 0 || limitedRiskCount > 0

    Surface(
        color = CardElevated,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // 1. Yuka Score Header Card (0/100 Bad / Poor / Good / Excellent)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = productName.ifBlank { "Product Health Analysis" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (brandName.isNotBlank()) {
                        Text(brandName, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (nutriscore != "?") {
                            Text(
                                text = "Nutri-Score $nutriscore",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        if (novaGroup > 0) {
                            Text(
                                text = "• NOVA $novaGroup",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Surface(
                    color = scoreColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(scoreColor))
                        Column {
                            Text(
                                text = "$displayScore/100",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                            Text(
                                text = displayLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = scoreColor
                            )
                        }
                    }
                }
            }

            if (aiVerdict.isNotBlank()) {
                Text(
                    text = aiVerdict,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // 2. NEGATIVES (For 100g) Section
            if (hasNegativeAdditives || negatives.isNotEmpty()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Negatives", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                    Text("For 100g", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }

                if (additives.isNotEmpty()) {
                    val additiveDotColor = when {
                        highRiskCount > 0 -> Color(0xFFEF4444)
                        limitedRiskCount > 0 -> Color(0xFFF97316)
                        else -> Color(0xFF10B981)
                    }
                    Surface(
                        color = CardDark.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { additivesExpanded = !additivesExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Default.Science, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                                    Column {
                                        Text("Additives", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = if (highRiskCount > 0) "Contains risky additives" else "Contains food additives",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${additives.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(additiveDotColor))
                                    Icon(
                                        imageVector = if (additivesExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = TextSecondary
                                    )
                                }
                            }

                            AnimatedVisibility(visible = additivesExpanded) {
                                Column(
                                    modifier = Modifier.padding(start = 30.dp, top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AdditiveRiskCountRow(count = highRiskCount, label = "High-risk", color = Color(0xFFEF4444))
                                    AdditiveRiskCountRow(count = limitedRiskCount, label = "Limited risk", color = Color(0xFFFACC15))
                                    AdditiveRiskCountRow(count = riskFreeCount, label = "Risk-free", color = Color(0xFF10B981))

                                    Spacer(Modifier.height(2.dp))
                                    OutlinedButton(
                                        onClick = { showAdditivesListModal = true },
                                        shape = RoundedCornerShape(20.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "More info on the additives",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = AccentEmerald,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                negatives.forEach { row ->
                    YukaNutrientMetricRow(row)
                }
            }

            // 3. POSITIVES (For 100g) Section
            if (positives.isNotEmpty() || (additives.isEmpty() && rawIngredients.isNotBlank())) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Positives", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                    Text("For 100g", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }

                if (additives.isEmpty() && rawIngredients.isNotBlank()) {
                    YukaNutrientMetricRow(
                        YukaNutrientRow(
                            key = "no_additives",
                            title = "Additives",
                            subtitle = "No risky additives detected",
                            valueText = "0",
                            impact = NutrientImpactLevel.GOOD_GREEN,
                            isPositive = true
                        )
                    )
                }

                positives.forEach { row ->
                    YukaNutrientMetricRow(row)
                }
            }

            // 4. RECOMMENDATIONS Section (Auto-populated whenever score < 50/100)
            if (displayScore < 50 || alternatives.isNotEmpty()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Recommendations",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Healthier UK supermarket swaps",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentEmerald,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                when {
                    isLoadingAlternatives -> {
                        Surface(
                            color = CardDark.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = AccentEmerald
                                )
                                Text(
                                    text = "Finding healthier UK supermarket alternatives...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    alternatives.isNotEmpty() -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            alternatives.forEach { alt ->
                                val altScore = alt.estimatedScore.coerceIn(50, 100)
                                val altColor = if (altScore >= 75) Color(0xFF10B981) else Color(0xFF34D399)

                                Surface(
                                    color = CardDark.copy(alpha = 0.75f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, altColor.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = alt.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                                if (alt.brandOrStore.isNotBlank()) {
                                                    Text(
                                                        text = alt.brandOrStore,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = AccentCyan,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                            Surface(
                                                color = altColor.copy(alpha = 0.16f),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                ) {
                                                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(altColor))
                                                    Text(
                                                        text = "$altScore/100",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = altColor
                                                    )
                                                }
                                            }
                                        }
                                        if (alt.whyBetter.isNotBlank()) {
                                            Text(
                                                text = alt.whyBetter,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tap to suggest cleaner UK supermarket swaps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            if (onRequestAlternatives != null) {
                                TextButton(onClick = { onRequestAlternatives(displayScore, negativesSummary) }) {
                                    Text("Find Swaps", color = AccentEmerald, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 5. Raw Ingredients & 1-Tap AI Deep-Dive Button
            if (rawIngredients.isNotBlank()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                Text(
                    text = "Ingredients: $rawIngredients",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onRunAiDeepDive != null && (rawIngredients.isNotBlank() || additives.isNotEmpty())) {
                FilledTonalButton(
                    onClick = onRunAiDeepDive,
                    enabled = !isAnalyzingAi,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FatAmber.copy(alpha = 0.16f),
                        contentColor = FatAmber
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isAnalyzingAi) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = FatAmber)
                        Spacer(Modifier.width(8.dp))
                        Text("AI analyzing every ingredient & additive...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (ingredientFacts.isEmpty()) "AI Deep-Dive: All Ingredients & Risks" else "Refresh AI Ingredient Facts",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 6. Individual Ingredient Facts
            if (ingredientFacts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Full Ingredient Breakdown (${ingredientFacts.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                    ingredientFacts.forEach { fact ->
                        val normalized = fact.rating.uppercase().trim()
                        val (color, displayRating) = when (normalized) {
                            "GOOD", "RISK_FREE", "RISK-FREE", "SAFE" -> Color(0xFF10B981) to "GOOD"
                            "BAD", "AVOID", "HIGH_RISK", "HIGH-RISK" -> Color(0xFFEF4444) to "AVOID"
                            else -> Color(0xFFF59E0B) to "CAUTION"
                        }
                        Surface(
                            color = CardDark.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(fact.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Text(displayRating, color = color, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelSmall)
                                }
                                if (fact.explanation.isNotBlank()) {
                                    Text(fact.explanation, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdditivesListModal) {
        YukaAdditivesListDialog(
            additives = additives,
            onSelectAdditive = { clicked ->
                selectedAdditiveForDetail = clicked
            },
            onDismiss = { showAdditivesListModal = false }
        )
    }

    selectedAdditiveForDetail?.let { additive ->
        YukaAdditiveDetailDialog(
            additive = additive,
            onDismiss = { selectedAdditiveForDetail = null }
        )
    }
}

@Composable
private fun AdditiveRiskCountRow(count: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF0B0F17)
            )
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
    }
}

@Composable
private fun YukaNutrientMetricRow(row: YukaNutrientRow) {
    val icon: ImageVector = when (row.key) {
        "saturates" -> Icons.Default.WaterDrop
        "sugar" -> Icons.Default.ViewInAr
        "energy" -> Icons.Default.LocalFireDepartment
        "salt" -> Icons.Default.Grain
        "protein" -> Icons.Default.FitnessCenter
        "fibre" -> Icons.Default.Spa
        else -> Icons.Default.CheckCircle
    }

    Surface(
        color = CardDark.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                Column {
                    Text(row.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text(row.subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.valueText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(row.impact.color))
            }
        }
    }
}

@Composable
fun YukaAdditivesListDialog(
    additives: List<AdditiveDetail>,
    onSelectAdditive: (AdditiveDetail) -> Unit,
    onDismiss: () -> Unit
) {
    val sorted = remember(additives) {
        additives.sortedBy {
            when (it.riskLevel) {
                "HIGH_RISK" -> 0
                "LIMITED_RISK" -> 1
                else -> 2
            }
        }
    }

    AlertDialog(
        containerColor = CardDark,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Text("List of additives", fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sorted, key = { it.code + it.name }) { item ->
                    val (riskLabel, riskColor) = when (item.riskLevel) {
                        "HIGH_RISK" -> "High-risk" to Color(0xFFEF4444)
                        "LIMITED_RISK" -> "Limited risk" to Color(0xFFFACC15)
                        else -> "Risk-free" to Color(0xFF10B981)
                    }

                    Surface(
                        onClick = { onSelectAdditive(item) },
                        color = CardElevated,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.code, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                                Surface(
                                    color = Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = item.functionTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(riskLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                                Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(riskColor))
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AccentEmerald, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun YukaAdditiveDetailDialog(
    additive: AdditiveDetail,
    onDismiss: () -> Unit
) {
    val (riskLabel, riskColor) = when (additive.riskLevel) {
        "HIGH_RISK" -> "High-risk" to Color(0xFFEF4444)
        "LIMITED_RISK" -> "Limited risk" to Color(0xFFFACC15)
        else -> "Risk-free" to Color(0xFF10B981)
    }

    AlertDialog(
        containerColor = CardDark,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Text("Additive", fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = additive.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(riskColor))
                            Text(riskLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = additive.code,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Surface(
                    color = CardElevated,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Hub, contentDescription = null, tint = AccentCyan)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(additive.functionTitle, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = additive.functionDescription.ifBlank {
                                    YukaHealthEngine.functionDefinitionFor(additive.functionTitle)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                if (additive.associatedRisks.isNotEmpty()) {
                    Text(
                        text = "Potential associated risks",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        additive.associatedRisks.forEach { riskText ->
                            val riskIcon = when {
                                riskText.contains("cardio", ignoreCase = true) || riskText.contains("heart", ignoreCase = true) ->
                                    Icons.Default.FavoriteBorder
                                riskText.contains("kidney", ignoreCase = true) || riskText.contains("renal", ignoreCase = true) ->
                                    Icons.Default.Healing
                                riskText.contains("bone", ignoreCase = true) || riskText.contains("mineral", ignoreCase = true) ->
                                    Icons.Default.AccessibilityNew
                                riskText.contains("gut", ignoreCase = true) || riskText.contains("intestin", ignoreCase = true) ->
                                    Icons.Default.Coronavirus
                                else -> Icons.Default.WarningAmber
                            }

                            Surface(
                                color = CardElevated,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, riskColor.copy(alpha = 0.3f)),
                                modifier = Modifier.width(136.dp).height(112.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp).fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = riskIcon,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = riskText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary,
                                        textAlign = TextAlign.Center,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if (additive.scientificExplanation.isNotBlank()) {
                    Text(
                        text = additive.scientificExplanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        lineHeight = 20.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Back to list", color = AccentEmerald, fontWeight = FontWeight.Bold)
            }
        }
    )
}
