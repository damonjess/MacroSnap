package com.example.macrosnap

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class MealCategory(val displayName: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack");

    companion object {
        fun defaultForTimestamp(millis: Long = System.currentTimeMillis()): MealCategory {
            val hour = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).hour
            return when (hour) {
                in 5..10 -> BREAKFAST
                in 11..15 -> LUNCH
                in 17..21 -> DINNER
                else -> SNACK
            }
        }

        fun fromString(value: String): MealCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.displayName.equals(value, ignoreCase = true) }
                ?: SNACK
    }
}

@Entity(tableName = "meal_logs")
data class MealLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long = LocalDate.now().toEpochDay(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val name: String,
    val calories: Int,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val fiberGrams: Double,
    val source: String,
    val notes: String = "",
    val category: String = MealCategory.defaultForTimestamp().name,
    val photoPath: String? = null,
    val isFavorite: Boolean = false,
    val subItemsJson: String = ""
)

@Entity(tableName = "daily_metrics")
data class DailyMetricsEntity(
    @PrimaryKey val dateEpochDay: Long = LocalDate.now().toEpochDay(),
    val waterMl: Int = 0,
    val steps: Int = 0,
    val activeCalories: Int = 0,
    val weightKg: Float? = null
)

@Dao
interface MealLogDao {
    @Query("SELECT * FROM meal_logs WHERE dateEpochDay = :epochDay ORDER BY timestampMillis DESC")
    fun observeLogsForDay(epochDay: Long): Flow<List<MealLogEntity>>

    @Query("SELECT * FROM meal_logs WHERE dateEpochDay = :epochDay ORDER BY timestampMillis DESC")
    suspend fun getLogsForDayOnce(epochDay: Long): List<MealLogEntity>

    @Query("SELECT * FROM meal_logs WHERE dateEpochDay >= :startEpochDay ORDER BY dateEpochDay ASC, timestampMillis ASC")
    fun observeLogsSince(startEpochDay: Long): Flow<List<MealLogEntity>>

    @Query("SELECT * FROM meal_logs ORDER BY isFavorite DESC, timestampMillis DESC LIMIT 40")
    fun observeRecentLogs(): Flow<List<MealLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MealLogEntity): Long

    @Update
    suspend fun update(entry: MealLogEntity)

    @Delete
    suspend fun delete(entry: MealLogEntity)

    @Query("UPDATE meal_logs SET isFavorite = :favorite WHERE name = :name")
    suspend fun setFavoriteByName(name: String, favorite: Boolean)

    @Query("SELECT * FROM daily_metrics WHERE dateEpochDay = :epochDay")
    fun observeMetricsForDay(epochDay: Long): Flow<DailyMetricsEntity?>

    @Query("SELECT * FROM daily_metrics WHERE dateEpochDay = :epochDay")
    suspend fun getMetricsForDayOnce(epochDay: Long): DailyMetricsEntity?

    @Query("SELECT * FROM daily_metrics WHERE dateEpochDay >= :startEpochDay ORDER BY dateEpochDay ASC")
    fun observeMetricsSince(startEpochDay: Long): Flow<List<DailyMetricsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetrics(metrics: DailyMetricsEntity)
}

@Database(
    entities = [MealLogEntity::class, DailyMetricsEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mealLogDao(): MealLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** Preserves existing meal logs & metrics when adding categories, photos, favorites, sub-items and weight. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meal_logs ADD COLUMN category TEXT NOT NULL DEFAULT 'SNACK'")
                db.execSQL("ALTER TABLE meal_logs ADD COLUMN photoPath TEXT")
                db.execSQL("ALTER TABLE meal_logs ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meal_logs ADD COLUMN subItemsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE daily_metrics ADD COLUMN weightKg REAL")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "macrosnap.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
