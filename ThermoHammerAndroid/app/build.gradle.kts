import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
}

android {
    // UX test media (assets/media): MediaExtractor reads the clips through openFd, which needs them stored
    androidResources { noCompress += listOf("mp4", "webm", "webp", "avif", "tflite") }
    namespace = "com.example.thermohammer"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.granturismo.thermohammer"
        minSdk = 24
        targetSdk = 36
        versionCode = 29
        versionName = "2.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }

    // GPU Test 3 (Neon City RT): the Vulkan ray-query renderer in src/main/cpp
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release-signing secrets live OUTSIDE the repo (the repo is public). On each build machine put
    // these in ~/.gradle/gradle.properties, or provide them as environment variables:
    //   THERMOHAMMER_STORE_FILE=/path/to/GTech.keystore
    //   THERMOHAMMER_STORE_PASSWORD=...
    //   THERMOHAMMER_KEY_ALIAS=gtech        (optional - defaults to gtech)
    //   THERMOHAMMER_KEY_PASSWORD=...
    // Without them the release build still works, but is left UNSIGNED.
    fun signingSecret(name: String): String? =
        (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv(name)?.takeIf { it.isNotBlank() }
    val releaseStoreFile = signingSecret("THERMOHAMMER_STORE_FILE")

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingSecret("THERMOHAMMER_STORE_PASSWORD")
                keyAlias = signingSecret("THERMOHAMMER_KEY_ALIAS") ?: "gtech"
                keyPassword = signingSecret("THERMOHAMMER_KEY_PASSWORD")
            }
        } else {
            logger.warn("THERMOHAMMER_STORE_FILE is not set - the release APK will be UNSIGNED (see build.gradle.kts).")
        }
    }

    buildTypes {
        getByName("debug") {
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
                nativeSymbolUploadEnabled = false
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = true
                nativeSymbolUploadEnabled = true
            }
        }
        // A release-speed build for testing on a phone without USB debugging: optimised and not
        // debuggable (so CPU scores are real), debug-signed, and installed beside the Play app.
        create("bench") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench"
            matchingFallbacks += "release"
            ndk { abiFilters += "arm64-v8a" }          // phones to sideload onto, and the arm64 emulator
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
                nativeSymbolUploadEnabled = false
            }
        }
    }

    // Production-only Firebase: debug and bench share a no-op AppTelemetry stub and skip Firebase tasks.
    sourceSets {
        getByName("debug").kotlin.directories.add("src/nonRelease/java")
        getByName("bench").kotlin.directories.add("src/nonRelease/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      jniLibs {
        // The NPU loads QNN's Hexagon libraries from files, so the native libraries are extracted on install
        useLegacyPackaging = true
        // Qualcomm's QNN runtime in the bench build: just the HTP backend for Snapdragon 8 Gen 2 – 8 Elite Gen 5
        excludes += listOf("**/libQnnDsp*.so", "**/libQnnGpu.so", "**/libQnnHtpV68*.so", "**/libQnnHtpV69*.so")
      }
    }

}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Network
  implementation(libs.retrofit.core)
  implementation(libs.retrofit.gson)
  implementation(libs.okhttp.core)
  implementation(libs.okhttp.logging)
  implementation(libs.kotlinx.coroutines.android)

  // UX test: QR scanning — ZXing (scored, CPU) and ML Kit's bundled scanner (reported)
  implementation(libs.mlkit.barcode)

  // Google Play's in-app rating card (ui/review/ReviewPrompt.kt)
  implementation(libs.play.review)

  // AI tests: LiteRT on the CPU and the GPU. Version 5 doesn't score the NPU, so Play builds carry no NPU runtime;
  // test builds keep LiteRT's vendor dispatch libraries (and Qualcomm's QNN in the bench build) for a report-only NPU run.
  implementation(libs.litert)
  implementation(libs.litert.gpu)
  "benchImplementation"(libs.litert.npu.qualcomm)
  "benchImplementation"(libs.litert.npu.mediatek)
  "benchImplementation"(libs.litert.npu.google.tensor)
  debugImplementation(libs.litert.npu.qualcomm)
  debugImplementation(libs.litert.npu.mediatek)
  debugImplementation(libs.litert.npu.google.tensor)
  "benchImplementation"(libs.qnn.runtime)

  // Production-only Firebase Analytics + Crashlytics (including native C/C++ NDK symbols).
  "releaseImplementation"(platform(libs.firebase.bom))
  "releaseImplementation"(libs.firebase.crashlytics)
  "releaseImplementation"(libs.firebase.crashlytics.ndk)
  "releaseImplementation"(libs.firebase.analytics)
}

// Crashlytics uploads the R8 mapping file as part of the release build, but not the native (NDK) symbols: without them
// a crash in Vulkan or a GPU or NPU driver shows only addresses. Every release bundle or APK uploads them too.
tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
    finalizedBy("uploadCrashlyticsSymbolFileRelease")
}

// Disable Google Services and Crashlytics Gradle tasks on non-release variants (debug and bench).
tasks.configureEach {
    if ((name.contains("GoogleServices", ignoreCase = true) || name.contains("Crashlytics", ignoreCase = true)) &&
        !name.contains("Release")) {
        enabled = false
    }
}
