package com.cloudwebrtc.webrtc

import android.content.Context
import android.util.Log
import com.cloudwebrtc.webrtc.video.LocalVideoTrack
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FlutterRTCVideoPipe : LocalVideoTrack.ExternalVideoFrameProcessing {

    var rotationOverride: Int? = null
    private val tag: String = "[FlutterRTC-VideoPipe]"
    private var sink: VideoSink? = null
    private var lastProcessedFrameTime: Long = 0
    private val targetFrameInterval: Long = 1000 / 24 // 24 FPS
    private var beautyFilters: FlutterRTCBeautyFilters? = null

    // Frame counters — log every 100 processed frames so we don't flood the file
    private val totalFramesReceived  = AtomicLong(0)
    private val totalFramesProcessed = AtomicLong(0)
    private val nv21ConvertFails     = AtomicInteger(0)
    private val outFrameConvertFails = AtomicInteger(0)
    private var lastDimLog           = ""  // suppress repeated WxH logs

    // ─────────────────────────── Lifecycle ─────────────────────────────────

    fun initialize(context: Context) {
        BeautyLogger.log("VideoPipe.initialize() | beautyFilters already=${beautyFilters != null}")
        val appContext = context.applicationContext
        Log.d(tag, "Initialized")

        if (this.beautyFilters == null) {
            BeautyLogger.log("VideoPipe.initialize() — creating FlutterRTCBeautyFilters")
            this.beautyFilters = FlutterRTCBeautyFilters(appContext)
            BeautyLogger.log("VideoPipe.initialize() — FlutterRTCBeautyFilters created OK")
        } else {
            BeautyLogger.warn("VideoPipe.initialize() — beautyFilters already exists, skipping")
        }
    }

    fun dispose() {
        BeautyLogger.log("VideoPipe.dispose()"
                + " | sink=${sink != null} beautyFilters=${beautyFilters != null}"
                + " | totalReceived=${totalFramesReceived.get()} processed=${totalFramesProcessed.get()}"
                + " | nv21Fails=${nv21ConvertFails.get()} outFrameFails=${outFrameConvertFails.get()}")
        this.sink = null
        this.beautyFilters?.release()
        this.beautyFilters = null
        BeautyLogger.log("VideoPipe.dispose() done")
    }

    fun releaseBeautyEngine() {
        BeautyLogger.log("VideoPipe.releaseBeautyEngine() | beautyFilters=${beautyFilters != null}")
        Log.i(tag, "Manually releasing FaceUnity engine and SDK state")
        this.beautyFilters?.release()
        this.beautyFilters = null
        BeautyLogger.log("VideoPipe.releaseBeautyEngine() done")
    }

    // ─────────────────────── Beauty parameter setters ──────────────────────
    // Each setter logs value + whether beautyFilters is null (call silently dropped if null)

    fun setThinValue(value: Float) {
        BeautyLogger.log("VideoPipe.setThinValue($value) | beautyFilters=${beautyFilters != null}")
        if (beautyFilters == null) BeautyLogger.warn("setThinValue — beautyFilters null, call DROPPED")
        beautyFilters?.setThinValue(value)
    }

    fun setRedValue(value: Float) {
        BeautyLogger.log("VideoPipe.setRedValue($value) | beautyFilters=${beautyFilters != null}")
        if (beautyFilters == null) BeautyLogger.warn("setRedValue — beautyFilters null, call DROPPED")
        beautyFilters?.setRedValue(value)
    }

    fun setBigEyesValue(value: Float) {
        BeautyLogger.log("VideoPipe.setBigEyesValue($value) | beautyFilters=${beautyFilters != null}")
        if (beautyFilters == null) BeautyLogger.warn("setBigEyesValue — beautyFilters null, call DROPPED")
        beautyFilters?.setBigEyesValue(value)
    }

    fun setBeautyValue(value: Float) {
        BeautyLogger.log("VideoPipe.setBeautyValue(smooth=$value) | beautyFilters=${beautyFilters != null}")
        if (beautyFilters == null) BeautyLogger.warn("setBeautyValue — beautyFilters null, call DROPPED")
        beautyFilters?.setBeautyValue(value)
    }

    fun setLipstickValue(value: Float) {
        BeautyLogger.log("VideoPipe.setLipstickValue($value) | beautyFilters=${beautyFilters != null}")
        if (beautyFilters == null) BeautyLogger.warn("setLipstickValue — beautyFilters null, call DROPPED")
        beautyFilters?.setLipstickValue(value)
    }

    fun setWhiteValue(value: Float) {
        BeautyLogger.log("VideoPipe.setWhiteValue($value) | beautyFilters=${beautyFilters != null}")
        if (beautyFilters == null) BeautyLogger.warn("setWhiteValue — beautyFilters null, call DROPPED")
        beautyFilters?.setWhiteValue(value)
    }

    fun setEyeBrightValue(value: Float) {
        BeautyLogger.log("VideoPipe.setEyeBrightValue($value) | beautyFilters=${beautyFilters != null}")
        if (beautyFilters == null) BeautyLogger.warn("setEyeBrightValue — beautyFilters null, call DROPPED")
        beautyFilters?.setEyeBrightValue(value)
    }

    fun setFilterName(name: String) {
        BeautyLogger.log("VideoPipe.setFilterName($name) | beautyFilters=${beautyFilters != null}")
        if (beautyFilters == null) BeautyLogger.warn("setFilterName — beautyFilters null, call DROPPED")
        beautyFilters?.setFilterName(name)
    }

    fun setFilterLevel(value: Float) {
        BeautyLogger.log("VideoPipe.setFilterLevel($value) | beautyFilters=${beautyFilters != null}")
        if (beautyFilters == null) BeautyLogger.warn("setFilterLevel — beautyFilters null, call DROPPED")
        beautyFilters?.setFilterLevel(value)
    }

    // ────────────────────── VideoFrame → NV21 ─────────────────────────────

    private fun videoFrameToNV21(videoFrame: VideoFrame, frameNum: Long): ByteArray? {
        videoFrame.retain()
        return try {
            val buffer = videoFrame.buffer
            if (frameNum == 1L) {
                BeautyLogger.log("videoFrameToNV21 #$frameNum: bufferType=${buffer.javaClass.simpleName} size=${buffer.width}x${buffer.height}")
            }

            val i420Buffer = buffer.toI420()
            if (i420Buffer == null) {
                BeautyLogger.error("videoFrameToNV21 #$frameNum: toI420() returned null — cannot convert frame")
                return null
            }

            try {
                val width  = i420Buffer.width
                val height = i420Buffer.height

                if (frameNum == 1L) {
                    BeautyLogger.log("videoFrameToNV21 #$frameNum: i420 ${width}x${height}"
                            + " yStride=${i420Buffer.strideY} uStride=${i420Buffer.strideU} vStride=${i420Buffer.strideV}")
                }

                val nv21 = ByteArray(width * height * 3 / 2)

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
                var uvDst   = width * height
                val uvHeight = height / 2
                val uvWidth  = width / 2
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
            val count = nv21ConvertFails.incrementAndGet()
            BeautyLogger.error("videoFrameToNV21 #$frameNum EXCEPTION (fail #$count): ${e.javaClass.simpleName}: ${e.message}", e)
            e.printStackTrace()
            null
        } finally {
            videoFrame.release()
        }
    }

    // ───────────────────── NV21 → VideoFrame ──────────────────────────────

    private fun convertNV21ToVideoFrame(
        nv21: ByteArray, width: Int, height: Int, rotation: Int, timestampNs: Long, frameNum: Long
    ): VideoFrame? {
        return try {
            val ySize  = width * height
            val uvSize = ySize / 4
            val expectedLen = ySize + uvSize * 2

            if (nv21.size != expectedLen) {
                BeautyLogger.error("convertNV21ToVideoFrame #$frameNum: NV21 size MISMATCH"
                        + " got=${nv21.size} expected=$expectedLen size=${width}x${height} — frame DROPPED")
                return null
            }

            val yBuffer = ByteBuffer.allocateDirect(ySize)
            val uBuffer = ByteBuffer.allocateDirect(uvSize)
            val vBuffer = ByteBuffer.allocateDirect(uvSize)

            yBuffer.put(nv21, 0, ySize); yBuffer.rewind()

            var uvIdx    = ySize
            val uvHeight = height / 2
            val uvWidth  = width / 2
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
            VideoFrame(i420Buffer, rotation, timestampNs)
        } catch (e: Exception) {
            val count = outFrameConvertFails.incrementAndGet()
            BeautyLogger.error("convertNV21ToVideoFrame #$frameNum EXCEPTION (fail #$count): ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────── onFrame (main entry) ──────────────────────

    override fun onFrame(frame: VideoFrame) {
        val received = totalFramesReceived.incrementAndGet()

        if (sink == null) {
            // Log first occurrence + every 300 frames so we know frames are arriving but sink is missing
            if (received == 1L || received % 300 == 0L) {
                BeautyLogger.warn("onFrame #$received: sink is null — frame DROPPED")
            }
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedFrameTime < targetFrameInterval) return
        lastProcessedFrameTime = currentTime

        val processed = totalFramesProcessed.incrementAndGet()
        val width     = frame.buffer.width
        val height    = frame.buffer.height
        val rotation  = rotationOverride ?: frame.rotation

        // Log first frame + every 100 processed frames + whenever dimensions change
        val dimKey    = "${width}x${height}@rot$rotation"
        val shouldLog = processed == 1L || processed % 100 == 0L || dimKey != lastDimLog
        if (shouldLog) {
            lastDimLog = dimKey
            BeautyLogger.log("onFrame #$processed (totalReceived=$received)"
                    + " | ${width}x${height} rot=$rotation"
                    + " | beautyFilters=${beautyFilters != null}"
                    + " | fuBeautyReady=${beautyFilters?.fuBeauty != null}"
                    + " | nv21Fails=${nv21ConvertFails.get()} outFails=${outFrameConvertFails.get()}")
        }

        val timestampNs = frame.timestampNs

        val nv21 = videoFrameToNV21(frame, processed)
        if (nv21 == null) {
            BeautyLogger.error("onFrame #$processed: NV21 conversion failed — frame DROPPED")
            return
        }

        val fuBeauty = beautyFilters?.fuBeauty
        if (fuBeauty == null && shouldLog) {
            BeautyLogger.warn("onFrame #$processed: fuBeauty is null — beauty bypassed")
        }

        val processedNv21 = fuBeauty?.processNV21Frame(nv21, width, height) ?: nv21
        // Reference equality: if FaceUnity returned the same array, beauty did nothing
        val beautyApplied = processedNv21 !== nv21
        if (shouldLog) {
            BeautyLogger.log("onFrame #$processed: beautyApplied=$beautyApplied"
                    + " inLen=${nv21.size} outLen=${processedNv21.size}")
        }

        val outFrame = convertNV21ToVideoFrame(processedNv21, width, height, rotation, timestampNs, processed)
        if (outFrame == null) {
            BeautyLogger.error("onFrame #$processed: output frame conversion failed — frame DROPPED")
            return
        }

        sink?.onFrame(outFrame)
    }

    override fun setSink(videoSink: VideoSink) {
        BeautyLogger.log("VideoPipe.setSink() | replacing ${if (sink != null) "existing" else "null"} sink")
        sink = videoSink
    }
}
