package com.example.macrosnap

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class ScannerCaptureMode {
    MEAL_OR_BARCODE,
    NUTRITION_LABEL_OCR,
    INGREDIENTS_OCR
}

@Serializable
data class DetectedFoodItem(
    val name: String,
    val grams: Int = 100,
    val calories: Int,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val fiberGrams: Double = 0.0,
    val included: Boolean = true
)

@Serializable
data class IngredientFact(
    val name: String,
    val rating: String, // "GOOD", "CAUTION", or "BAD"
    val category: String = "",
    val explanation: String = ""
)

@Serializable
data class IngredientAnalysisResponse(
    val productName: String = "",
    val healthScore: Int = 50,
    val healthVerdict: String = "",
    val ingredientsRaw: String = "",
    val ingredientFacts: List<IngredientFact> = emptyList(),
    val additiveDetails: List<AdditiveDetail> = emptyList(),
    val modelUsed: String = "",
    val alternatives: List<HealthierAlternative> = emptyList()
)

@Serializable
data class AlternativesWrapper(
    val alternatives: List<HealthierAlternative> = emptyList()
)

@Serializable
data class NutritionResult(
    val mealName: String,
    val brandName: String = "",
    val calories: Int,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val fiberGrams: Double,
    val saturates100g: Double? = null,
    val sugars100g: Double? = null,
    val salt100g: Double? = null,
    val confidenceNote: String = "",
    val ingredients: String = "",
    val nutriscore: String = "?",
    val novaGroup: Int = 0,
    val additivesCount: Int = 0,
    val additiveDetails: List<AdditiveDetail> = emptyList(),
    val subItems: List<DetectedFoodItem> = emptyList(),
    val photoPath: String? = null,
    val modelUsed: String = "",
    val healthScore: Int = -1,
    val healthVerdict: String = "",
    val ingredientFacts: List<IngredientFact> = emptyList(),
    val alternatives: List<HealthierAlternative> = emptyList()
)

class BarcodeNotFoundException(val barcode: String) :
    Exception("Barcode $barcode not found in Open Food Facts. You can scan the Nutrition Facts or Ingredients label instead.")

class NutritionRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .readTimeout(38, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val geminiModels = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash",
        "gemini-3.6-flash"
    )

    @Volatile private var preferredModel: String? = null
    @Volatile private var preferredUntilMillis: Long = 0L

    private fun orderedModels(): List<String> {
        val cached = preferredModel?.takeIf { System.currentTimeMillis() < preferredUntilMillis }
            ?: return geminiModels
        return listOf(cached) + geminiModels.filterNot { it == cached }
    }

    companion object {
        fun saveMealThumbnail(context: Context, bitmap: Bitmap): String? = runCatching {
            val dir = File(context.filesDir, "meal_thumbs").apply { mkdirs() }
            val file = File(dir, "meal_${System.currentTimeMillis()}.jpg")
            val ratio = minOf(320f / bitmap.width, 320f / bitmap.height, 1f)
            val thumb = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            FileOutputStream(file).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 78, out)
            }
            file.absolutePath
        }.getOrNull()
    }

    suspend fun analyzeMealPhoto(
        bitmap: Bitmap,
        userHint: String = "",
        captureMode: ScannerCaptureMode = ScannerCaptureMode.MEAL_OR_BARCODE
    ): Result<NutritionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val scaled = scaleBitmap(bitmap, 800)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 74, stream)
            val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val basePrompt = when (captureMode) {
                ScannerCaptureMode.INGREDIENTS_OCR -> """
                    Read the Ingredients list (and nutrition table if visible) on this food package using OCR.
                    1. Transcribe the full ingredients list into `ingredients`.
                    2. Evaluate every ingredient in `ingredientFacts` with `name`, `rating` ("GOOD", "CAUTION", or "BAD"), `category`, and `explanation`.
                    3. For every food additive, emulsifier, preservative, sweetener, or E-number found, populate `additiveDetails` with:
                       - `code`: E-number (e.g. "E442", "E476", "E322i") or short additive code.
                       - `name`: Chemical/common name (e.g. "Ammonium phosphatides").
                       - `riskLevel`: Strictly "HIGH_RISK", "LIMITED_RISK", or "RISK_FREE".
                       - `functionTitle`: Role like "Texturizing agent", "Emulsifier", "Preservative", "Sweetener".
                       - `functionDescription`: 1-sentence role description.
                       - `associatedRisks`: List of 1-3 short organ/health risk labels (e.g. "Cardiovascular diseases", "Adverse effects on the kidneys", "Disorders of bone mineralization", "Gut microbiome disruption") or empty if risk-free.
                       - `scientificExplanation`: Detailed Yuka-style scientific explanation paragraph.
                    4. Populate `healthScore` (0-100), `healthVerdict`, and per-100g `saturates100g`, `sugars100g`, `salt100g` if visible.
                """.trimIndent()

                ScannerCaptureMode.NUTRITION_LABEL_OCR -> """
                    Read this Nutrition Facts table / food package label using OCR.
                    Extract Per 100g values (calories, proteinGrams, carbsGrams, fatGrams, fiberGrams, saturates100g, sugars100g, salt100g) and set subItems grams to 100.
                    If an ingredients list is also visible, transcribe it into `ingredients` and populate `ingredientFacts`, `additiveDetails`, `healthScore` (0-100), and `healthVerdict`.
                """.trimIndent()

                ScannerCaptureMode.MEAL_OR_BARCODE -> """
                    Analyze this food, meal, menu, or nutrition label image.
                    Estimate portion sizes in grams for each visible item, then calculate total calories, protein, carbs, fat, and fiber.
                    Break down each distinct visible food item in `subItems` with its estimated weight in grams and individual macros.
                    Mention any likely hidden calories (cooking oils, sauces, dressings) in `confidenceNote`.
                """.trimIndent()
            }

            val fullPrompt = if (userHint.isNotBlank()) {
                "$basePrompt\nUser-provided context/notes: \"$userHint\""
            } else {
                basePrompt
            }

            val partsArray = buildJsonArray {
                addJsonObject { put("text", fullPrompt) }
                addJsonObject {
                    putJsonObject("inline_data") {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    }
                }
            }

            executeGeminiNutritionRequest(
                partsArray = partsArray,
                includeIngredientsSchema = captureMode != ScannerCaptureMode.MEAL_OR_BARCODE
            )
        }
    }

    suspend fun analyzeMealText(description: String): Result<NutritionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = """
                Estimate the nutrition and macros for the following described meal or food items:
                "$description"
                Break down each individual component in `subItems` with estimated weight in grams and macros, and sum them into the top-level fields.
                Mention portion assumptions in confidenceNote.
            """.trimIndent()

            val partsArray = buildJsonArray {
                addJsonObject { put("text", prompt) }
            }

            executeGeminiNutritionRequest(partsArray, includeIngredientsSchema = false)
        }
    }

    suspend fun analyzeIngredientsText(
        productName: String,
        ingredientsText: String
    ): Result<IngredientAnalysisResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = """
                Perform a Yuka-style health & toxicology analysis on this food product's ingredients:
                Product: "$productName"
                Ingredients: "$ingredientsText"

                1. Populate `ingredientFacts` for every ingredient (`name`, `rating` = "GOOD"|"CAUTION"|"BAD", `category`, `explanation`).
                2. For every additive, emulsifier, raising agent, preservative, or E-number present, populate `additiveDetails` with:
                   - `code`: Standard UK/EU E-number (e.g. "E442", "E476", "E500ii", "E322i").
                   - `name`: Common/chemical name (e.g. "Ammonium phosphatides").
                   - `riskLevel`: Strictly "HIGH_RISK", "LIMITED_RISK", or "RISK_FREE".
                   - `functionTitle`: Short category (e.g. "Texturizing agent", "Emulsifier", "Preservative").
                   - `functionDescription`: "Provides consistency, maintains or modifies the texture of the product" or relevant function description.
                   - `associatedRisks`: Short organ/health risk card titles (e.g. "Cardiovascular diseases", "Adverse effects on the kidneys", "Disorders of bone mineralization", "Gut microbiome disruption").
                   - `scientificExplanation`: Detailed scientific paragraph explaining mineral/chemical mechanisms, contaminants, or safety.
                3. Return an overall `healthScore` (0-100) and 1-sentence `healthVerdict`.
            """.trimIndent()

            val partsArray = buildJsonArray {
                addJsonObject { put("text", prompt) }
            }

            val (rawJson, modelName) = executeRawGeminiCall(partsArray, ingredientOnlySchema())
            json.decodeFromString<IngredientAnalysisResponse>(rawJson).copy(
                modelUsed = modelName
            )
        }
    }

    suspend fun suggestHealthierAlternatives(
        productName: String,
        brandName: String,
        currentScore: Int,
        negativesSummary: String
    ): Result<List<HealthierAlternative>> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = """
                The user scanned a food product in the UK that scored $currentScore/100 (Poor/Bad):
                Product: "$productName" ${if (brandName.isNotBlank()) "by $brandName" else ""}
                Flagged Negatives per 100g: $negativesSummary

                Recommend 3 healthier, widely available UK supermarket alternatives (available at Tesco, Sainsbury's, Aldi, Asda, Morrisons, M&S, or Waitrose) in the same food/snack category that score between 68 and 95 out of 100.
                For each alternative provide:
                - `name`: Specific product name
                - `brandOrStore`: Brand and store without repeating own-brand names (e.g. "Nescafé • Tesco / Sainsbury's" or just "Sainsbury's" for own-brand)
                - `estimatedScore`: Integer Yuka health score (68 to 95)
                - `whyBetter`: 1 concise sentence explaining why it is healthier (e.g. "No E442/E476 phosphate emulsifiers and 45% less sugar per 100g").
            """.trimIndent()

            val partsArray = buildJsonArray {
                addJsonObject { put("text", prompt) }
            }

            val schema = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("alternatives") {
                        put("type", "ARRAY")
                        putJsonObject("items") {
                            put("type", "OBJECT")
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "STRING") }
                                putJsonObject("brandOrStore") { put("type", "STRING") }
                                putJsonObject("estimatedScore") { put("type", "INTEGER") }
                                putJsonObject("whyBetter") { put("type", "STRING") }
                            }
                            putJsonArray("required") {
                                add("name"); add("brandOrStore"); add("estimatedScore"); add("whyBetter")
                            }
                        }
                    }
                }
                putJsonArray("required") { add("alternatives") }
            }

            val (rawJson, _) = executeRawGeminiCall(partsArray, schema)
            json.decodeFromString<AlternativesWrapper>(rawJson).alternatives
        }
    }

    private fun ingredientFactItemSchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "STRING") }
            putJsonObject("rating") {
                put("type", "STRING")
                putJsonArray("enum") { add("GOOD"); add("CAUTION"); add("BAD") }
            }
            putJsonObject("category") { put("type", "STRING") }
            putJsonObject("explanation") { put("type", "STRING") }
        }
        putJsonArray("required") {
            add("name"); add("rating"); add("category"); add("explanation")
        }
    }

    private fun additiveDetailItemSchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("code") { put("type", "STRING") }
            putJsonObject("name") { put("type", "STRING") }
            putJsonObject("riskLevel") { put("type", "STRING") }
            putJsonObject("functionTitle") { put("type", "STRING") }
            putJsonObject("functionDescription") { put("type", "STRING") }
            putJsonObject("associatedRisks") {
                put("type", "ARRAY")
                putJsonObject("items") { put("type", "STRING") }
            }
            putJsonObject("scientificExplanation") { put("type", "STRING") }
        }
        putJsonArray("required") {
            add("code"); add("name"); add("riskLevel"); add("functionTitle")
            add("functionDescription"); add("associatedRisks"); add("scientificExplanation")
        }
    }

    private fun ingredientOnlySchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("productName") { put("type", "STRING") }
            putJsonObject("healthScore") { put("type", "INTEGER") }
            putJsonObject("healthVerdict") { put("type", "STRING") }
            putJsonObject("ingredientsRaw") { put("type", "STRING") }
            putJsonObject("ingredientFacts") {
                put("type", "ARRAY")
                put("items", ingredientFactItemSchema())
            }
            putJsonObject("additiveDetails") {
                put("type", "ARRAY")
                put("items", additiveDetailItemSchema())
            }
        }
        putJsonArray("required") {
            add("productName"); add("healthScore"); add("healthVerdict")
            add("ingredientFacts"); add("additiveDetails")
        }
    }

    private fun nutritionResponseSchema(includeIngredients: Boolean): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("mealName") { put("type", "STRING") }
            putJsonObject("calories") { put("type", "INTEGER") }
            putJsonObject("proteinGrams") { put("type", "NUMBER") }
            putJsonObject("carbsGrams") { put("type", "NUMBER") }
            putJsonObject("fatGrams") { put("type", "NUMBER") }
            putJsonObject("fiberGrams") { put("type", "NUMBER") }
            putJsonObject("confidenceNote") { put("type", "STRING") }
            putJsonObject("subItems") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "STRING") }
                        putJsonObject("grams") { put("type", "INTEGER") }
                        putJsonObject("calories") { put("type", "INTEGER") }
                        putJsonObject("proteinGrams") { put("type", "NUMBER") }
                        putJsonObject("carbsGrams") { put("type", "NUMBER") }
                        putJsonObject("fatGrams") { put("type", "NUMBER") }
                        putJsonObject("fiberGrams") { put("type", "NUMBER") }
                    }
                    putJsonArray("required") {
                        add("name"); add("grams"); add("calories")
                        add("proteinGrams"); add("carbsGrams"); add("fatGrams"); add("fiberGrams")
                    }
                }
            }
            if (includeIngredients) {
                putJsonObject("saturates100g") { put("type", "NUMBER") }
                putJsonObject("sugars100g") { put("type", "NUMBER") }
                putJsonObject("salt100g") { put("type", "NUMBER") }
                putJsonObject("ingredients") { put("type", "STRING") }
                putJsonObject("healthScore") { put("type", "INTEGER") }
                putJsonObject("healthVerdict") { put("type", "STRING") }
                putJsonObject("ingredientFacts") {
                    put("type", "ARRAY")
                    put("items", ingredientFactItemSchema())
                }
                putJsonObject("additiveDetails") {
                    put("type", "ARRAY")
                    put("items", additiveDetailItemSchema())
                }
            }
        }
        putJsonArray("required") {
            add("mealName"); add("calories"); add("proteinGrams")
            add("carbsGrams"); add("fatGrams"); add("fiberGrams")
            add("confidenceNote"); add("subItems")
            if (includeIngredients) {
                add("ingredients"); add("healthScore"); add("healthVerdict")
                add("ingredientFacts"); add("additiveDetails")
            }
        }
    }

    private fun buildGeminiPayload(
        partsArray: JsonArray,
        schema: JsonObject,
        includeThinkingConfig: Boolean
    ): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("parts", partsArray)
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", schema)
            if (includeThinkingConfig) {
                putJsonObject("thinkingConfig") {
                    put("thinkingLevel", "low")
                }
            }
        }
    }

    private suspend fun executeGeminiNutritionRequest(
        partsArray: JsonArray,
        includeIngredientsSchema: Boolean
    ): NutritionResult {
        val (cleanedJson, modelName) = executeRawGeminiCall(
            partsArray = partsArray,
            schema = nutritionResponseSchema(includeIngredientsSchema)
        )
        val decoded = json.decodeFromString<NutritionResult>(cleanedJson)
        return decoded.copy(
            modelUsed = modelName,
            additivesCount = maxOf(decoded.additivesCount, decoded.additiveDetails.size)
        )
    }

    private suspend fun executeRawGeminiCall(
        partsArray: JsonArray,
        schema: JsonObject
    ): Pair<String, String> {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank()) {
            error("GEMINI_API_KEY is missing in local.properties.")
        }

        var lastError: Throwable? = null
        val modelsToTry = orderedModels()

        for ((attempt, modelName) in modelsToTry.withIndex()) {
            try {
                val payload = buildGeminiPayload(partsArray, schema, includeThinkingConfig = true)
                return callModelEndpointRaw(modelName, apiKey, payload).also {
                    preferredModel = modelName
                    preferredUntilMillis = System.currentTimeMillis() + 10 * 60_000L
                }
            } catch (e: ThinkingConfigRejectedException) {
                try {
                    val fallbackPayload = buildGeminiPayload(partsArray, schema, includeThinkingConfig = false)
                    return callModelEndpointRaw(modelName, apiKey, fallbackPayload).also {
                        preferredModel = modelName
                        preferredUntilMillis = System.currentTimeMillis() + 10 * 60_000L
                    }
                } catch (inner: IOException) {
                    lastError = inner
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < modelsToTry.lastIndex) {
                    delay(600L * (attempt + 1))
                }
            }
        }
        error("AI scan failed after trying all Flash models. (${lastError?.message ?: "Unknown error"})")
    }

    private class ThinkingConfigRejectedException : IOException()

    private fun callModelEndpointRaw(
        modelName: String,
        apiKey: String,
        payload: JsonObject
    ): Pair<String, String> {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: error("Empty response from $modelName")

            if (response.code in listOf(404, 429, 500, 502, 503, 504)) {
                throw IOException("HTTP ${response.code} on $modelName")
            }

            if (response.code == 400 && bodyStr.contains("thinking", ignoreCase = true)) {
                throw ThinkingConfigRejectedException()
            }

            if (!response.isSuccessful) {
                error("Gemini API error ${response.code} ($modelName): $bodyStr")
            }

            val root = json.parseToJsonElement(bodyStr).jsonObject
            val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?: error("No candidate parts returned from $modelName")

            val rawText = parts
                .map { it.jsonObject }
                .lastOrNull { partObj ->
                    val isThought = partObj["thought"]?.jsonPrimitive?.booleanOrNull == true
                    !isThought && partObj["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                }
                ?.get("text")?.jsonPrimitive?.content
                ?: parts.lastOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: error("No JSON text found in response")

            val cleanedJson = rawText
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            return cleanedJson to modelName
        }
    }

    suspend fun lookupBarcode(barcode: String): Result<NutritionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val fields = "product_name,brands,serving_size,ingredients_text,nutriscore_grade,nova_group,additives_tags,nutriments"
            val url = "https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=$fields"

            var lastError: Throwable? = null
            for (attempt in 0..1) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "MacroSnapPersonalApp/1.0")
                        .build()

                    client.newCall(request).execute().use { response ->
                        val bodyStr = response.body?.string() ?: error("Empty response")
                        if (!response.isSuccessful && response.code >= 500) {
                            throw IOException("Open Food Facts server busy (${response.code})")
                        }
                        val parsed = json.decodeFromString<OffResponse>(bodyStr)
                        if (parsed.status != 1 || parsed.product == null) {
                            throw BarcodeNotFoundException(barcode)
                        }
                        val p = parsed.product
                        val n = p.nutriments
                        val servingHint = p.servingSize?.let { " (1 serving = $it)" } ?: ""
                        val resolvedAdditives = YukaHealthEngine.resolveAdditivesFromTags(p.additivesTags)

                        return@runCatching NutritionResult(
                            mealName = p.productName?.ifBlank { "Packaged Item ($barcode)" } ?: "Packaged Item ($barcode)",
                            brandName = p.brands?.split(",")?.firstOrNull()?.trim() ?: "",
                            calories = (n.energyKcal100g ?: n.energyKcalServing ?: 0.0).toInt(),
                            proteinGrams = n.proteins100g ?: n.proteinsServing ?: 0.0,
                            carbsGrams = n.carbs100g ?: n.carbsServing ?: 0.0,
                            fatGrams = n.fat100g ?: n.fatServing ?: 0.0,
                            fiberGrams = n.fiber100g ?: n.fibre100g ?: n.fiberServing ?: n.fibreServing ?: 0.0,
                            saturates100g = n.saturatedFat100g ?: n.saturatedFatServing,
                            sugars100g = n.sugars100g ?: n.sugarsServing,
                            salt100g = n.salt100g ?: n.saltServing,
                            confidenceNote = "Base values per 100g$servingHint",
                            ingredients = p.ingredientsText ?: "",
                            nutriscore = p.nutriscoreGrade?.uppercase() ?: "?",
                            novaGroup = p.novaGroup ?: 0,
                            additivesCount = p.additivesTags.size,
                            additiveDetails = resolvedAdditives
                        )
                    }
                } catch (e: IOException) {
                    lastError = e
                    if (attempt == 0) delay(1000L)
                }
            }
            error("Barcode lookup timed out. Check your connection and try again. (${lastError?.message})")
        }
    }

    fun encodeSubItems(items: List<DetectedFoodItem>): String =
        if (items.isEmpty()) "" else runCatching { json.encodeToString(items) }.getOrDefault("")

    fun decodeSubItems(raw: String): List<DetectedFoodItem> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<DetectedFoodItem>>(raw) }.getOrDefault(emptyList())

    private fun scaleBitmap(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val ratio = minOf(maxEdge.toFloat() / bitmap.width, maxEdge.toFloat() / bitmap.height)
        if (ratio >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt(),
            (bitmap.height * ratio).toInt(),
            true
        )
    }
}

@Serializable
private data class OffResponse(val status: Int = 0, val product: OffProduct? = null)

@Serializable
private data class OffProduct(
    @SerialName("product_name") val productName: String? = null,
    @SerialName("brands") val brands: String? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    @SerialName("ingredients_text") val ingredientsText: String? = null,
    @SerialName("nutriscore_grade") val nutriscoreGrade: String? = null,
    @SerialName("nova_group") val novaGroup: Int? = null,
    @SerialName("additives_tags") val additivesTags: List<String> = emptyList(),
    val nutriments: OffNutriments = OffNutriments()
)

@Serializable
private data class OffNutriments(
    @SerialName("energy-kcal_serving") val energyKcalServing: Double? = null,
    @SerialName("energy-kcal_100g") val energyKcal100g: Double? = null,
    @SerialName("proteins_serving") val proteinsServing: Double? = null,
    @SerialName("proteins_100g") val proteins100g: Double? = null,
    @SerialName("carbohydrates_serving") val carbsServing: Double? = null,
    @SerialName("carbohydrates_100g") val carbs100g: Double? = null,
    @SerialName("fat_serving") val fatServing: Double? = null,
    @SerialName("fat_100g") val fat100g: Double? = null,
    @SerialName("saturated-fat_100g") val saturatedFat100g: Double? = null,
    @SerialName("saturated-fat_serving") val saturatedFatServing: Double? = null,
    @SerialName("sugars_100g") val sugars100g: Double? = null,
    @SerialName("sugars_serving") val sugarsServing: Double? = null,
    @SerialName("salt_100g") val salt100g: Double? = null,
    @SerialName("salt_serving") val saltServing: Double? = null,
    @SerialName("fiber_serving") val fiberServing: Double? = null,
    @SerialName("fiber_100g") val fiber100g: Double? = null,
    @SerialName("fibre_100g") val fibre100g: Double? = null,
    @SerialName("fibre_serving") val fibreServing: Double? = null
)
