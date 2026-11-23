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
        versionCode = 27
        versionName = "2.1.7"
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

    // Play builds get the NPU runtimes (Qualcomm QNN, MediaTek NeuroPilot) through the npu/ modules, installed only
    // on the chips in these device groups
    dynamicFeatures += listOf(":npu:qnn_htp", ":npu:qnn_v68", ":npu:qnn_v69", ":npu:qnn_v73", ":npu:qnn_v75",
        ":npu:qnn_v79", ":npu:qnn_v81", ":npu:mtk_v8", ":npu:mtk_v9")
    // The LLM test's NPU model (version 5): Google's Gemma 3 1B file for the phone's chip, a fast-follow AI pack split
    // by the same device groups (ai/gemma_npu/build.gradle.kts) — each phone downloads only its own chip's file
    assetPacks += listOf(":ai:gemma_npu")
    bundle {
        deviceTargetingConfig = file("device_targeting_configuration.xml")
        deviceGroup {
            enableSplit = true
            defaultGroup = "other"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// The LLM test's NPU engine (engine/llm/NpuLlmProbe.kt): LiteRT-LM 0.19 loads NPU dispatch libraries
// with the versioned dispatch ABI (LiteRtDispatchQueryInterface), which LiteRT 2.4's have and the scored AI test's 2.3
// ones don't. So 2.4's go in as assets (litertlm-npu/), copied out beside the app's QNN runtime at run time; the scored
// AI test keeps 2.3's in jniLibs. Release builds get none of this.
val litertLmNpu: Configuration by configurations.creating { isTransitive = false }

abstract class LiteRtLmNpuAssets : DefaultTask() {
    @get:InputFiles abstract val aars: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun extract() {
        val trees = aars.files.map { archives.zipTree(it) }
        files.sync {
            for (tree in trees) from(tree) {
                include("jni/arm64-v8a/libLiteRtDispatch_*.so")
                eachFile { path = "litertlm-npu/$name" }
            }
            into(outputDir)
            includeEmptyDirs = false
        }
    }
}

val litertLmNpuAssets = tasks.register<LiteRtLmNpuAssets>("litertLmNpuAssets") {
    aars.from(litertLmNpu)
    outputDir.set(layout.buildDirectory.dir("generated/litertlm-npu-assets"))
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(litertLmNpuAssets, LiteRtLmNpuAssets::outputDir)
    }
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

  // AI tests: LiteRT on the CPU, the GPU and the NPU (vendor dispatch libraries). Qualcomm's QNN runtime
  // goes into the sideloaded bench build; Play builds get it, and MediaTek's adapter, from the npu/ modules.
  implementation(libs.litert)
  implementation(libs.litert.gpu)
  implementation(libs.litert.npu.qualcomm)
  implementation(libs.litert.npu.mediatek)
  implementation(libs.litert.npu.google.tensor)
  "benchImplementation"(libs.qnn.runtime)
  // The LLM test's NPU engine (version 5): Google's LiteRT-LM (engine/llm/NpuLlmProbe.kt), and Play's AI Delivery
  // library for its model, the gemma_npu AI pack (engine/llm/NpuModels.kt)
  implementation(libs.litertlm.android)
  implementation(libs.ai.delivery)
  litertLmNpu(variantOf(libs.litertlm.npu.qualcomm) { artifactType("aar") })
  litertLmNpu(variantOf(libs.litertlm.npu.mediatek) { artifactType("aar") })

  // Production-only Firebase Analytics + Crashlytics (including native C/C++ NDK symbols).
  "releaseImplementation"(platform(libs.firebase.bom))
  "releaseImplementation"(libs.firebase.crashlytics)
  "releaseImplementation"(libs.firebase.crashlytics.ndk)
  "releaseImplementation"(libs.firebase.analytics)
}

// Crashlytics uploads the R8 mapping file as part of the release build, but not the native (NDK) symbols: without them
// a crash in Vulkan, an NPU driver or llama.cpp shows only addresses. Every release bundle or APK uploads them too.
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

// A Play bundle carries the NPU model for every supported chip, or none is built: a chip whose file is missing would
// get an empty pack, and its phones' runs couldn't be submitted
tasks.matching { it.name == "bundleRelease" }.configureEach { dependsOn(":ai:gemma_npu:checkGemmaModels") }
