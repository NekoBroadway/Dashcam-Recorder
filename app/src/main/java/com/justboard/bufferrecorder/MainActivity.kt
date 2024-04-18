package com.justboard.bufferrecorder

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.ImageReader
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.justboard.bufferrecorder.components.views.AutoFitTextureView
import com.justboard.bufferrecorder.services.Recorder
import com.justboard.bufferrecorder.services.Recorder.Companion.IMAGE_FORMAT
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
    private lateinit var workManager: WorkManager


    override fun onCreate(savedInstanceState: Bundle?) {
        workManager = WorkManager.getInstance(applicationContext)
        mHandler = MainHandler(WeakReference(this))

        super.onCreate(savedInstanceState)
        setContent {
            BufferRecorderTheme {
                SimpleMainGrid(
                    onStartIdle = { startRecording("Idle") },
                    onStopIdle = { stopRecording("Idle") },
                    onStartActive = { startRecording("Active") },
                    onStopActive = { stopRecording("Active") },
                )
            }
        }
    }

    private fun startRecording(workTag: String) {
        val workName = "Recorder"
//        val workData = workDataOf("RECORD_ID" to workerTag)
        val workPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

        val uniqueOneTimeWorkRequest: OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<IdleRecordingWorker>()
//            .setInputData(workData)
                .addTag(workTag)
                .build()
        workManager.enqueueUniqueWork(workName, workPolicy, uniqueOneTimeWorkRequest)
    }

    private fun stopRecording(workTag: String) {
        workManager.cancelUniqueWork(workTag)
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

        override fun bufferStatus(totalTimeMsec: Int) {
            sendMessage(obtainMessage(MSG_BUFFER_STATUS, (totalTimeMsec shr 32), totalTimeMsec))
        }
    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SimpleMainGrid(
    onStartIdle: () -> Unit = {},
    onStopIdle: () -> Unit = {},
    onStartActive: () -> Unit = {},
    onStopActive: () -> Unit = {},
) {
    val context = LocalContext.current
    val filesDir = context.filesDir.apply {
        val tmpDir = File(this, "tmp")
        if (!tmpDir.exists()) {
            tmpDir.mkdir()
        }
    }
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val cameraPermissionState: MultiplePermissionsState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    )

    if (cameraPermissionState.allPermissionsGranted) {
//        val imageReader = ImageReader.newInstance(4032, 3024, ImageFormat.PRIVATE, 30)
//        val imageReader = ImageReader.newInstance(1920, 1080, IMAGE_FORMAT, 30)

        val imageReader = ImageReader.newInstance(1920, 1080, IMAGE_FORMAT, 30)

        val mAutoFitTextureView = AutoFitTextureView(context)
        val recorder = Recorder(Dispatchers.IO, windowManager, cameraManager)
//        val encoder = Encoder()
//        val muxer = MediaMuxer()

        // FIXME Wszystko leci w jednym bieżącym kontekcie - nie powinno tak być.
        LaunchedEffect(Unit) {
            recorder.bindImageReader(imageReader)
            recorder.bindPersistentFileStorage(filesDir)
            recorder.bindTextureView(mAutoFitTextureView)
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
                        true -> {
                            StateStart(onStop = recorder::stopRecording)
                        }

                        false -> {
                            StateStop(onStart = recorder::startRecording)
                        }
                    }
                }
            }, floatingActionButtonPosition = FabPosition.Center
        ) { innerPadding ->
            AndroidView(
                factory = { mAutoFitTextureView },
                modifier = Modifier.padding(innerPadding)
//                modifier = Modifier.width(500.dp).height(500.dp).padding(innerPadding)
//                modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(innerPadding)
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

@Preview
@Composable
fun SimpleMainGridPreview() {
    SimpleMainGrid()
}