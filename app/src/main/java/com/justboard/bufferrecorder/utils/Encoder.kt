package com.justboard.bufferrecorder.utils

import android.media.MediaCodec
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
    private val cb: Encoder.Callback,
) {
    class EncoderThread : Thread() {
        companion object {
            val TAG = "EncoderThread"
            val VERBOSE = false


        }

        private var mEncoderThread: EncoderThread? = null
        private var mInputSurface: Surface? = null
        private var mEncoder: MediaCodec? = null
        private var mEncodedFormat: MediaFormat? = null
        private var mEncBuffer: EncoderBuffer? = null
        private var mHandler: EncoderHandler? = null
        private var mCallback: EncoderCallback? = null

        @Volatile
        private var mReady = false
        private var mLock = Any()

        override fun run() {
            super.run()
        }

        fun frameAvailableSoon() {
            val handler: EncoderHandler = mEncoderThread!!.getHandler()
            handler.sendMessage(handler.obtainMessage(EncoderHandler.MSG_FRAME_AVAILABLE_SOON))
        }

        interface EncoderCallback {
            fun fileSaveComplete(status: Int): Unit
            fun bufferStatus(totalTimeMsec: Int): Unit
        }

        private fun getHandler(): EncoderHandler {
            synchronized(mLock) {
                if (!mReady) {
                    throw RuntimeException("Not ready")
                }
            }
            return mHandler!!
        }

        fun saveVideo(outputFile: File) {
            if (VERBOSE) Log.d(TAG, "saveVideo $outputFile")

            var index: Int = mEncBuffer!!.getFirstIndex()
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
                    val buf: ByteBuffer = mEncBuffer!!.getChunk(index, info)
                    if (VERBOSE) Log.d(TAG, "SAVE ${index} flags=0x${Integer.toHexString(info.flags)}")
                    muxer.writeSampleData(videoTrack, buf, info)
                    index = mEncBuffer!!.getNextIndex(index)
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