pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ThermoHammer"
include(":app")
// NPU runtimes, delivered by Play only to the Snapdragons and Dimensitys that use them (npu/build.gradle.kts)
include(":npu:qnn_htp", ":npu:qnn_v68", ":npu:qnn_v69", ":npu:qnn_v73", ":npu:qnn_v75", ":npu:qnn_v79", ":npu:qnn_v81",
    ":npu:mtk_v8", ":npu:mtk_v9")
