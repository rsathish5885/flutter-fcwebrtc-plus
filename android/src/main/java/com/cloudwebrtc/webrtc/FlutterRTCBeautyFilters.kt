package com.cloudwebrtc.webrtc

import android.content.Context

/**
 * FaceUnity beauty facade for the WebRTC video pipeline.
 *
 * The FaceUnity engine is created only when beauty is actually enabled and is
 * released as soon as every beauty parameter goes back to an "off" value.
 */
class FlutterRTCBeautyFilters(context: Context) {

    private val appContext = context.applicationContext

    internal var fuBeauty: FlutterRTCFaceUnityBeauty? = null
        private set

    private var beautyValue = 0f
    private var whiteValue = 0f
    private var thinValue = 0f
    private var bigEyesValue = 0f
    private var redValue = 0f
    private var eyeBrightValue = 0f
    private var filterName = DEFAULT_FILTER_NAME
    private var filterLevel = 0f

    fun setBeautyValue(value: Float) {
        beautyValue = value
        applyState()
    }

    fun setWhiteValue(value: Float) {
        whiteValue = value
        applyState()
    }

    fun setThinValue(value: Float) {
        thinValue = value
        applyState()
    }

    fun setBigEyesValue(value: Float) {
        bigEyesValue = value
        applyState()
    }

    fun setLipstickValue(value: Float) {
        redValue = value
        applyState()
    }

    fun setRedValue(value: Float) {
        redValue = value
        applyState()
    }

    fun setEyeBrightValue(value: Float) {
        eyeBrightValue = value
        applyState()
    }

    fun setFilterName(name: String) {
        filterName = if (name.isBlank()) DEFAULT_FILTER_NAME else name
        applyState()
    }

    fun setFilterLevel(value: Float) {
        filterLevel = value
        applyState()
    }

    fun release() {
        resetValues()
        releaseEngine()
    }

    fun releaseEngine() {
        fuBeauty?.release()
        fuBeauty = null
    }

    private fun applyState() {
        if (!isBeautyEnabled()) {
            releaseEngine()
            return
        }

        val beauty = ensureEngine()
        beauty.setBlurLevel(beautyValue)
        beauty.setColorLevel(whiteValue)
        beauty.setCheekThinning(thinValue)
        beauty.setEyeEnlarging(bigEyesValue)
        beauty.setRedLevel(redValue)
        beauty.setEyeBright(eyeBrightValue)
        beauty.setFilterName(filterName)
        beauty.setFilterLevel(filterLevel)
    }

    private fun ensureEngine(): FlutterRTCFaceUnityBeauty {
        fuBeauty?.let { return it }

        val beauty = FlutterRTCFaceUnityBeauty(appContext)
        beauty.initialize(com.cloudwebrtc.faceunity.authpack.A())
        fuBeauty = beauty
        return beauty
    }

    private fun isBeautyEnabled(): Boolean {
        return beautyValue > EPSILON ||
            whiteValue > EPSILON ||
            thinValue > EPSILON ||
            bigEyesValue > EPSILON ||
            redValue > EPSILON ||
            eyeBrightValue > EPSILON ||
            filterLevel > EPSILON
    }

    private fun resetValues() {
        beautyValue = 0f
        whiteValue = 0f
        thinValue = 0f
        bigEyesValue = 0f
        redValue = 0f
        eyeBrightValue = 0f
        filterName = DEFAULT_FILTER_NAME
        filterLevel = 0f
    }

    companion object {
        private const val DEFAULT_FILTER_NAME = "origin"
        private const val EPSILON = 0.0001f
    }
}
