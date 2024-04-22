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
) : CameraDevice.StateCallback(), ImageReader.OnImageAvailableListener {
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

    private var mImageReader: ImageReader? = null           // The ImageReader
    private var mImageReaderSurface: Surface? = null        // The ImageReader Surface


    private var _mIsRecording: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private var _mFrameNumber: MutableStateFlow<Int> = MutableStateFlow(0)

    fun getIsRecording() = _mIsRecording.asStateFlow()
    fun getFrameNumber() = _mFrameNumber.asStateFlow()

    fun startRecording() {
        _mIsRecording.value = true
    }

    fun stopRecording() {
        _mIsRecording.value = false
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
            setDefaultBufferSize(4032, 3024)
            mTextureViewSurface = Surface(this)
        }
    }

//    fun bindEncoderSurface() {}

    fun bindImageReader(imageReader: ImageReader) {
        mImageReader = imageReader
        mImageReader!!.setOnImageAvailableListener(this, Handler())
        mImageReaderSurface = imageReader.surface
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
        val TAG_ON_CAPTURE_STARTED = "onCaptureStarted"
        val TAG_ON_CAPTURE_PROGRESSED = "onCaptureProgressed"
        val TAG_ON_CAPTURE_COMPLETED = "onCaptureCompleted"

        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)
            Log.i(TAG_ON_CAPTURE_STARTED, "Capture has started.")
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            super.onCaptureProgressed(session, request, partialResult)
            Log.i(
                TAG_ON_CAPTURE_PROGRESSED,
                (partialResult.get(CaptureResult.JPEG_THUMBNAIL_SIZE) ?: "None").toString()
            )
        }

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
        /**/

        if (mImageReader != null) {
            mImageReaderSurface = mImageReader!!.surface
            Log.i(CAMERA_TAG, "The ImageReader is not null")
        }

        mCaptureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        mCaptureRequest!!.apply {
            addTarget(mTextureViewSurface!!)
            addTarget(mImageReaderSurface!!)
            set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        }

        cameraDevice.createCaptureSession(
            listOf(
                mTextureViewSurface!!,
                mImageReaderSurface!!,
            ),
            mCaptureSessionCallback,
            Handler()
        )
    }

    override fun onClosed(camera: CameraDevice) {
        super.onClosed(camera)
        Log.i(CAMERA_TAG, "onClosed")
        mTextureViewSurface?.release()
        mImageReaderSurface?.release()
    }

    override fun onDisconnected(camera: CameraDevice) {
        Log.i(CAMERA_TAG, "onDisconnected")
    }

    override fun onError(camera: CameraDevice, error: Int) {
        Log.i(CAMERA_TAG, "onError")
        Log.e(CAMERA_TAG, error.toString())
    }

    override fun onImageAvailable(reader: ImageReader?) {
        Log.i(IMAGE_READER_TAG, "onImageAvailable")

        if (reader == null) {
            Log.w(IMAGE_READER_TAG, "The reader is not initialized.")
            return
        }

        val image: Image? = reader.acquireLatestImage()

        if (image == null) {
            Log.w(IMAGE_READER_TAG, "The image is null")
            return
        }

        if (!_mIsRecording.value) {
            Log.w(IMAGE_READER_TAG, "The recording is not enabled. Skipping.")
            image.close()
            return
        }

        Log.i(IMAGE_READER_TAG, image.width.toString())
        Log.i(IMAGE_READER_TAG, image.height.toString())

        /**/
        Log.i(IMAGE_READER_TAG, "Image format: ${image.format}.")

        when (image.format) {
            ImageFormat.YUV_420_888 -> {
                val meta: ByteBuffer = ByteBuffer.allocate(0)
                val Y: Image.Plane = image.planes[0]
                val U: Image.Plane = image.planes[1]
                val V: Image.Plane = image.planes[2]

                val metaB: Int = meta.remaining()
                val Yb: Int = Y.buffer.remaining()
                val Ub: Int = U.buffer.remaining()
                val Vb: Int = V.buffer.remaining()

                val imageByteArray = ByteArray(metaB + Yb + Ub + Vb)

                meta.get(imageByteArray, 0, metaB)
                Y.buffer.get(imageByteArray, metaB, Yb)
                U.buffer.get(imageByteArray, metaB + Yb, Ub)
                V.buffer.get(imageByteArray, metaB + Yb + Ub, Vb)

                handleYuvData(imageByteArray)
                image.close()
            }

            else -> {
                Log.w(IMAGE_READER_TAG, "Unhandled image format.")
            }
        }
        /**/
    }

    private var mFos: FileOutputStream? = null
    private fun handleYuvData(imageByteArray: ByteArray) {
        Log.i(IMAGE_READER_TAG, "Processing YUV_420_888 data format.")

        val outputFile = File(
            mPersistentFileStorage!!,
            "tmp/${String.format("%05d", _mFrameNumber.value++)}.yuv"
        )
//        val outputFile = File(mPersistentFileStorage!!, "tmp/bulk.yuv")

        if (!outputFile.exists()) {
            outputFile.createNewFile()
        }

        /*1. Zapis do wielu plików. Dla jednej klatki - jeden plik i jeden stream. */
        val fos = outputFile.outputStream()
        fos.write(imageByteArray)
        fos.close()
        /*1*/

//        /*2. Zapis do jednego pliku. */
//        if (mFos == null) {
//            mFos = outputFile.outputStream()
//        }
//
//        if (_mIsRecording.value) {
//            mFos!!.write(imageByteArray)
//            _mFrameNumber.value++
//        } else {
//            mFos!!.close()
//        }
//        /*2*/

        Log.i(IMAGE_READER_TAG, "Done. Closing image.")
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