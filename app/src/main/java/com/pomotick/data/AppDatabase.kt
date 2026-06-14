package com.pomotick.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.pomotick.timer.TimerPhase

/**
 * Room 数据库（单实例，懒加载；无 Hilt）。
 *
 * MVP 只包含 [TimerSession] 一张表。
 */
@Database(
    entities = [TimerSession::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun timerSessionDao(): TimerSessionDao

    companion object {
        private const val DB_NAME = "pomotick.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // MVP 阶段：未来若调整 schema，丢弃旧数据（避免 migration 复杂度）
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

/**
 * Room 类型转换器（仅处理 enum）。
 */
class Converters {
    @TypeConverter
    fun fromTimerPhase(value: TimerPhase): String = value.name

    @TypeConverter
    fun toTimerPhase(value: String): TimerPhase = TimerPhase.valueOf(value)
}
