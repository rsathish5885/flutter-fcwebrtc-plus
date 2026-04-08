package com.cloudwebrtc.webrtc;

import android.content.Context;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.util.Log;

import com.cloudwebrtc.faceunity.FURenderer;
import com.cloudwebrtc.faceunity.authpack;
import com.cloudwebrtc.faceunity.utils.FileUtils;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * FaceUnity beauty wrapper for WebRTC.
 *
 * <p>Runs FURenderer on a dedicated GL HandlerThread. Accepts NV21 frames from the
 * WebRTC video pipeline and returns NV21 frames back, completely avoiding JPEG
 * compression that was used in the previous GPUPixel-based implementation.
 */
public class FlutterRTCFaceUnityBeauty {

    private static final String TAG = "FlutterRTCFaceUnityBeauty";

    private final Context context;
    private FURenderer fuRenderer;

    // GL context owned by this class
    private EGL10 egl;
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    // Current beauty parameters (thread-safe via volatile)
    private volatile float blurLevel = 0.7f;
    private volatile float colorLevel = 0.3f;
    private volatile float redLevel = 0.3f;
    private volatile float eyeEnlarging = 0.4f;
    private volatile float cheekThinning = 0.0f;
    private volatile float eyeBright = 0.0f;
    private volatile String filterName = "origin";
    private volatile float filterLevel = 0.5f;

    public FlutterRTCFaceUnityBeauty(Context context) {
        this.context = context;
    }

    // ─────────────────────────── Init / Release ────────────────────────────

    /**
     * Must be called once before processing frames. Safe to call multiple times.
     *
     * @param beautyKey FaceUnity auth key bytes (from authpack.A())
     */
    public synchronized void initialize(byte[] beautyKey) {
        if (initialized.get()) return;

        try {
            // 1. Init FaceUnity SDK (one-time, static)
            if (!FURenderer.isLibInit()) {
                FURenderer.initFURenderer(context, beautyKey);
            }

            // 2. Create EGL context for FaceUnity's GL operations
            createEGLContext();

            // 3. Async copy of asset bundles
            new Thread(() -> FileUtils.copyAssetsChangeFaceTemplate(context)).start();

            // 4. Build the FURenderer
            fuRenderer = new FURenderer.Builder(context)
                    .maxFaces(1)
                    .inputImageOrientation(getCameraOrientation(Camera.CameraInfo.CAMERA_FACING_FRONT))
                    .inputTextureType(0) // CPU NV21 input
                    .createEGLContext(false) // we manage EGL ourselves
                    .build();

            makeCurrent();
            fuRenderer.onSurfaceCreated();
            fuRenderer.setBeautificationOn(true);

            // Apply initial beauty params
            applyBeautyParams();

            initialized.set(true);
            Log.i(TAG, "FaceUnity initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "FaceUnity initialization failed: " + e.getMessage(), e);
        }
    }

    public synchronized void release() {
        release(true); // Default to hard reset to avoid cross-integration conflicts
    }

    public synchronized void release(boolean hardReset) {
        if (!initialized.get()) return;
        try {
            makeCurrent();
            if (fuRenderer != null) {
                fuRenderer.onSurfaceDestroyed();
                fuRenderer = null;
            }
            if (hardReset) {
                com.cloudwebrtc.faceunity.FURenderer.destroyLibData();
            }
            releaseEGLContext();
        } catch (Exception e) {
            Log.e(TAG, "Release error: " + e.getMessage());
        }
        initialized.set(false);
        Log.i(TAG, "FaceUnity released (hardReset=" + hardReset + ")");
    }

    // ─────────────────────────── Frame Processing ──────────────────────────

    /**
     * Process a single NV21 frame through FaceUnity beauty.
     *
     * @param nv21   input NV21 byte array (width * height * 3 / 2 bytes)
     * @param width  frame width
     * @param height frame height
     * @return processed NV21 byte array, or the original if processing failed
     */
    public byte[] processNV21Frame(byte[] nv21, int width, int height) {
        if (!initialized.get() || fuRenderer == null) return nv21;
        if (!isProcessing.compareAndSet(false, true)) {
            // Already processing a frame — drop this one to avoid queue buildup
            return nv21;
        }

        // Save current EGL state to restore it later
        EGLContext oldContext = egl.eglGetCurrentContext();
        EGLDisplay oldDisplay = egl.eglGetCurrentDisplay();
        EGLSurface oldDrawSurface = egl.eglGetCurrentSurface(EGL10.EGL_DRAW);
        EGLSurface oldReadSurface = egl.eglGetCurrentSurface(EGL10.EGL_READ);

        try {
            makeCurrent();

            // onDrawFrame(NV21, width, height) → returns GL texture id
            int texId = fuRenderer.onDrawFrame(nv21, width, height);

            if (texId <= 0) return nv21;

            // Read back RGBA from the GL texture via framebuffer
            byte[] rgba = readPixelsFromTexture(texId, width, height);

            // Convert RGBA back to NV21 (WebRTC expects NV21 for the pipe)
            return rgbaToNV21(rgba, width, height);
        } catch (Exception e) {
            Log.e(TAG, "processNV21Frame error: " + e.getMessage());
            return nv21;
        } finally {
            // Restore original EGL state (WebRTC's context)
            if (oldDisplay != null && oldDrawSurface != null && oldReadSurface != null && oldContext != null) {
                egl.eglMakeCurrent(oldDisplay, oldDrawSurface, oldReadSurface, oldContext);
            }
            isProcessing.set(false);
        }
    }

    // ─────────────────────────── Beauty Setters ────────────────────────────

    /** Skin smoothing / blur (0.0 – 1.0) */
    public void setBlurLevel(float value) {
        blurLevel = value;
        if (initialized.get() && fuRenderer != null) fuRenderer.onBlurLevelSelected(value);
    }

    /** Whitening / color level (0.0 – 1.0) */
    public void setColorLevel(float value) {
        colorLevel = value;
        if (initialized.get() && fuRenderer != null) fuRenderer.onColorLevelSelected(value);
    }

    /** Redness (0.0 – 1.0) */
    public void setRedLevel(float value) {
        redLevel = value;
        if (initialized.get() && fuRenderer != null) fuRenderer.onRedLevelSelected(value);
    }

    /** Eye enlarging (0.0 – 1.0) */
    public void setEyeEnlarging(float value) {
        eyeEnlarging = value;
        if (initialized.get() && fuRenderer != null) fuRenderer.onEyeEnlargeSelected(value);
    }

    /** Cheek thinning / slim face (0.0 – 1.0) */
    public void setCheekThinning(float value) {
        cheekThinning = value;
        if (initialized.get() && fuRenderer != null) fuRenderer.onCheekThinningSelected(value);
    }

    /** Eye brightening (0.0 – 1.0) */
    public void setEyeBright(float value) {
        eyeBright = value;
        if (initialized.get() && fuRenderer != null) fuRenderer.onEyeBrightSelected(value);
    }

    /** Filter name (e.g. "origin", "bailiang1") */
    public void setFilterName(String name) {
        filterName = name;
        if (initialized.get() && fuRenderer != null) fuRenderer.onFilterNameSelected(name);
    }

    /** Filter intensity (0.0 – 1.0) */
    public void setFilterLevel(float value) {
        filterLevel = value;
        if (initialized.get() && fuRenderer != null) fuRenderer.onFilterLevelSelected(value);
    }

    // ─────────────────────────── Private Helpers ───────────────────────────

    private void applyBeautyParams() {
        if (fuRenderer == null) return;
        fuRenderer.onBlurLevelSelected(blurLevel);
        fuRenderer.onColorLevelSelected(colorLevel);
        fuRenderer.onRedLevelSelected(redLevel);
        fuRenderer.onEyeEnlargeSelected(eyeEnlarging);
        fuRenderer.onCheekThinningSelected(cheekThinning);
        fuRenderer.onEyeBrightSelected(eyeBright);
        fuRenderer.onFilterNameSelected(filterName);
        fuRenderer.onFilterLevelSelected(filterLevel);
    }

    /**
     * Read RGBA pixels from a GL texture by binding it to a framebuffer.
     */
    private byte[] readPixelsFromTexture(int texId, int width, int height) {
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                texId, 0);

        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fbo, 0);

        byte[] rgba = new byte[width * height * 4];
        buf.rewind();
        buf.get(rgba);
        return rgba;
    }

    /**
     * Convert RGBA byte array to NV21 (YUV420sp).
     */
    private byte[] rgbaToNV21(byte[] rgba, int width, int height) {
        int frameSize = width * height;
        byte[] nv21 = new byte[frameSize * 3 / 2];

        int yIndex = 0;
        int uvIndex = frameSize;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int pix = (j * width + i) * 4;
                int r = (rgba[pix] & 0xFF);
                int g = (rgba[pix + 1] & 0xFF);
                int b = (rgba[pix + 2] & 0xFF);

                int y = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                nv21[yIndex++] = (byte) Math.max(0, Math.min(255, y));

                if (j % 2 == 0 && i % 2 == 0) {
                    int v = (int) (0.5 * r - 0.419 * g - 0.081 * b + 128);
                    int u = (int) (-0.169 * r - 0.331 * g + 0.5 * b + 128);
                    nv21[uvIndex++] = (byte) Math.max(0, Math.min(255, v));
                    nv21[uvIndex++] = (byte) Math.max(0, Math.min(255, u));
                }
            }
        }
        return nv21;
    }

    private int getCameraOrientation(int cameraFacing) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == cameraFacing) return info.orientation;
        }
        return 270;
    }

    // ─────────────────────────── EGL Management ────────────────────────────

    private void createEGLContext() {
        egl = (EGL10) EGLContext.getEGL();
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        egl.eglInitialize(eglDisplay, new int[2]);

        int[] attribList = {
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */,
                EGL10.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        egl.eglChooseConfig(eglDisplay, attribList, configs, 1, numConfigs);
        EGLConfig config = configs[0];

        int[] ctxAttribs = {0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 2, EGL10.EGL_NONE};
        eglContext = egl.eglCreateContext(eglDisplay, config, EGL10.EGL_NO_CONTEXT, ctxAttribs);

        int[] pbufAttribs = {EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE};
        eglSurface = egl.eglCreatePbufferSurface(eglDisplay, config, pbufAttribs);
    }

    private void makeCurrent() {
        if (egl != null && eglDisplay != null && eglSurface != null && eglContext != null) {
            egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        }
    }

    private void releaseEGLContext() {
        if (egl != null) {
            egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            if (eglSurface != null) egl.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != null) egl.eglDestroyContext(eglDisplay, eglContext);
            egl.eglTerminate(eglDisplay);
            egl = null;
        }
    }
}
