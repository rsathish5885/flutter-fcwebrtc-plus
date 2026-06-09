package com.cloudwebrtc.webrtc.audio;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * RNNoise-based noise suppressor plugged into the WebRTC
 * ExternalAudioProcessingFactory capture post-processing chain.
 *
 * Suppression levels (default: 2):
 *   1 – rnnoise only
 *   2 – rnnoise + VAD-probability gate (noticeably quieter background)
 *   3 – double-pass rnnoise + VAD gate (most aggressive)
 */
public class RnNoiseSuppressor implements AudioProcessingAdapter.ExternalAudioFrameProcessing {

    private static final String TAG = "RnNoiseSuppressor";

    /** Default: level 2 — rnnoise + VAD gate. */
    public static final int DEFAULT_SUPPRESSION_LEVEL = 3;

    private static final boolean nativeAvailable;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("rnnoise_jni");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load rnnoise_jni native library", e);
        }
        nativeAvailable = loaded;
    }

    private long    nativeHandle    = 0;
    private int     sampleRateHz    = 0;
    private int     numChannels     = 0;
    private boolean enabled         = true;
    private int     suppressionLevel = DEFAULT_SUPPRESSION_LEVEL;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled && nativeAvailable;
        if (!this.enabled) {
            release();
        } else if (sampleRateHz > 0 && numChannels > 0) {
            createNativeState(sampleRateHz, numChannels);
        }
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    /**
     * @param level 1 (mild) · 2 (moderate, default) · 3 (aggressive)
     */
    public synchronized void setSuppressionLevel(int level) {
        suppressionLevel = Math.max(1, Math.min(3, level));
        // Recreate native state so the second-pass denoisers are allocated/freed
        if (nativeHandle != 0) {
            createNativeState(sampleRateHz, numChannels);
        }
    }

    public synchronized int getSuppressionLevel() {
        return suppressionLevel;
    }

    public synchronized void release() {
        if (nativeHandle != 0) {
            nativeRelease(nativeHandle);
            nativeHandle = 0;
        }
    }

    // -----------------------------------------------------------------------
    // ExternalAudioFrameProcessing
    // -----------------------------------------------------------------------

    @Override
    public synchronized void initialize(int sampleRateHz, int numChannels) {
        this.sampleRateHz = sampleRateHz;
        this.numChannels  = numChannels;
        if (enabled && nativeAvailable) {
            createNativeState(sampleRateHz, numChannels);
        }
    }

    @Override
    public synchronized void reset(int newRate) {
        sampleRateHz = newRate;
        if (enabled && nativeAvailable) {
            createNativeState(newRate, numChannels);
        }
    }

    @Override
    public synchronized void process(int numBands, int numFrames, ByteBuffer buffer) {
        if (!enabled || buffer == null || numFrames <= 0 || nativeHandle == 0) return;

        int bytesPerSample = buffer.remaining() / Math.max(1, numFrames * numChannels);
        if (bytesPerSample != 2 && bytesPerSample != 4) return;

        byte[] audioBytes;
        int offset;
        if (buffer.hasArray()) {
            audioBytes = buffer.array();
            offset     = buffer.arrayOffset() + buffer.position();
        } else {
            ByteBuffer dup = buffer.duplicate();
            audioBytes = new byte[dup.remaining()];
            dup.get(audioBytes);
            offset = 0;
        }

        if (bytesPerSample == 2) {
            nativeProcess(nativeHandle, audioBytes, offset, buffer.remaining());
        } else {
            processFloatBuffer(audioBytes, offset, buffer.remaining());
        }

        if (!buffer.hasArray()) {
            buffer.duplicate().put(audioBytes, 0, Math.min(audioBytes.length, buffer.remaining()));
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void createNativeState(int sr, int ch) {
        release();
        nativeHandle = nativeCreate(sr, ch, suppressionLevel);
        if (nativeHandle != 0) {
            Log.d(TAG, "rnnoise created sr=" + sr + " ch=" + ch + " level=" + suppressionLevel);
        } else {
            Log.w(TAG, "rnnoise_create failed sr=" + sr + " ch=" + ch);
        }
    }

    private void processFloatBuffer(byte[] audioBytes, int offset, int length) {
        ByteBuffer floatBuf = ByteBuffer.wrap(audioBytes, offset, length)
                .slice().order(ByteOrder.LITTLE_ENDIAN);
        int floatCount = length / 4;

        float maxAbs = 0f;
        for (int i = 0; i < floatCount; i++) {
            maxAbs = Math.max(maxAbs, Math.abs(floatBuf.getFloat(i * 4)));
        }
        boolean normalized = maxAbs <= 2.0f;

        byte[]     pcmBytes = new byte[floatCount * 2];
        ByteBuffer pcmBuf   = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < floatCount; i++) {
            float s = floatBuf.getFloat(i * 4);
            short v = normalized
                    ? (short) Math.round(Math.max(-1f, Math.min(1f, s)) * 32767f)
                    : (short) Math.round(Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s)));
            pcmBuf.putShort(v);
        }

        nativeProcess(nativeHandle, pcmBytes, 0, pcmBytes.length);

        pcmBuf.position(0);
        floatBuf.position(0);
        for (int i = 0; i < floatCount; i++) {
            short v = pcmBuf.getShort();
            floatBuf.putFloat(normalized ? v / 32768.0f : (float) v);
        }
    }

    // -----------------------------------------------------------------------
    // Native methods
    // -----------------------------------------------------------------------

    private native long nativeCreate(int sampleRateHz, int numChannels, int suppressionLevel);

    private native void nativeProcess(long handle, byte[] audioBytes, int offset, int length);

    private native void nativeRelease(long handle);
}
