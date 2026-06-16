package com.cloudwebrtc.webrtc

import android.content.Context
import android.util.Log
import com.cloudwebrtc.webrtc.video.LocalVideoTrack
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer

class FlutterRTCVideoPipe : LocalVideoTrack.ExternalVideoFrameProcessing {

    var rotationOverride: Int? = null  // null = use camera rotation; 0/90/180/270 = force
    private val tag: String = "[FlutterRTC-VideoPipe]"
    private var sink: VideoSink? = null
    private var lastProcessedFrameTime: Long = 0
    private val targetFrameInterval: Long = 1000 / 24 // 24 FPS
    private var beautyFilters: FlutterRTCBeautyFilters? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        Log.d(tag, "Initialized")

        if (this.beautyFilters == null) {
            this.beautyFilters = FlutterRTCBeautyFilters(appContext)
        }
    }

    fun dispose() {
        this.sink = null
        this.beautyFilters?.release()
        this.beautyFilters = null
    }

    fun releaseBeautyEngine() {
        Log.i(tag, "Manually releasing FaceUnity engine and SDK state")
        this.beautyFilters?.release()
        this.beautyFilters = null
    }

    // ─────────────────────── Beauty parameter setters ──────────────────────

    fun setThinValue(value: Float) = beautyFilters?.setThinValue(value) ?: Unit
    fun setRedValue(value: Float) = beautyFilters?.setRedValue(value) ?: Unit
    fun setBigEyesValue(value: Float) = beautyFilters?.setBigEyesValue(value) ?: Unit
    fun setBeautyValue(value: Float) = beautyFilters?.setBeautyValue(value) ?: Unit
    fun setLipstickValue(value: Float) = beautyFilters?.setLipstickValue(value) ?: Unit
    fun setWhiteValue(value: Float) = beautyFilters?.setWhiteValue(value) ?: Unit
    fun setEyeBrightValue(value: Float) = beautyFilters?.setEyeBrightValue(value) ?: Unit
    fun setFilterName(name: String) = beautyFilters?.setFilterName(name) ?: Unit
    fun setFilterLevel(value: Float) = beautyFilters?.setFilterLevel(value) ?: Unit

    // ────────────────────── VideoFrame → NV21 (no JPEG) ───────────────────

    private fun videoFrameToNV21(videoFrame: VideoFrame): ByteArray? {
        videoFrame.retain()
        return try {
            val buffer = videoFrame.buffer
            val i420Buffer = buffer.toI420() ?: return null
            try {
                val width = i420Buffer.width
                val height = i420Buffer.height
                val nv21Size = width * height * 3 / 2
                val nv21 = ByteArray(nv21Size)

                val yBuffer = i420Buffer.dataY
                val yStride = i420Buffer.strideY
                var yDst = 0
                for (row in 0 until height) {
                    yBuffer.position(row * yStride)
                    yBuffer.get(nv21, yDst, width)
                    yDst += width
                }

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
                        nv21[uvDst++] = vBuffer.get()
                        nv21[uvDst++] = uBuffer.get()
                    }
                }
                nv21
            } finally {
                i420Buffer.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            videoFrame.release()
        }
    }

    // ───────────────────── NV21 → VideoFrame ──────────────────────────────

    private fun convertNV21ToVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int, timestampNs: Long): VideoFrame? {
        val ySize = width * height
        val uvSize = ySize / 4

        val yBuffer = ByteBuffer.allocateDirect(ySize)
        val uBuffer = ByteBuffer.allocateDirect(uvSize)
        val vBuffer = ByteBuffer.allocateDirect(uvSize)

        yBuffer.put(nv21, 0, ySize)
        yBuffer.rewind()

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
        return VideoFrame(i420Buffer, rotation, timestampNs)
    }

    // ─────────────────────────── onFrame (main entry) ──────────────────────

    override fun onFrame(frame: VideoFrame) {
        if (sink == null) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedFrameTime < targetFrameInterval) return
        lastProcessedFrameTime = currentTime

        val timestampNs = frame.timestampNs
        val width = frame.buffer.width
        val height = frame.buffer.height
        val rotation = rotationOverride ?: frame.rotation

        val nv21 = videoFrameToNV21(frame)
        if (nv21 == null) {
            Log.d(tag, "Failed to convert VideoFrame to NV21")
            return
        }

        val processedNv21 = beautyFilters?.fuBeauty?.processNV21Frame(nv21, width, height) ?: nv21
        val outFrame = convertNV21ToVideoFrame(processedNv21, width, height, rotation, timestampNs)
        if (outFrame != null) sink?.onFrame(outFrame)
    }

    override fun setSink(videoSink: VideoSink) {
        sink = videoSink
    }
}
