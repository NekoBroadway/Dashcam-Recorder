package com.justboard.bufferrecorder.components.views

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import java.util.Collections

class AutoFitTextureView(
    private val context: Context,
): TextureView(context), TextureView.SurfaceTextureListener {
    private val TAG = "AutoFitTextureView"
    private val TAG_LISTENER = "AutoFitTextureView:SurfaceTextureListener"

    private var mRatioWidth: Int = 0
    private var mRatioHeight: Int = 0

    private val mPreviewSize: Size = Size(900, 1600)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        Log.i(TAG, "onMeasure")

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (mRatioWidth == 0 || mRatioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * mRatioWidth / mRatioHeight) {
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth)
            } else {
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height)
            }
        }
    }

    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be negative")
        }

        Log.i(TAG, this.width.toString())
        Log.i(TAG, this.height.toString())

        mRatioWidth = width
        mRatioHeight = height
        requestLayout()
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation: Int = windowManager.defaultDisplay.rotation

        val matrix: Matrix = Matrix()
        val viewRect: RectF = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect: RectF = RectF(0f, 0f, mPreviewSize.height.toFloat(), mPreviewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale: Float = (viewHeight / mPreviewSize.height).toFloat()
                .coerceAtLeast((viewWidth / mPreviewSize.width).toFloat())
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90 * (rotation / 2).toFloat(), centerX, centerY)
        }
        this.setTransform(matrix)
    }

    /**
     * In this sample, we choose a video size with 3x4 for  aspect ratio. for more perfectness 720
     * as well Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size 1080p,720px
     */
    private fun chooseVideoSize(choices: Array<Size>): Size {
        for (size in choices) {
            if (size.width == 1920 && size.height == 1080) {
                return size
            }
        }

        for (size: Size in choices) {
            if (size.width == size.height * 16 / 9 && size.width <= 1080) {
                return size
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size")
        return choices.last()
    }

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        Log.i(TAG_LISTENER, "OnSurfaceTextureAvailable")
    }
    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        Log.i(TAG_LISTENER, "onSurfaceTextureSizeChanged")
        configureTransform(width, height)
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Log.i(TAG_LISTENER, "onSurfaceTextureDestroyed")
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture): Unit {
        Log.i(TAG_LISTENER, "onSurfaceTextureUpdated")
    }

//    /**
//     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
//     * width and height are at least as large as the respective requested values, and whose aspect
//     * ratio matches with the specified value.
//     *
//     * @param choices The list of sizes that the camera supports for the intended output class
//     * @param width The minimum desired width
//     * @param height The minimum desired height
//     * @param aspectRatio The aspect ratio
//     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
//     */
//    private fun chooseOptimalSize(
//        choices: Array<Size>,
//        width: Int,
//        height: Int,
//        aspectRatio: Size
//    ): Size {
//        // Collect the supported resolutions that are at least as big as the preview Surface
//        val bigEnough: ArrayList<Size> = ArrayList()
//        val w = aspectRatio.width
//        val h = aspectRatio.height
//
//        bigEnough.addAll(choices.filter {
//            it.height == it.width * h / w && it.width >= width && it.height >= height
//        })
//
//        // Pick the smallest of those, assuming we found any
//        return if (bigEnough.size > 0) {
//            Collections.min(bigEnough, CompareSizesByArea())
//        } else {
//            Log.e(TAG, "Couldn't find any suitable preview size")
//            choices.first()
//        }
//    }
}