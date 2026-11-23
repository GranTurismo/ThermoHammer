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

# Jetpack Compose needs no rule of its own: its libraries carry consumer rules. A blanket
# "-keep class androidx.compose.** { *; }" used to sit here; it kept ~7,200 classes out of R8's
# shrinking, obfuscation and optimisation, which left 75% of the DEX code untouched and Play's
# "DEX code optimization" score at 24% (Play warns under 25%).

# WebView benchmark bridge (ui/components/UxStage.kt): the page calls these by name
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ML Kit barcode scanner (UX QR test): its component registry looks factories up by class at
# runtime, and R8 optimisation leaves BarcodeScanning.getClient with a null factory
-keep class com.google.mlkit.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode** { *; }

# WorkManager (pulled in by LiteRT → Play Asset Delivery) builds its Room database by reflection; R8 stripped
# the generated implementation and the app died at launch
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# LiteRT (AI tests): its native code finds Java classes by name — LiteRtException included — to throw and to call
# back; renamed by R8, a failed model compile aborted the whole app instead of throwing
-keep class com.google.ai.edge.litert.** { *; }
# LiteRT-LM (test builds only: the NPU LLM probe): JNI calls back into its Kotlin classes; its AAR has no rules
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Keep file names and line numbers for Crashlytics stack traces in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
