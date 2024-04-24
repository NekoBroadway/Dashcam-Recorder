package com.justboard.bufferrecorder

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.work.WorkManager
import com.justboard.bufferrecorder.ui.theme.BufferRecorderTheme
import java.lang.ref.WeakReference

class PlayerActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BufferRecorderTheme {
                PlayerComponent()
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerComponent() {
    val context = LocalContext.current
    val filesDir = context.filesDir

    val fileList = filesDir.listFiles()
    val videoList = fileList
        ?.filter { video -> video.extension == "mp4" && video.length() > 100 }
        ?.sortedDescending()

    println(videoList)
    println(videoList?.size)

    val player = ExoPlayer.Builder(context).build()
    videoList?.forEach { video ->
        player.addMediaItem(MediaItem.fromUri(video.toUri()))
    }
    player.prepare()
    player.play()

    val playerView = PlayerView(context)
        .apply {
            this.player = player
            this.useController = true
            this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            this.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

    AndroidView(
        factory = { playerView },
        modifier = Modifier.fillMaxSize()
    )
}