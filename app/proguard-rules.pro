-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeVisibleTypeAnnotations

# Kotlin Serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class org.michimusic.core.models.** { *; }
-keepclassmembers class org.michimusic.sync.models.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Michi playback service
-keep class org.michimusic.player.MichiPlaybackService { *; }
-keep class org.michimusic.player.PlayerController { *; }
-keep class org.michimusic.player.LibraryProvider { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# Coil
-dontwarn coil.**
-keep class coil.** { *; }

# Room
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <fields>;
}

# Media3
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Gson / kotlinx-serialization reflection
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
