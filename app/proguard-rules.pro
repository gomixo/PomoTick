# R8 / ProGuard 规则

# === Room 反射 ===
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# === DataStore ===
-keep class androidx.datastore.** { *; }

# === Kotlin 协程 ===
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# === Compose ===
-keep class androidx.compose.runtime.** { *; }

# === 应用实体类 ===
-keep class com.pomotick.data.TimerSession { *; }
-keep class com.pomotick.data.SessionStatus { *; }
-keep class com.pomotick.timer.TimerPhase { *; }
-keep class com.pomotick.timer.TimerRunState { *; }
-keep class com.pomotick.timer.TimerRuntimeState { *; }

# === 保留行号（崩溃日志可读） ===
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
