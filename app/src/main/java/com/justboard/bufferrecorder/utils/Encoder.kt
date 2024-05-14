package com.justboard.bufferrecorder.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

class Encoder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val frameRate: Int,
    private val desiredSpanSec: Int,
    private val cb: EncoderThread.EncoderCallback,
) {
    companion object {
        const val TAG = "Encoder"
        const val VERBOSE = false

        const val MIME_TYPE = "video/avc"
        const val IFRAME_INTERVAL = 1
    }

    private var mEncoderThread: EncoderThread? = null
    private var mInputSurface: Surface? = null
    private var mEncoder: MediaCodec? = null

    private val mMediaCodecCallback = object: MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            TODO("Not yet implemented")
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            Log.i(TAG, "onOutputBufferAvailable")
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.i(TAG, "onError")
        }

        override fun onCryptoError(codec: MediaCodec, e: MediaCodec.CryptoException) {
            super.onCryptoError(codec, e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "onOutputFormatChanged")
        }
    }

    init {
        if (desiredSpanSec < IFRAME_INTERVAL * 2)
            throw RuntimeException("Requested time span is too short: $desiredSpanSec vs. ${IFRAME_INTERVAL * 2}")

        val circularBuffer = CircularBuffer(bitRate, frameRate, desiredSpanSec)

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        format.apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
        }
        if (VERBOSE) Log.d(TAG, "format: $format")

        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        mEncoder!!.setCallback(mMediaCodecCallback)
        mEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mInputSurface = mEncoder!!.createInputSurface()
        mEncoder!!.start()

        mEncoderThread = EncoderThread(mEncoder!!, circularBuffer, cb)
        mEncoderThread!!.start()
        mEncoderThread!!.waitUntilReady()
    }

    fun getInputSurface(): Surface = mInputSurface!!

    fun shutdown() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects")

        val handler = mEncoderThread!!.getHandler()
        handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN))

        try {
            mEncoderThread!!.join()
        } catch (ie: InterruptedException) {
            Log.w(TAG, "Encoder thread join() was interrupted", ie)
        }

        if (mEncoder != null) {
            mEncoder!!.stop()
            mEncoder!!.release()
            mEncoder = null
        }
    }

    fun frameAvailableSoon() {
        val handler: EncoderThread.EncoderHandler = mEncoderThread!!.getHandler()
        handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_FRAME_AVAILABLE_SOON))
    }

    fun saveVideo(outputFile: File) {
        val handler: EncoderThread.EncoderHandler = mEncoderThread!!.getHandler()
        handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SAVE_VIDEO))
    }

    class EncoderThread(
        private val mEncoder: MediaCodec,
        private val mCircularBuffer: CircularBuffer,
        private val mCallback: EncoderCallback
    ) : Thread() {
        companion object {
            val TAG = "EncoderThread"
            val VERBOSE = false
        }

        interface EncoderCallback {
            fun fileSaveComplete(status: Int): Unit
            fun bufferStatus(totalTimeMsec: Long): Unit
        }

        private var mEncodedFormat: MediaFormat? = null
        private var mHandler: EncoderHandler? = null
        private var mBufferInfo: MediaCodec.BufferInfo? = null

        @Volatile
        private var mReady = false
        private var mLock = Object()
        private var mFrameNum: Int = 0

        init {
            mBufferInfo = MediaCodec.BufferInfo()
        }

        override fun run() {
            super.run()
        }

        fun waitUntilReady() {
            synchronized(mLock) {
                while (!mReady) {
                    try {
                        mLock.wait()
                    } catch (ie: InterruptedException) { /* TODO */ }
                }
            }
        }

        fun getHandler(): EncoderHandler {
            synchronized(mLock) {
                if (!mReady) throw RuntimeException("Not ready")
            }
            return mHandler!!
        }

        fun drainEncoder() {
            val TIMEOUT_USEC: Long = 0

            var encoderOutputBuffers = mEncoder.outputBuffers

            while (true) {
                var encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo!!, TIMEOUT_USEC)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = mEncoder.outputBuffers
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    mEncodedFormat = mEncoder.outputFormat
                    Log.d(TAG, "encoder output format changed: $mEncodedFormat")
                } else if (encoderStatus < 0) {
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")
                } else {
                    var encodedData = encoderOutputBuffers[encoderStatus]
                    if (encodedData == null) {
                        throw RuntimeException("encoderOutputBuffer $encoderStatus was null")
                    }

                    if ((mBufferInfo!!.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG")
                        mBufferInfo!!.size = 0
                    }

                    if (mBufferInfo!!.size != 0) {
                        encodedData.position(mBufferInfo!!.offset)
                        encodedData.limit(mBufferInfo!!.offset + mBufferInfo!!.size)

                        mCircularBuffer.add(encodedData, mBufferInfo!!.flags, mBufferInfo!!.presentationTimeUs)

                        if (VERBOSE) Log.d(TAG, "sent ${mBufferInfo!!.size} bytes to muxer, ts=${mBufferInfo!!.presentationTimeUs}")
                    }

                    mEncoder.releaseOutputBuffer(encoderStatus, false)

                    if ((mBufferInfo!!.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.w(TAG, "reached end of stream unexpectedly")
                        break      // out of while
                    }
                }
            }
        }

        fun frameAvailableSoon() {
            if (VERBOSE) Log.d(TAG, "frameAvailableSoon")
            drainEncoder()

            mFrameNum++
            if ((mFrameNum % 10) == 0) {        // TODO: should base off frame rate or clock?
                mCallback.bufferStatus(mCircularBuffer.computeTimeSpanUsec());
            }
        }

        fun saveVideo(outputFile: File) {
            if (VERBOSE) Log.d(TAG, "saveVideo $outputFile")

            var index: Int = mCircularBuffer!!.getFirstIndex()
            if (index < 0) {
                Log.w(TAG, "Unable to get first index")
                mCallback!!.fileSaveComplete(1)
                return
            }

            val info = MediaCodec.BufferInfo()
            var muxer: MediaMuxer? = null
            var result = -1
            try {
                muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val videoTrack = muxer.addTrack(mEncodedFormat!!)
                muxer.start()

                do {
                    val buf: ByteBuffer = mCircularBuffer!!.getChunk(index, info)
                    if (VERBOSE) Log.d(TAG, "SAVE ${index} flags=0x${Integer.toHexString(info.flags)}")
                    muxer.writeSampleData(videoTrack, buf, info)
                    index = mCircularBuffer!!.getNextIndex(index)
                } while (index >= 0)

                result = 0
            } catch (ioe: IOException) {
                Log.w(TAG, "muxer failed", ioe)
                result = 2
            } finally {
                if (muxer != null) {
                    muxer.stop()
                    muxer.release()
                }
            }

            if (VERBOSE) Log.d(TAG, "muxer stopped, result=$result")
            mCallback!!.fileSaveComplete(result)
        }

        fun shutdown() {
            if (VERBOSE) Log.d(TAG, "shutdown")
            Looper.myLooper()!!.quit()
        }

        class EncoderHandler(
            private val et: EncoderThread
        ) : Handler() {
            companion object {
                const val MSG_FRAME_AVAILABLE_SOON = 1
                const val MSG_SAVE_VIDEO = 2
                const val MSG_SHUTDOWN = 3
            }

            // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
            // but no real harm in it.
            private val mWeakEncoderThread: WeakReference<EncoderThread> = WeakReference(et)

            // runs on encoder thread
            override fun handleMessage(msg: Message) {
                val what = msg.what
                if (VERBOSE) {
                    Log.v(TAG, "EncoderHandler: what=$what")
                }
                val encoderThread: EncoderThread? = mWeakEncoderThread.get()
                if (encoderThread == null) {
                    Log.w(TAG, "EncoderHandler.handleMessage: weak ref is null")
                    return
                }
                when (what) {
                    MSG_FRAME_AVAILABLE_SOON -> encoderThread.frameAvailableSoon()
                    MSG_SAVE_VIDEO -> encoderThread.saveVideo(msg.obj as File)
                    MSG_SHUTDOWN -> encoderThread.shutdown()
                    else -> throw RuntimeException("unknown message $what")
                }
            }
        }
    }


    class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            TODO("Not yet implemented")
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            TODO("Not yet implemented")
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            TODO("Not yet implemented")
        }

        override fun onCryptoError(codec: MediaCodec, e: MediaCodec.CryptoException) {
            super.onCryptoError(codec, e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            TODO("Not yet implemented")
        }
    }
}