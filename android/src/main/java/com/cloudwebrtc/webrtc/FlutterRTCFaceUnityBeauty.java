package com.cloudwebrtc.webrtc;

import android.content.Context;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.cloudwebrtc.faceunity.FURenderer;
import com.cloudwebrtc.faceunity.utils.FileUtils;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * FaceUnity beauty wrapper for WebRTC.
 *
 * All EGL and FaceUnity calls are confined to a single dedicated HandlerThread
 * ("FaceUnity-GL"). This completely isolates FaceUnity's GL context from
 * WebRTC's encoder/decoder threads, preventing EGL_BAD_ACCESS and MediaCodec
 * crashes that occur when eglMakeCurrent is called on WebRTC-owned threads.
 */
public class FlutterRTCFaceUnityBeauty {

    private static final String TAG = "FlutterRTCFaceUnityBeauty";

    private final Context context;
    private FURenderer fuRenderer;

    // Dedicated GL thread — ALL EGL / FaceUnity calls happen exclusively here
    private HandlerThread fuThread;
    private Handler fuHandler;

    // EGL objects — created and used only on fuThread
    private EGL10 egl;
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    private final AtomicBoolean initialized  = new AtomicBoolean(false);
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    // Last successfully beauty-processed frame — returned for frames that arrive while
    // FaceUnity is busy, so output is always beauty (no flicker) at full camera FPS.
    private volatile byte[] cachedBeautyNV21 = null;

    // Beauty parameters — written from any thread, applied on fuThread
    private volatile float blurLevel     = 0.7f;
    private volatile float colorLevel    = 0.3f;
    private volatile float redLevel      = 0.3f;
    private volatile float eyeEnlarging  = 0.4f;
    private volatile float cheekThinning = 0.0f;
    private volatile float eyeBright     = 0.0f;
    private volatile String filterName   = "origin";
    private volatile float filterLevel   = 0.5f;

    public FlutterRTCFaceUnityBeauty(Context context) {
        this.context = context;
    }

    // ─────────────────────────── Init / Release ────────────────────────────

    public synchronized void initialize(byte[] beautyKey) {
        if (initialized.get()) return;

        fuThread = new HandlerThread("FaceUnity-GL");
        fuThread.start();
        fuHandler = new Handler(fuThread.getLooper());

        CountDownLatch latch = new CountDownLatch(1);
        fuHandler.post(() -> {
            try {
                if (!FURenderer.isLibInit()) {
                    FURenderer.initFURenderer(context, beautyKey);
                }

                createEGLContext();
                // Make context current once — it stays current on fuThread forever.
                // No makeCurrent/release per-frame: fuThread is dedicated and never
                // runs any other EGL code, so the context is always available.
                makeCurrent();

                new Thread(() -> FileUtils.copyAssetsChangeFaceTemplate(context)).start();

                fuRenderer = new FURenderer.Builder(context)
                        .maxFaces(1)
                        .inputImageOrientation(
                                getCameraOrientation(Camera.CameraInfo.CAMERA_FACING_FRONT))
                        .inputTextureType(0)      // CPU NV21 input
                        .createEGLContext(false)  // we own the EGL context
                        .build();

                fuRenderer.onSurfaceCreated();
                fuRenderer.setBeautificationOn(true);
                applyBeautyParams();

                initialized.set(true);
                Log.i(TAG, "FaceUnity initialized successfully");
            } catch (Exception e) {
                Log.e(TAG, "FaceUnity initialization failed: " + e.getMessage(), e);
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                Log.e(TAG, "FaceUnity initialization timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void release() {
        release(true);
    }

    public synchronized void release(boolean hardReset) {
        if (!initialized.get()) return;
        initialized.set(false);

        if (fuHandler != null) {
            CountDownLatch latch = new CountDownLatch(1);
            fuHandler.post(() -> {
                try {
                    if (fuRenderer != null) {
                        fuRenderer.onSurfaceDestroyed();
                        fuRenderer = null;
                    }
                    if (hardReset) {
                        FURenderer.destroyLibData();
                    }
                    releaseEGLContext();
                } catch (Exception e) {
                    Log.e(TAG, "Release error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            fuThread.quitSafely();
            fuThread = null;
            fuHandler = null;
        }

        Log.i(TAG, "FaceUnity released (hardReset=" + hardReset + ")");
    }

    // ─────────────────────────── Frame Processing ──────────────────────────

    public byte[] processNV21Frame(byte[] nv21, int width, int height) {
        if (!initialized.get() || fuHandler == null) return nv21;

        // If FaceUnity is free, submit this frame for async processing.
        // The capture thread never blocks — it returns the cached result immediately.
        if (isProcessing.compareAndSet(false, true)) {
            fuHandler.post(() -> {
                try {
                    int texId = fuRenderer.onDrawFrame(nv21, width, height);
                    if (texId > 0) {
                        byte[] rgba = readPixelsFromTexture(texId, width, height);
                        cachedBeautyNV21 = rgbaToNV21(rgba, width, height);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "processNV21Frame error: " + e.getMessage());
                } finally {
                    isProcessing.set(false);
                }
            });
        }

        // Return the latest cached beauty result (one frame behind at most).
        // Guard: only use the cache if it matches the current frame's dimensions —
        // a mismatch means the camera resolution or rotation changed, which would cause
        // an IndexOutOfBoundsException in the NV21→VideoFrame conversion.
        byte[] cached = cachedBeautyNV21;
        if (cached != null && cached.length == width * height * 3 / 2) {
            return cached;
        }
        return nv21;
    }

    // ─────────────────────────── Beauty Setters ────────────────────────────

    public void setBlurLevel(float value) {
        blurLevel = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onBlurLevelSelected(value);
        });
    }

    public void setColorLevel(float value) {
        colorLevel = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onColorLevelSelected(value);
        });
    }

    public void setRedLevel(float value) {
        redLevel = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onRedLevelSelected(value);
        });
    }

    public void setEyeEnlarging(float value) {
        eyeEnlarging = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onEyeEnlargeSelected(value);
        });
    }

    public void setCheekThinning(float value) {
        cheekThinning = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onCheekThinningSelected(value);
        });
    }

    public void setEyeBright(float value) {
        eyeBright = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onEyeBrightSelected(value);
        });
    }

    public void setFilterName(String name) {
        filterName = name;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onFilterNameSelected(name);
        });
    }

    public void setFilterLevel(float value) {
        filterLevel = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onFilterLevelSelected(value);
        });
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

    private byte[] rgbaToNV21(byte[] rgba, int width, int height) {
        int frameSize = width * height;
        byte[] nv21 = new byte[frameSize * 3 / 2];

        int yIndex = 0;
        int uvIndex = frameSize;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int pix = (j * width + i) * 4;
                int r = rgba[pix]     & 0xFF;
                int g = rgba[pix + 1] & 0xFF;
                int b = rgba[pix + 2] & 0xFF;

                int y = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                nv21[yIndex++] = (byte) Math.max(0, Math.min(255, y));

                if (j % 2 == 0 && i % 2 == 0) {
                    int v = (int) ( 0.500 * r - 0.419 * g - 0.081 * b + 128);
                    int u = (int) (-0.169 * r - 0.331 * g + 0.500 * b + 128);
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

    // ─────────────────────── EGL — called only from fuThread ───────────────

    private void createEGLContext() {
        egl = (EGL10) EGLContext.getEGL();
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        egl.eglInitialize(eglDisplay, new int[2]);

        int[] attribList = {
                EGL10.EGL_RED_SIZE,         8,
                EGL10.EGL_GREEN_SIZE,       8,
                EGL10.EGL_BLUE_SIZE,        8,
                EGL10.EGL_ALPHA_SIZE,       8,
                EGL10.EGL_RENDERABLE_TYPE,  4, /* EGL_OPENGL_ES2_BIT */
                EGL10.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        egl.eglChooseConfig(eglDisplay, attribList, configs, 1, numConfigs);

        int[] ctxAttribs = {0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 2, EGL10.EGL_NONE};
        eglContext = egl.eglCreateContext(
                eglDisplay, configs[0], EGL10.EGL_NO_CONTEXT, ctxAttribs);

        int[] pbufAttribs = {EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE};
        eglSurface = egl.eglCreatePbufferSurface(eglDisplay, configs[0], pbufAttribs);
    }

    private void makeCurrent() {
        if (egl != null && eglDisplay != null && eglSurface != null && eglContext != null) {
            egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        }
    }

    private void releaseEGLContext() {
        if (egl != null) {
            egl.eglMakeCurrent(eglDisplay,
                    EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            if (eglSurface != null) egl.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != null) egl.eglDestroyContext(eglDisplay, eglContext);
            egl.eglTerminate(eglDisplay);
            egl = null;
        }
    }
}
