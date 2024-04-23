package com.justboard.bufferrecorder.services

import android.annotation.SuppressLint
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import com.justboard.bufferrecorder.components.views.AutoFitTextureView
import com.justboard.bufferrecorder.utils.CircularBuffer
import com.justboard.bufferrecorder.utils.Encoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Recorder(
    private val ioDispatcher: CoroutineDispatcher,
    private val mWindowManager: WindowManager,
    private val mCameraManager: CameraManager
) : CameraDevice.StateCallback() {
    companion object {
        val TAG = "Recorder"
        val CAMERA_TAG = "Recorder:Camera"

        val VIDEO_WIDTH = 1920
        val VIDEO_HEIGHT = 1080
        val FRAME_RATE = 30
        val BIT_RATE = 6_000_000
        val MIME_TYPE = "video/avc"

        val SPAN_SEC = 30

        // 720p:
        //      30fps -> 6_000_000
        //      60fps -> 10_000_000
        //
        // 1080p:
        //      30fps -> 10_000_000
        //      60fps -> 15_000_000
        //
        // 1440p:
        //      30fps -> 20_000_000
        //      60fps -> 30_000_000
        //
        // 2160p:
        //      30fps -> 44_000_000 - 56_000_000
        //      60fps -> 66_000_000 - 85_000_000
    }

    private var mPersistentFileStorage: File? = null

    private var mCameraDevice: CameraDevice? = null  // TODO A czy jest ta zmienna potrzebna?
    private var mCaptureRequest: CaptureRequest.Builder? = null
    private var mSensorOrientation: Int? = null
    private var mDisplayRotation: Int? = null

    private var mTextureView: AutoFitTextureView? = null    // The TextureView
    private var mTextureViewSurface: Surface? = null        // The TextureView Surface

    private var mMediaCodec: MediaCodec? = null             // The ImageReader
    private var mMediaCodecSurface: Surface? = null         // The ImageReader Surface

    private var mMediaMuxer: MediaMuxer? = null             // The MediaMuxer
    private var mVideoTrackIndex: Int? = null               // The Video track index

    private var mCircularBuffer: CircularBuffer? = null


    private var _mIsRecording: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private var _mFrameNumber: MutableStateFlow<Int> = MutableStateFlow(0)

    fun getIsRecording() = _mIsRecording.asStateFlow()
    fun getFrameNumber() = _mFrameNumber.asStateFlow()

    fun startRecording() {
        _mIsRecording.value = true
    }

    fun stopRecording() {
        Log.e(TAG, "Is stopped.")

        _mIsRecording.value = false
        mMediaMuxer!!.stop()
        mMediaMuxer!!.release()
        mMediaMuxer = null
    }

    fun bindPersistentFileStorage(persistentFileStorage: File) {
        mPersistentFileStorage = persistentFileStorage
    }

    fun bindCameraDevice(cameraDevice: CameraDevice) {
        mCameraDevice = cameraDevice
    }

    fun bindTextureView(textureView: AutoFitTextureView) {
        mTextureView = textureView
        mTextureView!!.surfaceTextureListener = mTextureView
        mTextureView!!.surfaceTexture!!.apply {
            setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            mTextureViewSurface = Surface(this)
        }
    }


    private val mMediaCodecCallback = object: MediaCodec.Callback() {
        val TAG = "MediaCodecCallback"
        val VERBOSE = true

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            Log.i(TAG, "onInputBufferAvailable")
            codec.getInputBuffer(index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            Log.i(TAG, "onOutputBufferAvailable")

            if (mMediaMuxer == null) {
                Log.w(TAG, "The muxer is null")
                bindMuxer()
            }

            val encodedData = codec.getOutputBuffer(index)

            if (encodedData == null) {
                Log.e(TAG, "No data.")
                codec.releaseOutputBuffer(index, false)
                return
            }

            if (_mIsRecording.value) {
                if (mCircularBuffer!!.canRead) {
                    var mCircularBufferIndex = mCircularBuffer!!.getFirstIndex()
                    val mCircularBufferInfo = MediaCodec.BufferInfo()
                    do {
                        val chunk = mCircularBuffer!!.getChunk(mCircularBufferIndex, mCircularBufferInfo)
                        if (VERBOSE) Log.d(TAG, "SAVE $mCircularBufferIndex flags=0x${Integer.toHexString(mCircularBufferInfo.flags)}")
                        mMediaMuxer!!.writeSampleData(mVideoTrackIndex!!, chunk, mCircularBufferInfo)
                        mCircularBufferIndex = mCircularBuffer!!.getNextIndex(mCircularBufferIndex)
                    } while (mCircularBufferIndex >= 0)
                    mCircularBuffer!!.canRead = false
                }
                println("Is recording: ${_mIsRecording.value}")
                mMediaMuxer!!.writeSampleData(mVideoTrackIndex!!, encodedData, info)
            } else {
                mCircularBuffer!!.add(encodedData, info.flags, info.presentationTimeUs)
            }

            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.i(Encoder.TAG, "onError")
        }

        override fun onCryptoError(codec: MediaCodec, e: MediaCodec.CryptoException) {
            super.onCryptoError(codec, e)
            Log.i(Encoder.TAG, "onCryptoError")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(Encoder.TAG, "onOutputFormatChanged")
        }
    }

    fun bindMediaCodec() {
        val mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
        val mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT)
        mediaFormat.apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Encoder.IFRAME_INTERVAL)
        }

        mMediaCodec = mediaCodec
        mMediaCodec!!.setCallback(mMediaCodecCallback)  // TODO pass handler for multi-threading.
        mMediaCodec!!.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mMediaCodecSurface = MediaCodec.createPersistentInputSurface()
        mMediaCodec!!.setInputSurface(mMediaCodecSurface!!)
        mMediaCodec!!.start()
    }

    fun bindMuxer() {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val current = LocalDateTime.now().format(formatter)
        val outputFile = File(mPersistentFileStorage, "${current}.mp4")

        mMediaMuxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val videoFormat = mMediaCodec!!.outputFormat
        mVideoTrackIndex = mMediaMuxer!!.addTrack(videoFormat)
        mMediaMuxer!!.setOrientationHint(90)

        mMediaMuxer!!.start()
    }

    fun bindCircularBuffer() {
        // TODO Bitrate depends on frameRate and video size.
        // TODO Would be good to depend on CameraProfile classes.
        mCircularBuffer = CircularBuffer(BIT_RATE, FRAME_RATE, SPAN_SEC)
    }

    private val mCaptureSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            session.setRepeatingRequest(
                mCaptureRequest!!.build(),
                mCaptureSessionListener,
                Handler()
            )
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            session.stopRepeating()
        }
    }

    private val mCaptureSessionListener = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            Log.i(TAG_ON_CAPTURE_COMPLETED, result.frameNumber.toString())
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera() {
        if (mTextureView == null) {
            Log.w(TAG, "The camera have no texture view bind.")
            return
        }
        val cameraId = mCameraManager.cameraIdList[0] // Usually the back.
        mCameraManager.openCamera(cameraId, this, Handler())
    }

    override fun onOpened(cameraDevice: CameraDevice) {
        bindCameraDevice(cameraDevice)

        val cameraCharacteristics = mCameraManager.getCameraCharacteristics(cameraDevice.id)
        val size: Size =
            cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) as Size
        mSensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        mDisplayRotation = when (mWindowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            else -> 270
        }
        val map: StreamConfigurationMap =
            cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview/video sizes")

        val width: Int?
        val height: Int?
        val cropRect: Rect?
        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
            width = size.width
            height = size.height
        } else {
            width = size.height
            height = size.width
        }
        cropRect = Rect(0, 0, width, height)    // 3024x4032 or 4032x3024
        mTextureView!!.setAspectRatio(width, height)
        mTextureView!!.configureTransform(width, height)

        Log.w(CAMERA_TAG, "The sensor orientation: ${mSensorOrientation.toString()}")
        Log.w(CAMERA_TAG, "The display rotation: ${mDisplayRotation.toString()}")
        Log.w(
            CAMERA_TAG,
            "The rotation (calculated): ${(mSensorOrientation!! - mDisplayRotation!! * -1 + 360) % 360}"
        )

        mCaptureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        mCaptureRequest!!.apply {
            addTarget(mTextureViewSurface!!)
            addTarget(mMediaCodecSurface!!)
            set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        }

        cameraDevice.createCaptureSession(
            listOf(
                mTextureViewSurface!!,
                mMediaCodecSurface!!,
            ),
            mCaptureSessionCallback,
            Handler()
        )
    }

    override fun onClosed(camera: CameraDevice) {
        super.onClosed(camera)
        Log.i(CAMERA_TAG, "onClosed")
        mTextureViewSurface?.release()
    }

    override fun onDisconnected(camera: CameraDevice) {
        Log.i(CAMERA_TAG, "onDisconnected")
    }

    override fun onError(camera: CameraDevice, error: Int) {
        Log.i(CAMERA_TAG, "onError")
        Log.e(CAMERA_TAG, error.toString())
    }

    public sealed class State(
        val text: String,
        val icon: @Composable () -> Unit,
    ) {
        object Start : State(
            "Start",
            { Icon(Icons.Outlined.PlayArrow, "Start") }
        )

        object Pause : State(
            "Pause",
            { Icon(Icons.Outlined.Videocam, "Pause") },
        )

        object Resume : State(
            "Resume",
            { Icon(Icons.Outlined.PlayArrow, "Resume") },
        )

        object Stop : State(
            "Stop",
            { Icon(Icons.Outlined.Stop, "Stop") },
        )
    }
}