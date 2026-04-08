package com.cloudwebrtc.webrtc

import android.content.Context

/**
 * Beauty filter facade for the WebRTC video pipeline.
 *
 * Previously backed by GPUPixel (Bitmap-based, slow due to JPEG compression).
 * Now backed by FaceUnity SDK via [FlutterRTCFaceUnityBeauty] (NV21 byte array,
 * GPU-accelerated, no JPEG round-trip).
 *
 * All frame processing is handled in [FlutterRTCVideoPipe] using
 * [FlutterRTCFaceUnityBeauty.processNV21Frame]; this class is retained as the
 * parameter-control surface called from [MethodCallHandlerImpl].
 */
class FlutterRTCBeautyFilters(context: Context) {

    private val tag = "FlutterRTCBeautyFilters"

    // Underlying FaceUnity processor — initialized lazily on first param change
    internal val fuBeauty = FlutterRTCFaceUnityBeauty(context)

    init {
        // Initialize with embedded auth key from authpack
        val key = com.cloudwebrtc.faceunity.authpack.A()
        fuBeauty.initialize(key)
    }

    // ─── Beauty Parameter Setters (mapped to FaceUnity equivalents) ───────

    /** Skin smoothing (0.0 = off, 1.0 = maximum). Maps to FU blur level. */
    fun setBeautyValue(value: Float) = fuBeauty.setBlurLevel(value)

    /** Whitening (0.0 = off, 1.0 = maximum). Maps to FU color level. */
    fun setWhiteValue(value: Float) = fuBeauty.setColorLevel(value)

    /** Face slimming (0.0 = off, 1.0 = maximum). Maps to FU cheek thinning. */
    fun setThinValue(value: Float) = fuBeauty.setCheekThinning(value)

    /** Eye enlarging (0.0 = off, 1.0 = maximum). Maps to FU eye enlarging. */
    fun setBigEyesValue(value: Float) = fuBeauty.setEyeEnlarging(value)

    /** Redness / rosy glow (0.0 = off, 1.0 = maximum). Maps to FU red level. */
    fun setLipstickValue(value: Float) = fuBeauty.setRedLevel(value)

    /** Eye brightening (0.0 = off, 1.0 = maximum). */
    fun setEyeBrightValue(value: Float) = fuBeauty.setEyeBright(value)

    /** Filter name (e.g. "origin", "bailiang1"). */
    fun setFilterName(name: String) = fuBeauty.setFilterName(name)

    /** Filter intensity (0.0 = off, 1.0 = maximum). */
    fun setFilterLevel(value: Float) = fuBeauty.setFilterLevel(value)

    // ─────────────────────────────────────────────────────────────────────

    fun release() {
        fuBeauty.release()
    }
}