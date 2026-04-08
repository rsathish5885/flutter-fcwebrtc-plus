package com.cloudwebrtc.webrtc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.cloudwebrtc.webrtc.models.CacheFrame
import com.cloudwebrtc.webrtc.utils.ImageSegmenterHelper
import com.cloudwebrtc.webrtc.video.LocalVideoTrack
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import com.google.android.gms.tflite.java.TfLite
import com.google.mediapipe.tasks.vision.core.RunningMode
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.YuvHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class FlutterRTCVideoPipe : LocalVideoTrack.ExternalVideoFrameProcessing {

    var isGpuSupported = false
    private val tag: String = "[FlutterRTC-VideoPipe]"
    private var backgroundBitmap: Bitmap? = null
    private var expectConfidence = 0.7
    private var imageSegmentationHelper: ImageSegmenterHelper? = null
    private var sink: VideoSink? = null
    private val bitmapMap = HashMap<Long, CacheFrame>()
    private var lastProcessedFrameTime: Long = 0
    private val targetFrameInterval: Long = 1000 / 24 // 24 FPS
    private var virtualBackground: FlutterRTCVirtualBackground? = null
    private var beautyFilters: FlutterRTCBeautyFilters? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        Log.d(tag, "Initialized")
        this.virtualBackground = FlutterRTCVirtualBackground()

        if (this.beautyFilters == null) {
            this.beautyFilters = FlutterRTCBeautyFilters(appContext)
        }

        // MediaPipe ImageSegmenter manages its own native libraries and delegates.
        // We use DELEGATE_GPU by default in ImageSegmenterHelper; if it fails, 
        // it falls back to CPU or throws a RuntimeException which we catch.
        this.imageSegmentationHelper = ImageSegmenterHelper(
            context = appContext,
            runningMode = RunningMode.LIVE_STREAM,
            imageSegmenterListener = object : ImageSegmenterHelper.SegmenterListener {
                override fun onError(error: String, errorCode: Int) {
                    Log.e(tag, "ImageSegmenter error: $error (code: $errorCode)")
                }

                override fun onResults(resultBundle: ImageSegmenterHelper.ResultBundle) {
                    val timestampMS = resultBundle.frameTime
                    val cacheFrame: CacheFrame = bitmapMap[timestampMS] ?: return

                    val maskFloat = resultBundle.results
                    val maskWidth = resultBundle.width
                    val maskHeight = resultBundle.height
                    val bitmap = cacheFrame.originalBitmap
                    val mask = virtualBackground?.convertFloatBufferToByteBuffer(maskFloat)
                    val colors = virtualBackground?.maskColorsFromByteBuffer(
                        mask!!, maskWidth, maskHeight, bitmap, expectConfidence
                    )
                    val segmentedBitmap = virtualBackground?.createBitmapFromColors(colors!!, bitmap.width, bitmap.height)

                    if (backgroundBitmap == null) return

                    val outputBitmap = virtualBackground?.drawSegmentedBackground(segmentedBitmap, backgroundBitmap)
                    if (outputBitmap != null) emitBitmapOnFrame(outputBitmap)

                    bitmapMap.remove(timestampMS)
                }
            })
    }

    fun dispose() {
        this.expectConfidence = 0.7
        this.sink = null
        this.bitmapMap.clear()
        this.backgroundBitmap = null
        this.imageSegmentationHelper = null
        this.virtualBackground = null
        this.beautyFilters?.release()
        this.beautyFilters = null
        resetBackground()
    }

    /**
     * Explicitly destroy the FaceUnity engine and global SDK state.
     * Use this when switching between multiple SDK integrations (e.g. NERtc and WebRTC).
     */
    fun releaseBeautyEngine() {
        Log.i(tag, "Manually releasing FaceUnity engine and SDK state")
        this.beautyFilters?.release()
        this.beautyFilters = null
    }

    fun resetBackground() {
        this.backgroundBitmap = null
    }

    fun configurationVirtualBackground(bgBitmap: Bitmap, confidence: Double) {
        backgroundBitmap = bgBitmap
        expectConfidence = confidence
    }

    // ─────────────────────── Beauty parameter setters ──────────────────────

    fun setThinValue(value: Float) = beautyFilters?.setThinValue(value) ?: Unit
    fun setBigEyesValue(value: Float) = beautyFilters?.setBigEyesValue(value) ?: Unit
    fun setBeautyValue(value: Float) = beautyFilters?.setBeautyValue(value) ?: Unit
    fun setLipstickValue(value: Float) = beautyFilters?.setLipstickValue(value) ?: Unit
    fun setWhiteValue(value: Float) = beautyFilters?.setWhiteValue(value) ?: Unit
    fun setEyeBrightValue(value: Float) = beautyFilters?.setEyeBrightValue(value) ?: Unit
    fun setFilterName(name: String) = beautyFilters?.setFilterName(name) ?: Unit
    fun setFilterLevel(value: Float) = beautyFilters?.setFilterLevel(value) ?: Unit

    // ────────────────────── VideoFrame → NV21 (no JPEG) ───────────────────

    /**
     * Convert a WebRTC VideoFrame directly to an NV21 byte array.
     * This avoids the JPEG encode/decode that was the primary performance bottleneck
     * in the previous GPUPixel-based implementation.
     */
    private fun videoFrameToNV21(videoFrame: VideoFrame): ByteArray? {
        return try {
            videoFrame.retain()
            val buffer = videoFrame.buffer
            val i420Buffer = buffer.toI420() ?: return null

            val width = i420Buffer.width
            val height = i420Buffer.height
            val nv21Size = width * height * 3 / 2
            val nv21 = ByteArray(nv21Size)

            // Y plane — copy directly
            val yBuffer = i420Buffer.dataY
            val yStride = i420Buffer.strideY
            var yDst = 0
            for (row in 0 until height) {
                yBuffer.position(row * yStride)
                yBuffer.get(nv21, yDst, width)
                yDst += width
            }

            // U/V planes → interleaved VU for NV21
            val uBuffer = i420Buffer.dataU
            val vBuffer = i420Buffer.dataV
            val uStride = i420Buffer.strideU
            val vStride = i420Buffer.strideV
            var uvDst = width * height
            val uvHeight = height / 2
            val uvWidth = width / 2
            for (row in 0 until uvHeight) {
                uBuffer.position(row * uStride)
                vBuffer.position(row * vStride)
                for (col in 0 until uvWidth) {
                    nv21[uvDst++] = vBuffer.get() // V first in NV21
                    nv21[uvDst++] = uBuffer.get()
                }
            }

            i420Buffer.release()
            videoFrame.release()
            nv21
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ───────────────────── NV21 → VideoFrame ──────────────────────────────

    private fun convertNV21ToVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): VideoFrame? {
        val ySize = width * height
        val uvSize = ySize / 4

        val yBuffer = ByteBuffer.allocateDirect(ySize)
        val uBuffer = ByteBuffer.allocateDirect(uvSize)
        val vBuffer = ByteBuffer.allocateDirect(uvSize)

        // Y plane
        yBuffer.put(nv21, 0, ySize)
        yBuffer.rewind()

        // NV21 interleaved VU → separate U and V for I420
        var uvIdx = ySize
        val uvHeight = height / 2
        val uvWidth = width / 2
        val uArr = ByteArray(uvSize)
        val vArr = ByteArray(uvSize)
        for (i in 0 until uvHeight * uvWidth) {
            vArr[i] = nv21[uvIdx++]
            uArr[i] = nv21[uvIdx++]
        }
        vBuffer.put(vArr); vBuffer.rewind()
        uBuffer.put(uArr); uBuffer.rewind()

        val i420Buffer = JavaI420Buffer.wrap(
            width, height,
            yBuffer, width,
            uBuffer, width / 2,
            vBuffer, width / 2,
            null
        )
        return VideoFrame(i420Buffer, rotation, System.nanoTime())
    }

    // ─────────────── Virtual background (Bitmap-based, unchanged) ──────────

    /**
     * For the virtual background path we still need a Bitmap. Convert NV21 → Bitmap
     * efficiently (no JPEG, use YuvImage with NV21 format).
     */
    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
            val jpegData = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun emitBitmapOnFrame(bitmap: Bitmap) {
        val frame = convertBitmapToVideoFrame(bitmap)
        sink?.onFrame(frame)
    }

    // Kept for virtual background output path only
    private fun convertBitmapToVideoFrame(bitmap: Bitmap): VideoFrame? {
        val width = bitmap.width
        val height = bitmap.height
        val ySize = width * height
        val uvSize = ySize / 4

        val yBuffer = ByteBuffer.allocateDirect(ySize)
        val uBuffer = ByteBuffer.allocateDirect(uvSize)
        val vBuffer = ByteBuffer.allocateDirect(uvSize)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            val u = (-0.169 * r - 0.331 * g + 0.5 * b + 128).toInt()
            val v = (0.5 * r - 0.419 * g - 0.081 * b + 128).toInt()
            yBuffer.put(y.toByte())
            if (i % 2 == 0 && (i / width) % 2 == 0) {
                uBuffer.put(u.toByte())
                vBuffer.put(v.toByte())
            }
        }
        yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind()

        val i420Buffer = JavaI420Buffer.wrap(
            width, height,
            yBuffer, width,
            uBuffer, width / 2,
            vBuffer, width / 2,
            null
        )
        return VideoFrame(i420Buffer, 0, System.nanoTime())
    }

    // ─────────────────────────── onFrame (main entry) ──────────────────────

    override fun onFrame(frame: VideoFrame) {
        if (sink == null) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedFrameTime < targetFrameInterval) return
        lastProcessedFrameTime = currentTime

        val width = frame.buffer.width
        val height = frame.buffer.height
        val rotation = frame.rotation

        // Fast path: VideoFrame → NV21 (no JPEG)
        val nv21 = videoFrameToNV21(frame)
        if (nv21 == null) {
            Log.d(tag, "Failed to convert VideoFrame to NV21")
            return
        }

        // Apply FaceUnity beauty on the NV21 buffer
        val processedNv21 = beautyFilters?.fuBeauty?.processNV21Frame(nv21, width, height) ?: nv21

        if (backgroundBitmap != null) {
            // Virtual background path: convert processed NV21 to Bitmap
            val bitmap = nv21ToBitmap(processedNv21, width, height) ?: return
            val frameTimeMs: Long = SystemClock.uptimeMillis()
            bitmapMap[frameTimeMs] = CacheFrame(originalBitmap = bitmap)
            imageSegmentationHelper?.segmentLiveStreamFrame(bitmap, frameTimeMs)
        } else {
            // Direct output path: NV21 → VideoFrame (no extra copies)
            val outFrame = convertNV21ToVideoFrame(processedNv21, width, height, rotation)
            if (outFrame != null) sink?.onFrame(outFrame)
        }
    }

    override fun setSink(videoSink: VideoSink) {
        sink = videoSink
    }
}