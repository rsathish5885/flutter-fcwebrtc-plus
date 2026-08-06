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
import java.util.concurrent.atomic.AtomicInteger;

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

    // Frame-error throttle: log at most once every 100 frames to avoid flooding the file
    private final AtomicInteger frameErrorCount   = new AtomicInteger(0);
    private final AtomicInteger busySkipCount     = new AtomicInteger(0); // frames skipped because GL thread was busy
    private final AtomicInteger cacheNullCount    = new AtomicInteger(0); // frames where cache was null (no result yet)
    private final AtomicInteger cacheMismatchCount= new AtomicInteger(0); // frames where cache size didn't match
    private static final int FRAME_ERROR_LOG_INTERVAL = 100;

    public FlutterRTCFaceUnityBeauty(Context context) {
        this.context = context;
    }

    // ─────────────────────────── Init / Release ────────────────────────────

    public synchronized void initialize(byte[] beautyKey) {
        BeautyLogger.log("initialize() called | alreadyInit=" + initialized.get());
        if (initialized.get()) return;

        fuThread = new HandlerThread("FaceUnity-GL");
        fuThread.start();
        fuHandler = new Handler(fuThread.getLooper());

        CountDownLatch latch = new CountDownLatch(1);
        fuHandler.post(() -> {
            try {
                boolean libAlreadyInit = FURenderer.isLibInit();
                BeautyLogger.log("FURenderer.isLibInit()=" + libAlreadyInit);

                if (!libAlreadyInit) {
                    BeautyLogger.log("Calling FURenderer.initFURenderer...");
                    FURenderer.initFURenderer(context, beautyKey);
                    BeautyLogger.log("FURenderer.initFURenderer done");
                }

                BeautyLogger.log("Creating EGL context...");
                createEGLContext();
                BeautyLogger.log("EGL context created — eglContext=" + eglContext
                        + " eglSurface=" + eglSurface);

                // Make context current once — it stays current on fuThread forever.
                makeCurrent();
                BeautyLogger.log("EGL makeCurrent done");

                new Thread(() -> FileUtils.copyAssetsChangeFaceTemplate(context)).start();

                int cameraOrientation = getCameraOrientation(Camera.CameraInfo.CAMERA_FACING_FRONT);
                BeautyLogger.log("Camera orientation (front)=" + cameraOrientation);

                fuRenderer = new FURenderer.Builder(context)
                        .maxFaces(1)
                        .inputImageOrientation(cameraOrientation)
                        .inputTextureType(0)      // CPU NV21 input
                        .createEGLContext(false)  // we own the EGL context
                        .build();

                BeautyLogger.log("FURenderer built, calling onSurfaceCreated...");
                fuRenderer.onSurfaceCreated();
                fuRenderer.setBeautificationOn(true);
                applyBeautyParams();

                initialized.set(true);
                BeautyLogger.log("FaceUnity initialized successfully ✓");
                Log.i(TAG, "FaceUnity initialized successfully");
            } catch (Exception e) {
                BeautyLogger.error("FaceUnity initialization FAILED: " + e.getMessage(), e);
                Log.e(TAG, "FaceUnity initialization failed: " + e.getMessage(), e);
            } finally {
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            if (!completed) {
                BeautyLogger.error("FaceUnity initialization TIMED OUT after 5s");
                Log.e(TAG, "FaceUnity initialization timed out");
            }
        } catch (InterruptedException e) {
            BeautyLogger.error("FaceUnity initialization interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void release() {
        release(true);
    }

    public synchronized void release(boolean hardReset) {
        BeautyLogger.log("release(hardReset=" + hardReset + ") | initialized=" + initialized.get());
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
                    BeautyLogger.log("release complete (hardReset=" + hardReset + ")");
                } catch (Exception e) {
                    BeautyLogger.error("release error", e);
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
        if (!initialized.get() || fuHandler == null) {
            // Log once when beauty is called but not initialized
            if (frameErrorCount.getAndIncrement() % FRAME_ERROR_LOG_INTERVAL == 0) {
                BeautyLogger.warn("processNV21Frame: beauty not ready"
                        + " | initialized=" + initialized.get()
                        + " | fuHandler=" + (fuHandler != null)
                        + " | frame#" + frameErrorCount.get());
            }
            return nv21;
        }

        if (isProcessing.compareAndSet(false, true)) {
            fuHandler.post(() -> {
                try {
                    int texId = fuRenderer.onDrawFrame(nv21, width, height);
                    if (texId > 0) {
                        byte[] rgba = readPixelsFromTexture(texId, width, height);
                        cachedBeautyNV21 = rgbaToNV21(rgba, width, height);
                    } else {
                        // texId <= 0 — FaceUnity returned no output (auth fail, GPU issue, etc.)
                        int errN = frameErrorCount.getAndIncrement();
                        if (errN % FRAME_ERROR_LOG_INTERVAL == 0) {
                            BeautyLogger.warn("onDrawFrame returned texId=" + texId
                                    + " | size=" + width + "x" + height
                                    + " | texId=0 count=" + errN
                                    + " | busySkips=" + busySkipCount.get()
                                    + " | cacheMismatches=" + cacheMismatchCount.get());
                        }
                    }
                } catch (Exception e) {
                    BeautyLogger.error("processNV21Frame exception | size=" + width + "x" + height, e);
                    Log.e(TAG, "processNV21Frame error: " + e.getMessage());
                } finally {
                    isProcessing.set(false);
                }
            });
        } else {
            // GL thread is still processing previous frame — this frame is skipped
            int skips = busySkipCount.incrementAndGet();
            if (skips % FRAME_ERROR_LOG_INTERVAL == 0) {
                BeautyLogger.warn("processNV21Frame: GL thread BUSY, frame skipped"
                        + " | totalBusySkips=" + skips
                        + " | size=" + width + "x" + height
                        + " — if this is high, GL thread is too slow for camera FPS");
            }
        }

        byte[] cached = cachedBeautyNV21;
        if (cached == null) {
            int nullN = cacheNullCount.incrementAndGet();
            if (nullN % FRAME_ERROR_LOG_INTERVAL == 0) {
                BeautyLogger.warn("processNV21Frame: cachedBeautyNV21 is still null after " + nullN
                        + " frames — FaceUnity has not produced any output yet"
                        + " | busySkips=" + busySkipCount.get());
            }
            return nv21;
        }
        if (cached.length != width * height * 3 / 2) {
            int mismatch = cacheMismatchCount.incrementAndGet();
            if (mismatch % FRAME_ERROR_LOG_INTERVAL == 0) {
                BeautyLogger.warn("processNV21Frame: cache size MISMATCH"
                        + " | cached=" + cached.length
                        + " expected=" + (width * height * 3 / 2)
                        + " | size=" + width + "x" + height
                        + " | mismatchCount=" + mismatch
                        + " — resolution changed, waiting for new cache");
            }
            return nv21;
        }
        return cached;
    }

    // ─────────────────────────── Beauty Setters ────────────────────────────

    public void setBlurLevel(float value) {
        BeautyLogger.log("setBlurLevel(" + value + ") | fuHandler=" + (fuHandler != null));
        blurLevel = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onBlurLevelSelected(value);
            else BeautyLogger.warn("setBlurLevel: fuRenderer is null");
        });
    }

    public void setColorLevel(float value) {
        BeautyLogger.log("setColorLevel(" + value + ") | fuHandler=" + (fuHandler != null));
        colorLevel = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onColorLevelSelected(value);
            else BeautyLogger.warn("setColorLevel: fuRenderer is null");
        });
    }

    public void setRedLevel(float value) {
        BeautyLogger.log("setRedLevel(" + value + ") | fuHandler=" + (fuHandler != null));
        redLevel = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onRedLevelSelected(value);
            else BeautyLogger.warn("setRedLevel: fuRenderer is null");
        });
    }

    public void setEyeEnlarging(float value) {
        BeautyLogger.log("setEyeEnlarging(" + value + ") | fuHandler=" + (fuHandler != null));
        eyeEnlarging = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onEyeEnlargeSelected(value);
            else BeautyLogger.warn("setEyeEnlarging: fuRenderer is null");
        });
    }

    public void setCheekThinning(float value) {
        BeautyLogger.log("setCheekThinning(" + value + ") | fuHandler=" + (fuHandler != null));
        cheekThinning = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onCheekThinningSelected(value);
            else BeautyLogger.warn("setCheekThinning: fuRenderer is null");
        });
    }

    public void setEyeBright(float value) {
        BeautyLogger.log("setEyeBright(" + value + ") | fuHandler=" + (fuHandler != null));
        eyeBright = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onEyeBrightSelected(value);
            else BeautyLogger.warn("setEyeBright: fuRenderer is null");
        });
    }

    public void setFilterName(String name) {
        BeautyLogger.log("setFilterName(" + name + ") | fuHandler=" + (fuHandler != null));
        filterName = name;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onFilterNameSelected(name);
            else BeautyLogger.warn("setFilterName: fuRenderer is null");
        });
    }

    public void setFilterLevel(float value) {
        BeautyLogger.log("setFilterLevel(" + value + ") | fuHandler=" + (fuHandler != null));
        filterLevel = value;
        if (fuHandler != null) fuHandler.post(() -> {
            if (fuRenderer != null) fuRenderer.onFilterLevelSelected(value);
            else BeautyLogger.warn("setFilterLevel: fuRenderer is null");
        });
    }

    // ─────────────────────────── Private Helpers ───────────────────────────

    private void applyBeautyParams() {
        if (fuRenderer == null) {
            BeautyLogger.warn("applyBeautyParams: fuRenderer is null");
            return;
        }
        BeautyLogger.log("applyBeautyParams: blur=" + blurLevel
                + " color=" + colorLevel + " red=" + redLevel
                + " eye=" + eyeEnlarging + " cheek=" + cheekThinning
                + " eyeBright=" + eyeBright
                + " filter=" + filterName + "@" + filterLevel);
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

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            BeautyLogger.error("readPixelsFromTexture: FBO incomplete, status=0x"
                    + Integer.toHexString(status));
        }

        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);

        int glErr = GLES20.glGetError();
        if (glErr != GLES20.GL_NO_ERROR) {
            BeautyLogger.error("glReadPixels error=0x" + Integer.toHexString(glErr)
                    + " | size=" + width + "x" + height);
        }

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
        BeautyLogger.log("getCameraOrientation: numCameras=" + numCameras);
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == cameraFacing) return info.orientation;
        }
        BeautyLogger.warn("getCameraOrientation: front camera not found, defaulting to 270");
        return 270;
    }

    // ─────────────────────── EGL — called only from fuThread ───────────────

    private void createEGLContext() {
        egl = (EGL10) EGLContext.getEGL();
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

        int[] version = new int[2];
        boolean initOk = egl.eglInitialize(eglDisplay, version);
        BeautyLogger.log("eglInitialize ok=" + initOk + " version=" + version[0] + "." + version[1]);

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
        boolean chooseOk = egl.eglChooseConfig(eglDisplay, attribList, configs, 1, numConfigs);
        BeautyLogger.log("eglChooseConfig ok=" + chooseOk + " numConfigs=" + numConfigs[0]);

        if (numConfigs[0] == 0) {
            BeautyLogger.error("eglChooseConfig: no EGL configs found — GPU may not support ES2");
        }

        int[] ctxAttribs = {0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 2, EGL10.EGL_NONE};
        eglContext = egl.eglCreateContext(
                eglDisplay, configs[0], EGL10.EGL_NO_CONTEXT, ctxAttribs);

        int eglErr = egl.eglGetError();
        BeautyLogger.log("eglCreateContext result=" + eglContext
                + " error=0x" + Integer.toHexString(eglErr));

        if (eglContext == EGL10.EGL_NO_CONTEXT) {
            BeautyLogger.error("eglCreateContext FAILED — EGL_NO_CONTEXT returned"
                    + " | eglError=0x" + Integer.toHexString(eglErr));
        }

        int[] pbufAttribs = {EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE};
        eglSurface = egl.eglCreatePbufferSurface(eglDisplay, configs[0], pbufAttribs);

        eglErr = egl.eglGetError();
        BeautyLogger.log("eglCreatePbufferSurface result=" + eglSurface
                + " error=0x" + Integer.toHexString(eglErr));

        if (eglSurface == EGL10.EGL_NO_SURFACE) {
            BeautyLogger.error("eglCreatePbufferSurface FAILED — EGL_NO_SURFACE returned"
                    + " | eglError=0x" + Integer.toHexString(eglErr));
        }
    }

    private void makeCurrent() {
        if (egl != null && eglDisplay != null && eglSurface != null && eglContext != null) {
            boolean ok = egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            int err = egl.eglGetError();
            BeautyLogger.log("eglMakeCurrent ok=" + ok + " error=0x" + Integer.toHexString(err));
            if (!ok) {
                BeautyLogger.error("eglMakeCurrent FAILED | eglError=0x" + Integer.toHexString(err));
            }
        } else {
            BeautyLogger.error("makeCurrent skipped — one or more EGL objects are null"
                    + " | egl=" + egl + " display=" + eglDisplay
                    + " surface=" + eglSurface + " context=" + eglContext);
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
            BeautyLogger.log("EGL context released");
        }
    }
}
