package com.justboard.bufferrecorder

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat.startActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.justboard.bufferrecorder.components.views.AutoFitTextureView
import com.justboard.bufferrecorder.services.Recorder
import com.justboard.bufferrecorder.ui.theme.BufferRecorderTheme
import com.justboard.bufferrecorder.utils.Encoder
import com.justboard.bufferrecorder.workers.IdleRecordingWorker
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    companion object {
        const val TAG = "MainActivity"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Log.i("MainActivity", "Permission granted")
        else Log.i("MainActivity", "Permission denied")
    }

    private var mHandler: MainHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        mHandler = MainHandler(WeakReference(this))

        super.onCreate(savedInstanceState)
        setContent {
            BufferRecorderTheme {
                SimpleMainGrid(mHandler!!)
            }
        }
    }


    class MainHandler(
        private val mWeakActivity: WeakReference<MainActivity>
    ): Handler(), Encoder.EncoderThread.EncoderCallback {
        companion object {
            const val MSG_FRAME_AVAILABLE = 1
            const val MSG_FILE_SAVE_COMPLETE = 2
            const val MSG_BUFFER_STATUS = 3
        }

        override fun handleMessage(msg: Message) {
            val activity: MainActivity? = mWeakActivity.get()
            if (activity == null) {
                Log.d(TAG, "Got message for dead activity")
                return
            }

            when (msg.what) {
                MSG_FRAME_AVAILABLE -> { Log.d(TAG, "Message received: ${msg.what}")}
                MSG_FILE_SAVE_COMPLETE -> { Log.d(TAG, "Message received: ${msg.what}")}
                MSG_BUFFER_STATUS -> { Log.d(TAG, "Message received: ${msg.what}")}
                else -> { throw RuntimeException("Unknown message: ${msg.what}")}
            }
        }

        override fun fileSaveComplete(status: Int) {
            sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null))
        }

        override fun bufferStatus(totalTimeMsec: Long) {
            sendMessage(obtainMessage(MSG_BUFFER_STATUS, ((totalTimeMsec shr 32).toInt()),
                totalTimeMsec.toInt()
            ))
        }
    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SimpleMainGrid(
    mHandler: MainActivity.MainHandler
) {
    val context = LocalContext.current
    val filesDir = context.filesDir

    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val cameraPermissionState: MultiplePermissionsState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    )

    if (cameraPermissionState.allPermissionsGranted) {
        val mAutoFitTextureView = AutoFitTextureView(context)
        val recorder = Recorder(Dispatchers.IO, windowManager, cameraManager)

        // FIXME Wszystko leci w jednym bieżącym kontekcie - nie powinno tak być.
        LaunchedEffect(Unit) {
            recorder.bindPersistentFileStorage(filesDir)
            recorder.bindTextureView(mAutoFitTextureView)
            recorder.bindCircularBuffer()
            recorder.bindMediaCodec()
            recorder.openCamera()
        }

        Scaffold(
            topBar = {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(recorder.getFrameNumber().collectAsState().value.toString())
                }
            },
            floatingActionButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Absolute.SpaceBetween
                ) {
                    when (recorder.getIsRecording().collectAsState().value) {
                        true -> StateStart(onStop = recorder::stopRecording)
                        false -> StateStop(onStart = recorder::startRecording)
                    }

                    PlayerButton {
                        val intent = Intent(context, PlayerActivity::class.java)
                        startActivity(context, intent, null)
                    }
                }
            }, floatingActionButtonPosition = FabPosition.Center
        ) { innerPadding ->
            AndroidView(
                factory = { mAutoFitTextureView },
//                modifier = Modifier.padding(innerPadding)
//                modifier = Modifier.width(500.dp).height(500.dp).padding(innerPadding)
                modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(innerPadding)
            )
        }
    } else {
        LaunchedEffect(Unit) {
            cameraPermissionState.launchMultiplePermissionRequest()
        }
    }
}

@Composable
fun StateStart(
    onStop: () -> Unit
) {
    Column {
        ExtendedFloatingActionButton(
            text = { Text(Recorder.State.Stop.text) },
            icon = { Icon(Icons.Outlined.Stop, "Stop") },
            onClick = { onStop() }
        )
    }
}


@Composable
fun StateStop(
    onStart: () -> Unit
) {
    Column {
        ExtendedFloatingActionButton(
            text = { Text(Recorder.State.Start.text) },
            icon = { Icon(Icons.Outlined.PlayArrow, "Start") },
            onClick = { onStart() }
        )
    }
}

@Composable
fun PlayerButton(
    onClick: () -> Unit,
) {
    Column {
        ExtendedFloatingActionButton(
            text = { Text(Recorder.State.Start.text) },
            icon = { Icon(Icons.Outlined.PlayArrow, "Start") },
            onClick = { onClick() }
        )
    }
}
