package com.justboard.buildsrc

import org.gradle.api.artifacts.dsl.DependencyHandler

object Deps {
    object Compose {
        object Animation {
            val android = "androidx.compose.animation:animation:${Versions.Compose.core}"
            val core = "androidx.compose.animation.animation-core:${Versions.Compose.core}"
        }
        object Foundation {
            val android = "androidx.compose.foundation:foundation:${Versions.Compose.core}"
            val layout = "androidx.compose.foundation:foundation-layout:${Versions.Compose.core}"
        }

        object Material {
            val android = "androidx.compose.material:material:${Versions.Compose.core}"
            val iconsCore = "androidx.compose.material:material-icons-core:${Versions.Compose.core}"
            val iconsExtended = "androidx.compose.material:material-icons-extended:${Versions.Compose.core}"
            val ripple = "androidx.compose.material:material-ripple:${Versions.Compose.core}"
        }

        object Material3 {
            val android = "androidx.compose.material3:material3:${Versions.Compose.material3}"
            val windowSizeClass = "androidx.compose.material3:material3-window-size-class:${Versions.Compose.material3}"
        }

        object Runtime {
            val android = "androidx.compose.runtime:runtime:${Versions.Compose.core}"
        }

        object UI {
            val android = "androidx.compose.ui:ui:${Versions.Compose.core}"
            val geometry = "androidx.compose.ui:ui-geometry:${Versions.Compose.core}"
            val graphics = "androidx.compose.ui:ui-graphics:${Versions.Compose.core}"
            val test = "androidx.compose.ui:ui-test:${Versions.Compose.core}"
            val testJunit4 = "androidx.compose.ui:ui-test-junit4:${Versions.Compose.core}"
            val testManifest = "androidx.compose.ui:ui-test-manifest:${Versions.Compose.core}"
            val text = "androidx.compose.ui:ui-text:${Versions.Compose.core}"
            val textGoogleFonts = "androidx.compose.ui:ui-text-google-fonts:${Versions.Compose.core}"
            val tooling = "androidx.compose.ui:ui-tooling:${Versions.Compose.core}"
            val toolingData = "androidx.compose.ui:ui-tooling-data:${Versions.Compose.core}"
            val toolingPreview = "androidx.compose.ui:ui-tooling-preview:${Versions.Compose.core}"
            val unit = "androidx.compose.ui:ui-unit:${Versions.Compose.core}"
            val util = "androidx.compose.ui:ui-util:${Versions.Compose.core}"
            val viewbinding = "androidx.compose.ui:ui-viewbinding:${Versions.Compose.core}"
        }
    }

    object CameraX {
        val core = "androidx.camera:camera-core:${Versions.CameraX.core}"
        val camera2 = "androidx.camera:camera-camera2:${Versions.CameraX.core}"
        val lifecycle = "androidx.camera:camera-lifecycle:${Versions.CameraX.core}"
        val video = "androidx.camera:camera-video:${Versions.CameraX.core}"
        val view = "androidx.camera:camera-view:${Versions.CameraX.core}"
        val extensions = "androidx.camera:camera-extensions:${Versions.CameraX.core}"
    }

    object Camera2 {

    }

    object ExoPlayer {
        val core = "androidx.media3:media3-exoplayer:${Versions.ExoPlayer.core}"
        val dash = "androidx.media3:media3-exoplayer-dash:${Versions.ExoPlayer.core}"
        val ui = "androidx.media3:media3-ui:${Versions.ExoPlayer.core}"
    }

    object WorkManager {
        val runtime = "androidx.work:work-runtime-ktx:${Versions.WorkManager.core}"
        val multiprocess = "androidx.work:work-multiprocess:${Versions.WorkManager.core}"
        val testing = "androidx.work:work-testing:${Versions.WorkManager.core}"
    }
}

fun DependencyHandler.compose() {
    implementation(Deps.Compose.Runtime.android)
    implementation(Deps.Compose.Foundation.android)
    implementation(Deps.Compose.Foundation.layout)

    implementation(Deps.Compose.UI.android)
    implementation(Deps.Compose.UI.graphics)
    implementation(Deps.Compose.UI.toolingPreview)
    implementation(Deps.Compose.Material.android)
    implementation(Deps.Compose.Material.iconsCore)
    implementation(Deps.Compose.Material.iconsExtended)
    implementation(Deps.Compose.Material3.android)

    androidTestImplementation(Deps.Compose.UI.testJunit4)
    debugImplementation(Deps.Compose.UI.tooling)
    debugImplementation(Deps.Compose.UI.testManifest)
}

fun DependencyHandler.camera2() {
}

fun DependencyHandler.camerax() {
    implementation(Deps.CameraX.core)
    implementation(Deps.CameraX.camera2)
    implementation(Deps.CameraX.video)
    implementation(Deps.CameraX.view)
    implementation(Deps.CameraX.extensions)
}

fun DependencyHandler.exoPlayer() {
    implementation(Deps.ExoPlayer.core)
    implementation(Deps.ExoPlayer.dash)
    implementation(Deps.ExoPlayer.ui)
}

fun DependencyHandler.workManager() {
    implementation(Deps.WorkManager.runtime)
    implementation(Deps.WorkManager.multiprocess)
    androidTestImplementation(Deps.WorkManager.testing)
}