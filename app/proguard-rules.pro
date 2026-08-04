# ============================================================
# FloWave ProGuard / R8 Rules
# ============================================================

# Preserve source file names and line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve all annotations (required by many libraries)
-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod

# ── Kotlin ──────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ── Kotlin Coroutines ────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── OkHttp & Okio ───────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Retrofit ────────────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault

# ── Moshi ───────────────────────────────────────────────────
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
# Keep all Moshi-generated JsonAdapter classes
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **JsonAdapter { *; }
-keep class **JsonAdapter$* { *; }
-dontwarn com.squareup.moshi.**

# ── Room Database ────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers @androidx.room.Entity class * { *; }
-keepclassmembers @androidx.room.Embedded class * { *; }
-keep class com.example.flowave.data.** { *; }
-dontwarn androidx.room.**

# ── ExoPlayer / Media3 ──────────────────────────────────────
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn androidx.media3.**
-dontwarn com.google.android.exoplayer2.**
# Keep ExoPlayer extension renderers
-keepclassmembers class * implements androidx.media3.common.util.Clock {
    public *;
}

# ── DataStore ────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends androidx.datastore.core.Serializer { *; }

# ── Coil ────────────────────────────────────────────────────
-dontwarn coil.**
-keep class coil.** { *; }

# ── FloWave App Classes ──────────────────────────────────────
# Keep all FloWave model/entity classes to prevent R8 from renaming
# fields that Room and Moshi reference by name at runtime
-keep class com.example.flowave.data.model.** { *; }
-keep class com.example.flowave.utils.FloWaveConstants { *; }
-keep class com.example.flowave.utils.SettingsSchema { *; }
-keep class com.example.flowave.downloader.DownloadState { *; }
-keep class com.example.flowave.downloader.DownloadState$* { *; }

# ── Enum classes ─────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Serialization / Reflection ───────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── AndroidX / Jetpack Compose ──────────────────────────────
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**
