package com.example.macrosnap

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

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
data class NutritionResult(
    val mealName: String,
    val calories: Int,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val fiberGrams: Double,
    val confidenceNote: String = "",
    val ingredients: String = "",
    val nutriscore: String = "?",
    val novaGroup: Int = 0,
    val additivesCount: Int = 0,
    val subItems: List<DetectedFoodItem> = emptyList(),
    val photoPath: String? = null
)

class BarcodeNotFoundException(val barcode: String) :
    Exception("Barcode $barcode not found in Open Food Facts. You can scan the Nutrition Facts label instead.")

class NutritionRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val GEMINI_MODEL = "gemini-3.8-flash"

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
        isNutritionLabelOcr: Boolean = false
    ): Result<NutritionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val scaled = scaleBitmap(bitmap, 1024)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val basePrompt = if (isNutritionLabelOcr) {
                """
                Read this Nutrition Facts table / food package label using OCR.
                If it is a UK/EU nutrition label, read the exact Per 100g values (including Fibre/Fiber).
                Extract the product or food name, serving size in grams, and exact calories, protein, carbs, fat, and fiber per serving.
                Include a single entry in subItems representing 1 serving, and note the serving size in confidenceNote.
                """.trimIndent()
            } else {
                """
                Analyze this food, meal, menu, or nutrition label image.
                If it is a UK/EU nutrition label, read the exact Per 100g values (including Fibre/Fiber).
                If it is a plate of food, estimate portion sizes in grams for each visible item, 
                then calculate total calories, protein, carbs, fat, and fiber. Break down each distinct visible food item in `subItems` with its estimated weight in grams and individual macros.
                Mention any likely hidden calories (cooking oils, sauces, dressings) in confidenceNote.
                """.trimIndent()
            }

            val fullPrompt = if (userHint.isNotBlank()) {
                "$basePrompt\nUser-provided context/notes: \"$userHint\""
            } else {
                basePrompt
            }

            val payload = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject { put("text", fullPrompt) }
                            addJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                }
                            }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("responseSchema", nutritionResponseSchema())
                }
            }

            executeGeminiRequest(payload)
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

            val payload = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject { put("text", prompt) }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("responseSchema", nutritionResponseSchema())
                }
            }

            executeGeminiRequest(payload)
        }
    }

    private fun nutritionResponseSchema(): JsonObject = buildJsonObject {
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
        }
        putJsonArray("required") {
            add("mealName"); add("calories"); add("proteinGrams")
            add("carbsGrams"); add("fatGrams"); add("fiberGrams")
            add("confidenceNote"); add("subItems")
        }
    }

    private fun executeGeminiRequest(payload: JsonObject): NutritionResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=${BuildConfig.GEMINI_API_KEY}"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: error("Empty response")
            if (!response.isSuccessful) error("Gemini API error ${response.code}: $bodyStr")

            val root = json.parseToJsonElement(bodyStr).jsonObject
            val text = root["candidates"]?.jsonArray?.get(0)?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.get(0)?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?: error("No candidate text returned")

            return json.decodeFromString<NutritionResult>(text)
        }
    }

    suspend fun lookupBarcode(barcode: String): Result<NutritionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://world.openfoodfacts.org/api/v2/product/$barcode.json")
                .header("User-Agent", "MacroSnapPersonalApp/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: error("Empty response")
                val parsed = json.decodeFromString<OffResponse>(bodyStr)
                if (parsed.status != 1 || parsed.product == null) {
                    throw BarcodeNotFoundException(barcode)
                }
                val p = parsed.product
                val n = p.nutriments
                val servingHint = p.servingSize?.let { " (1 serving = $it)" } ?: ""
                NutritionResult(
                    mealName = p.productName?.ifBlank { "Packaged Item ($barcode)" } ?: "Packaged Item ($barcode)",
                    calories = (n.energyKcal100g ?: n.energyKcalServing ?: 0.0).toInt(),
                    proteinGrams = n.proteins100g ?: n.proteinsServing ?: 0.0,
                    carbsGrams = n.carbs100g ?: n.carbsServing ?: 0.0,
                    fatGrams = n.fat100g ?: n.fatServing ?: 0.0,
                    fiberGrams = n.fiber100g ?: n.fibre100g ?: n.fiberServing ?: n.fibreServing ?: 0.0,
                    confidenceNote = "Base values per 100g$servingHint",
                    ingredients = p.ingredientsText ?: "",
                    nutriscore = p.nutriscoreGrade?.uppercase() ?: "?",
                    novaGroup = p.novaGroup ?: 0,
                    additivesCount = p.additivesTags.size
                )
            }
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
    @SerialName("fiber_serving") val fiberServing: Double? = null,
    @SerialName("fiber_100g") val fiber100g: Double? = null,
    @SerialName("fibre_100g") val fibre100g: Double? = null,
    @SerialName("fibre_serving") val fibreServing: Double? = null
)
