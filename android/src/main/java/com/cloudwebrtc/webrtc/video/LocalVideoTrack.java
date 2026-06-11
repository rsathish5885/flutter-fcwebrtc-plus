package com.cloudwebrtc.webrtc.video;
 
import android.content.Context;
 
import androidx.annotation.Nullable;
 
import com.cloudwebrtc.webrtc.LocalTrack;
import com.cloudwebrtc.webrtc.video.camera.DeviceOrientationManager;
 
import org.webrtc.JavaI420Buffer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoProcessor;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;
import org.webrtc.YuvHelper;
 
import java.util.ArrayList;
import java.util.List;
 
public class LocalVideoTrack extends LocalTrack implements VideoProcessor {
    public interface ExternalVideoFrameProcessing {
        /**
         * Process a video frame.
         * @param frame
         * @return The processed video frame.
         */
        public abstract void onFrame(VideoFrame frame);
 
        public abstract void setSink(VideoSink videoSink);
    }
 
    public LocalVideoTrack(VideoTrack videoTrack) {
        super(videoTrack);
    }
 
    List<ExternalVideoFrameProcessing> processors = new ArrayList<>();
 
    public void addProcessor(ExternalVideoFrameProcessing processor) {
        synchronized (processors) {
            processors.add(processor);
        }
    }
 
    public void removeProcessor(ExternalVideoFrameProcessing processor) {
        synchronized (processors) {
            processors.remove(processor);
        }
    }
 
    // ── Portrait-up capture lock ──────────────────────────────────────────────
    // When enabled, every frame delivered downstream (video encoder + local
    // preview) is physically rotated to portrait-up and emitted with rotation 0.
    // Baking the rotation into the pixels (instead of only setting the rotation
    // metadata) guarantees the remote sees portrait even if its renderer ignores
    // the video-orientation (CVO) flag, and cancels out any device rotation so
    // capture always behaves as portrait-up regardless of how the phone is held.
    private Context appContext;
    private boolean portraitLockEnabled = false;
    private volatile boolean frontFacing = false;
    private volatile int lockedRotation = -1; // -1 = (re)derive from the next frame
 
    /**
     * Locks the rotation of all outgoing frames of this track to portrait-up.
     *
     * @param context     any context; the application context is used to read display rotation.
     * @param frontFacing true if the active camera is front-facing (user), false for back.
     */
    public void enablePortraitLock(Context context, boolean frontFacing) {
        this.appContext = context.getApplicationContext();
        this.frontFacing = frontFacing;
        this.portraitLockEnabled = true;
        this.lockedRotation = -1;
    }
 
    /** Updates the active camera facing (e.g. after switchCamera) and re-derives the lock. */
    public void setFrontFacing(boolean frontFacing) {
        this.frontFacing = frontFacing;
        this.lockedRotation = -1;
    }
 
    private VideoSink sink = null;
 
    @Override
    public void setSink(@Nullable VideoSink videoSink) {
        VideoSink downstream =
                (videoSink == null || !portraitLockEnabled) ? videoSink : new PortraitLockSink(videoSink);
        synchronized (processors) {
            for (ExternalVideoFrameProcessing processor : processors) {
                processor.setSink(downstream);
            }
        }
        sink = downstream;
    }
 
    /**
     * A sink that physically rotates each frame to portrait-up and emits it with rotation 0, so the
     * orientation is baked into the encoded stream (independent of any receiver honoring the
     * video-orientation/CVO metadata) and the device-rotation component is cancelled out.
     */
    private class PortraitLockSink implements VideoSink {
        private final VideoSink target;
 
        PortraitLockSink(VideoSink target) {
            this.target = target;
        }
 
        @Override
        public void onFrame(VideoFrame frame) {
            int bake = lockedRotation;
            if (bake < 0) {
                bake = computeLockedRotation(frame.getRotation());
                lockedRotation = bake;
            }
 
            // Nothing to bake: forward as-is, but make sure the metadata reads upright.
            if (bake == 0) {
                if (frame.getRotation() == 0) {
                    target.onFrame(frame);
                } else {
                    VideoFrame.Buffer buffer = frame.getBuffer();
                    buffer.retain();
                    VideoFrame upright = new VideoFrame(buffer, 0, frame.getTimestampNs());
                    target.onFrame(upright);
                    upright.release();
                }
                return;
            }
 
            VideoFrame.I420Buffer src = frame.getBuffer().toI420();
            if (src == null) {
                target.onFrame(frame);
                return;
            }
            try {
                int w = src.getWidth();
                int h = src.getHeight();
                boolean swap = (bake % 180) != 0;
                JavaI420Buffer dst = JavaI420Buffer.allocate(swap ? h : w, swap ? w : h);
                YuvHelper.I420Rotate(
                        src.getDataY(), src.getStrideY(),
                        src.getDataU(), src.getStrideU(),
                        src.getDataV(), src.getStrideV(),
                        dst.getDataY(), dst.getStrideY(),
                        dst.getDataU(), dst.getStrideU(),
                        dst.getDataV(), dst.getStrideV(),
                        w, h, bake);
                VideoFrame out = new VideoFrame(dst, 0, frame.getTimestampNs());
                target.onFrame(out);
                out.release();
            } finally {
                src.release();
            }
        }
    }
 
    private int computeLockedRotation(int frameRotation) {
        int device = appContext == null ? 0 : DeviceOrientationManager.getDeviceRotationDegrees(appContext);
        // WebRTC bakes the device rotation into the captured frame's rotation metadata as:
        //   back  camera: frameRotation = (sensorOrientation - device) mod 360
        //   front camera: frameRotation = (sensorOrientation + device) mod 360
        // Cancel the device term so the value we bake is the portrait-up (device == 0) rotation,
        // which equals the camera's sensor orientation.
        if (frontFacing) {
            return ((frameRotation - device) % 360 + 360) % 360;
        }
        return (frameRotation + device) % 360;
    }
 
    @Override
    public void onCapturerStarted(boolean b) {}
 
    @Override
    public void onCapturerStopped() {}
 
    @Override
    public void onFrameCaptured(VideoFrame videoFrame) {
        synchronized (processors) {
            for (ExternalVideoFrameProcessing processor : processors) {
                processor.onFrame(videoFrame);
            }
        }
    }
}
 
 