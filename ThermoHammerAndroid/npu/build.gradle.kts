// NPU runtimes for the AI tests, as install-time Play modules that only the phones using them download. Each
// module's AndroidManifest.xml names its device groups (app/device_targeting_configuration.xml), as Google's
// LiteRT NPU samples do. The sideloaded bench build (one APK) bundles QNN in the app instead.
//   qnn_htp, qnn_v68 … qnn_v81: Qualcomm's QNN, from the qnn-runtime AAR on Maven — the part every HTP Snapdragon
//     shares (with the on-device compiler) and one module per Hexagon version (its DSP image and stub).
//   mtk_v8, mtk_v9: MediaTek's NeuroPilot adapter for Dimensity chips, from the NeuroPilot SDK Google's samples
//     fetch. Its license lets apps ship it inside them but not pass it on by itself, so it's downloaded here and
//     never committed (the repo is public).

/** Downloads a file once and checks its SHA-256. */
abstract class Download : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val file: RegularFileProperty

    @TaskAction
    fun fetch() {
        val out = file.get().asFile
        val part = File(out.path + ".part")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.net.URI(url.get()).toURL().openStream().use { input ->
            java.security.DigestInputStream(input, digest).use { d -> part.outputStream().use { d.copyTo(it) } }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        check(got == sha256.get()) { "${url.get()}: SHA-256 $got, expected ${sha256.get()}" }
        check(part.renameTo(out)) { "can't write $out" }
    }
}

/** Copies the module's libraries out of an archive (an AAR, or a .tar.gz) into a jniLibs directory. */
abstract class NpuLibs : DefaultTask() {
    @get:InputFiles abstract val archive: ConfigurableFileCollection
    @get:Input abstract val paths: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val files: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun extract() {
        val file = archive.singleFile
        val tree = if (file.name.endsWith(".gz")) archives.tarTree(archives.gzip(file)) else archives.zipTree(file)
        files.sync {
            from(tree) {
                include(paths.get())
                eachFile { path = "arm64-v8a/$name" }
            }
            includeEmptyDirs = false
            into(outputDir)
        }
        val missing = paths.get().map { it.substringAfterLast('/') }
            .filterNot { outputDir.file("arm64-v8a/$it").get().asFile.exists() }
        check(missing.isEmpty()) { "${file.name} has no $missing" }
    }
}

val qnnRuntime = "com.qualcomm.qti:qnn-runtime:${libs.versions.qnnRuntime.get()}@aar"
val qnnLibs = mapOf("qnn_htp" to listOf("libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so")) +
    listOf(68, 69, 73, 75, 79, 81).associate { v -> "qnn_v$v" to listOf("libQnnHtpV${v}Skel.so", "libQnnHtpV${v}Stub.so") }

// NeuroPilot adapter 8 for the Dimensity 9400 and older, 9 for the 9500 (fetch_mediatek_library.sh)
val neuroPilotLibs = mapOf(
    "mtk_v8" to "neuro_pilot/v8_0_10/usdk/lib64/libneuronusdk_adapter.mtk.so",
    "mtk_v9" to "neuro_pilot/v9_0_3/usdk/lib64/libneuronusdk_adapter.so")
val neuroPilotSdk = tasks.register<Download>("neuroPilotSdk") {
    url.set("https://s3.ap-southeast-1.amazonaws.com/mediatek.neuropilot.com/66f2c33a-2005-4f0b-afef-2053c8654e4f.gz")
    sha256.set("f69434d45856964627c750e716b835988a1f07511b6196d7f070fdde26027994")
    file.set(layout.buildDirectory.file("neuropilot-sdk.tar.gz"))
}

subprojects { plugins.withId("com.android.dynamic-feature") {
    dependencies { "implementation"(project(":app")) }
    val extract = tasks.register<NpuLibs>("npuLibs") { outputDir.set(layout.buildDirectory.dir("npu")) }
    if (project.name in qnnLibs) {
        val qnn = configurations.create("qnn")
        dependencies { "qnn"(qnnRuntime) }
        extract.configure {
            archive.from(qnn)
            paths.set(qnnLibs.getValue(project.name).map { "jni/arm64-v8a/$it" })
        }
    } else {
        extract.configure {
            archive.from(neuroPilotSdk.flatMap { it.file })
            paths.set(listOf(neuroPilotLibs.getValue(project.name)))
        }
    }

    extensions.configure<com.android.build.api.dsl.DynamicFeatureExtension> {
        namespace = "com.example.thermohammer.npu.${project.name}"
        compileSdk = 36
        ndkVersion = "28.2.13676358"    // the app's
        defaultConfig {
            minSdk = 24
            externalNativeBuild { cmake { arguments += "-DMODULE=${project.name}" } }
        }
        externalNativeBuild { cmake { path = parent!!.file("CMakeLists.txt"); version = "3.22.1" } }
        buildTypes { create("bench") }
        packaging { jniLibs { useLegacyPackaging = true } }    // compressed in the APK, extracted on install, as in the app
    }
    extensions.configure<com.android.build.api.variant.DynamicFeatureAndroidComponentsExtension> {
        onVariants { it.sources.jniLibs?.addGeneratedSourceDirectory(extract, NpuLibs::outputDir) }
    }
} }
