// The LLM test's NPU model as a Play AI pack (Play for On-device AI): Google's Gemma 3 1B build for each chip that has
// one (huggingface.co/litert-community/Gemma3-1B-IT, 4-bit, a 1,280-token context), in a folder per chip —
// gemma#group_<device group>/ (app/device_targeting_configuration.xml). Play gives a phone only its own chip's folder
// (as gemma/); a phone with no group gets an empty pack. Fast-follow: Play downloads it right after the install, and
// the app finds it as a file (AiPackManager.getPackLocation — what LiteRT-LM needs; an install-time pack is only an
// asset stream). engine/llm/NpuModels.kt reads it.
//
// The files aren't in git: the repository is public and Gemma's files are behind its licence on Hugging Face. Download
// them once (a Hugging Face account that accepted the Gemma licence), put them in one folder and name it in
// local.properties as `gemma.npu.dir=/path` (default ~/Downloads); `./gradlew :ai:gemma_npu:linkGemmaModels` links them
// into src/main/assets (git-ignored). A Play bundle (bundleRelease) refuses to build while a chip's file is missing —
// that chip's phones would get no model, and their runs couldn't be submitted.
plugins { alias(libs.plugins.android.ai.pack) }

aiPack {
    packName = "gemma_npu"
    dynamicDelivery { deliveryType = "fast-follow" }
}

/** Device group → Google's file for that chip. Repeated in NpuModels.CHIPS (the app) and BenchmarkScoring.cs (the server). */
val gemmaFiles = mapOf(
    "Qualcomm_SM8550" to "Gemma3-1B-IT_q4_ekv1280_sm8550.litertlm",   // Snapdragon 8 Gen 2
    "Qualcomm_SM8650" to "Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm",   // Snapdragon 8 Gen 3
    "Qualcomm_SM8750" to "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm",   // Snapdragon 8 Elite
    "Qualcomm_SM8850" to "Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm",   // Snapdragon 8 Elite Gen 5
    "Mediatek_MT6989" to "Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm",   // Dimensity 9300
    "Mediatek_MT6991" to "Gemma3-1B-IT_q4_ekv1280_mt6991.litertlm",   // Dimensity 9400
    "Mediatek_MT6993" to "Gemma3-1B-IT_q4_ekv1280_mt6993.litertlm")   // Dimensity 9500

val assetsDir: File = layout.projectDirectory.dir("src/main/assets").asFile
/** Where each file sits in the pack: gemma#group_<group>/<file>. */
val packed: Map<String, File> = gemmaFiles.map { (group, name) -> name to File(assetsDir, "gemma#group_$group/$name") }.toMap()
val sourceDir: File = run {
    val props = java.util.Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { props.load(it) }
    File(props.getProperty("gemma.npu.dir") ?: "${System.getProperty("user.home")}/Downloads")
}

tasks.register("linkGemmaModels") {
    description = "Links the downloaded Gemma NPU files into the AI pack's assets (gemma.npu.dir in local.properties)."
    val files = packed
    val from = sourceDir
    doLast {
        val missing = ArrayList<String>()
        for ((name, target) in files) {
            val source = File(from, name)
            if (!source.isFile) { if (!target.exists()) missing += name; continue }
            target.parentFile.mkdirs()
            target.delete()
            java.nio.file.Files.createSymbolicLink(target.toPath(), source.toPath())
        }
        logger.lifecycle(if (missing.isEmpty()) "All ${files.size} Gemma NPU files are in the pack."
            else "Not in $from (their chips get no model): ${missing.joinToString()}")
    }
}

/** For a Play bundle: every chip's file, each a real file of a plausible size (a broken link or a partial download isn't). */
tasks.register("checkGemmaModels") {
    val files = packed
    doLast {
        val bad = files.mapNotNull { (name, target) ->
            val f = target.canonicalFile
            when {
                !f.isFile -> "$name: missing"
                f.length() < 600_000_000L -> "$name: only ${f.length() / 1_000_000} MB"
                else -> null
            }
        }
        check(bad.isEmpty()) {
            "The Gemma NPU AI pack is incomplete — ${bad.joinToString("; ")}. Download them from " +
                "huggingface.co/litert-community/Gemma3-1B-IT and run ./gradlew :ai:gemma_npu:linkGemmaModels."
        }
    }
}
