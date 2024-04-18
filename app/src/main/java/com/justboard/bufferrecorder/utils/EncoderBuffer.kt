package com.justboard.bufferrecorder.utils

import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer

class EncoderBuffer(
    private val bitRate: Int,
    private val frameRate: Int,
    private val desiredSpanSec: Int
) {
    companion object {
        const val TAG = "EncoderBuffer"
        const val EXTRA_DEBUG = true
        const val VERBOSE = false

        const val MIME_TYPE = "video/avc"
        const val IFRAME_INTERVAL = 1
    }

    private var mDataBufferWrapper: ByteBuffer
    private var mDataBuffer: ByteArray

    private var mPacketFlags: IntArray
    private var mPacketPtsUsec: LongArray
    private var mPacketStart: IntArray
    private var mPacketLength: IntArray

    private var mMetaHead: Int = 0
    private var mMetaTail: Int = 0

    init {
        val dataBufferSize = bitRate * desiredSpanSec / 8;
        mDataBuffer = ByteArray(dataBufferSize)
        mDataBufferWrapper = ByteBuffer.wrap(mDataBuffer)

        val metaBufferCount: Int = frameRate * desiredSpanSec * 2
        mPacketFlags = IntArray(metaBufferCount)
        mPacketPtsUsec = LongArray(metaBufferCount)
        mPacketStart = IntArray(metaBufferCount)
        mPacketLength = IntArray(metaBufferCount)

        if (VERBOSE) Log.d(TAG, """
            CBE: bitRate=$bitRate frameRate=$frameRate 
            desiredSpan=$desiredSpanSec: dataBufferSize=$dataBufferSize 
            metaBufferCount=$metaBufferCount
        """.trimIndent())
    }

    fun computeTimeSpanUsec(): Long {
        val metaLen = mPacketStart.size
        if (metaLen == mMetaTail) return 0

        val beforeHead = (mMetaHead + metaLen - 1) % metaLen
        return mPacketPtsUsec[beforeHead] - mPacketPtsUsec[mMetaTail]
    }

    fun add(buf: ByteBuffer, flags: Int, ptsUsec: Long): Unit {
        val size = buf.limit() - buf.position()
        if (VERBOSE) Log.d(TAG, "add size=$size flags=0x ${Integer.toHexString(flags)} pts=$ptsUsec")

        while (!canAdd(size)) removeTail()

        val dataLen = mDataBuffer.size
        val metaLen = mPacketStart.size
        val packetStart = getHeadStart()
        mPacketFlags[mMetaHead] = flags
        mPacketPtsUsec[mMetaHead] = ptsUsec
        mPacketStart[mMetaHead] = packetStart
        mPacketLength[mMetaHead] = size

        if (packetStart + size < dataLen) {
            buf.get(mDataBuffer, packetStart, size)
        } else {
            val firstSize = dataLen - packetStart
            if (VERBOSE) Log.v(TAG, "split, firstsize=$firstSize size=$size")

            buf.get(mDataBuffer, packetStart, firstSize)
            buf.get(mDataBuffer, 0, size - firstSize)
        }

        mMetaHead = (mMetaHead + 1) % metaLen

        if (EXTRA_DEBUG) {
            mPacketFlags[mMetaHead] = 0x77aaccff
            mPacketPtsUsec[mMetaHead] = -1000000000L
            mPacketStart[mMetaHead] = -100000
            mPacketLength[mMetaHead] = Int.MAX_VALUE
        }
    }

    fun getFirstIndex(): Int {
        val metaLen = mPacketStart.size

        var index = mMetaTail
        while (index != mMetaHead) {
            if (mPacketFlags[index] and MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0) {
                break
            }
            index = (index + 1) % metaLen
        }

        if (index == mMetaHead) {
            Log.w(TAG, "HEY: could not find sync frame in buffer")
            index = -1
        }
        return index
    }

    fun getNextIndex(index: Int): Int {
        val metaLen = mPacketStart.size
        var next = (index + 1) % metaLen
        if (next == mMetaHead) {
            next = -1
        }
        return next
    }

    fun getChunk(index: Int, info: MediaCodec.BufferInfo): ByteBuffer {
        val dataLen = mDataBuffer.size
        var packetStart = mPacketStart[index]
        var length = mPacketLength[index]

        info.flags = mPacketFlags[index]
        info.offset = packetStart
        info.presentationTimeUs = mPacketPtsUsec[index]
        info.size = length

        if (packetStart + length <= dataLen) {
            return mDataBufferWrapper
        } else {
            val tempBuf = ByteBuffer.allocateDirect(length)
            var firstSize = dataLen - packetStart

            tempBuf.put(mDataBuffer, mPacketStart[index], firstSize)
            tempBuf.put(mDataBuffer, 0, length - firstSize)

            info.offset = 0

            return tempBuf
        }
    }

    private fun getHeadStart(): Int {
        if (mMetaHead == mMetaTail) return 0

        val dataLen = mDataBuffer.size
        val metaLen = mPacketStart.size
        val beforeHead = (mMetaHead + metaLen - 1) % metaLen

        return (mPacketStart[beforeHead] + mPacketLength[beforeHead] + 1) % dataLen
    }

    private fun canAdd(size: Int): Boolean {
        val dataLen = mDataBuffer.size
        val metaLen = mPacketStart.size

        if (size > dataLen) throw RuntimeException("Enormous packet: $size vs. buffer $dataLen")
        if (mMetaHead == mMetaTail) return true

        val nextHead = (mMetaHead + 1) % metaLen
        if (nextHead == mMetaTail) {
            if (VERBOSE) Log.v(TAG, "ran out of metadata (head=$mMetaHead tail=$mMetaTail)")
            return false
        }

        val headStart = getHeadStart()
        val tailStart = mPacketStart[mMetaTail]
        val freeSpace = (tailStart + dataLen - headStart) % dataLen
        if (size > freeSpace) {
            if (VERBOSE) Log.v(TAG, "ran out of metadata (tailStart=$tailStart headStart=$headStart)")
            return false
        }

        if (VERBOSE) Log.v(TAG, "OK: size=$size free=$freeSpace metaFree=${(mMetaTail + metaLen - mMetaHead) % metaLen - 1})")
        return true
    }

    private fun removeTail(): Unit {
        if (mMetaHead == mMetaTail) throw RuntimeException("Can't removeTail() in empty buffer")

        val metaLen = mPacketStart.size
        mMetaTail = (mMetaTail + 1) % metaLen
    }
}