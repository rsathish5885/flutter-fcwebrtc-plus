package com.cloudwebrtc.webrtc.audio;

import org.webrtc.ExternalAudioProcessingFactory;

public class AudioProcessingController {
    /**
     * Applied to the audio stream after microphone capture.
     * RnNoiseSuppressor is registered here so it runs on every captured frame.
     */
    public final AudioProcessingAdapter capturePostProcessing = new AudioProcessingAdapter();

    /**
     * Applied to the audio stream before speaker render.
     */
    public final AudioProcessingAdapter renderPreProcessing = new AudioProcessingAdapter();

    public final RnNoiseSuppressor noiseSuppressor = new RnNoiseSuppressor();

    public ExternalAudioProcessingFactory externalAudioProcessingFactory;

    public AudioProcessingController() {
        externalAudioProcessingFactory = new ExternalAudioProcessingFactory();
        capturePostProcessing.addProcessor(noiseSuppressor);
        externalAudioProcessingFactory.setCapturePostProcessing(capturePostProcessing);
        externalAudioProcessingFactory.setRenderPreProcessing(renderPreProcessing);
    }

    public void setNoiseSuppressionEnabled(boolean enabled) {
        noiseSuppressor.setEnabled(enabled);
    }

    /** 1 = mild, 2 = moderate (default), 3 = aggressive */
    public void setNoiseSuppressionLevel(int level) {
        noiseSuppressor.setSuppressionLevel(level);
    }
}
