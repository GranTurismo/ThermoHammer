# ProGuard / R8 Rules for ThermoHammer

# Preserve Retrofit & OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Preserve API Network & Data Models
-keep class com.example.thermohammer.network.** { *; }
-keep class com.example.thermohammer.data.** { *; }
-keep class com.example.thermohammer.engine.** { *; }

# Preserve Gson SerializedName annotations
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Preserve Jetpack Compose
-keep class androidx.compose.** { *; }
