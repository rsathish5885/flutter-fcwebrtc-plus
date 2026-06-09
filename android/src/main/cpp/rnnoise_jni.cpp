#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <vector>
#include "rnnoise.h"

#define TAG "RnNoiseJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// rnnoise processes exactly 480 float samples per call at 48 kHz (10 ms).
static constexpr int kFrameSize = 480;

// Suppression levels
//  1 – rnnoise only
//  2 – rnnoise + VAD-probability gate   (default, noticeably quieter background)
//  3 – double-pass rnnoise + VAD gate   (most aggressive, some speech softening)
static constexpr int kDefaultSuppressionLevel = 2;

// ---------------------------------------------------------------------------
// VAD gate constants (level 2+)
//
// kVadFloor / kVadCeil  — hard thresholds: below floor → silence, above ceil → full pass
// kVadHoldFrames        — gate only opens after this many consecutive above-floor frames
//                         (30 ms debounce) so a single loud ambient event can't open it
// kGainAttack           — fast attack  (2 frames ≈ 20 ms) once hold count is satisfied
// kGainRelease          — slow release (20 frames ≈ 200 ms) for natural speech-pause fade
// kGainFastRelease      — fast release (5 frames ≈ 50 ms) when VAD drops below floor
// ---------------------------------------------------------------------------
static constexpr float kVadFloor      = 0.20f;  // below this → gate counts down
static constexpr int   kVadHoldFrames = 4;      // 40 ms debounce — gate opens after this many consecutive above-floor frames
static constexpr int   kHoldOffFrames = 15;     // 150 ms — gate stays open this long after VAD drops (bridges word gaps, quiet consonants)

// Transient detector: if frame energy exceeds background by this factor AND
// rnnoise VAD is below 0.5 (not speech), the frame is hard-muted.
// rnnoise's spectral subtraction on non-speech transients (bang, hit, drop)
// produces partial suppression of some bands → crackling / paper-crush artifact.
// Muting the whole frame is cleaner than letting that partial output through.
// 20× ≈ 13 dB above background — loud enough to be a real impact, not mic noise.
static constexpr float kTransientThreshold  = 20.0f;
static constexpr float kTransientVadCeiling = 0.50f;  // above this → treat as speech, never mute
static constexpr float kGainAttack  = 0.39f;  // 1 - exp(-1/2),  ~20 ms open
static constexpr float kGainRelease = 0.05f;  // 1 - exp(-1/20), ~200 ms close

struct RnNoiseCtx {
    int sampleRateHz;
    int numChannels;
    int suppressionLevel;

    std::vector<DenoiseState*> states;   // first-pass denoiser per channel
    std::vector<DenoiseState*> states2;  // second-pass denoiser (level 3 only)

    std::vector<float> smoothedVad;   // per-channel VAD averaged over ~3 frames to stop quiet-speech gate flutter
    std::vector<float> smoothedGain;  // per-channel smoothed gate gain
    std::vector<int>   holdCount;     // consecutive above-floor frames (gate opens at kVadHoldFrames)
    std::vector<int>   holdOffCount;  // countdown after gate opens; prevents closing on micro-pauses

    std::vector<float> bgEnergy;  // per-channel slow-tracking background energy for transient detection
    std::vector<float> dry;       // original unmodified frame — gated output uses this, not rnnoise
    std::vector<float> in;        // scratch kFrameSize floats
    std::vector<float> out;
};

static RnNoiseCtx* fromHandle(jlong h) {
    return reinterpret_cast<RnNoiseCtx*>(h);
}

static void freeStates(RnNoiseCtx* ctx) {
    for (DenoiseState* s : ctx->states)  { if (s) rnnoise_destroy(s); }
    for (DenoiseState* s : ctx->states2) { if (s) rnnoise_destroy(s); }
    ctx->states.clear();
    ctx->states2.clear();
}

// ---------------------------------------------------------------------------
// nativeCreate(sampleRateHz, numChannels, suppressionLevel) → long handle
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_cloudwebrtc_webrtc_audio_RnNoiseSuppressor_nativeCreate(
        JNIEnv*, jobject, jint sampleRateHz, jint numChannels, jint suppressionLevel) {

    auto* ctx = new RnNoiseCtx();
    ctx->sampleRateHz     = sampleRateHz;
    ctx->numChannels      = numChannels;
    ctx->suppressionLevel = suppressionLevel;
    ctx->bgEnergy.resize(static_cast<size_t>(numChannels), 0.0f);
    ctx->dry.resize(kFrameSize, 0.0f);
    ctx->in.resize(kFrameSize, 0.0f);
    ctx->out.resize(kFrameSize, 0.0f);
    ctx->smoothedVad.resize(static_cast<size_t>(numChannels), 0.0f);
    ctx->smoothedGain.resize(static_cast<size_t>(numChannels), 1.0f);
    ctx->holdCount.resize(static_cast<size_t>(numChannels), 0);
    ctx->holdOffCount.resize(static_cast<size_t>(numChannels), 0);

    ctx->states.resize(static_cast<size_t>(numChannels), nullptr);
    for (int ch = 0; ch < numChannels; ++ch) {
        ctx->states[ch] = rnnoise_create(nullptr);
        if (!ctx->states[ch]) {
            LOGE("rnnoise_create (pass1) failed ch=%d", ch);
            freeStates(ctx);
            delete ctx;
            return 0L;
        }
    }

    if (suppressionLevel >= 3) {
        ctx->states2.resize(static_cast<size_t>(numChannels), nullptr);
        for (int ch = 0; ch < numChannels; ++ch) {
            ctx->states2[ch] = rnnoise_create(nullptr);
            if (!ctx->states2[ch]) {
                LOGE("rnnoise_create (pass2) failed ch=%d", ch);
                freeStates(ctx);
                delete ctx;
                return 0L;
            }
        }
    }

    LOGI("created sr=%d ch=%d level=%d", sampleRateHz, numChannels, suppressionLevel);
    return reinterpret_cast<jlong>(ctx);
}

// ---------------------------------------------------------------------------
// nativeProcess(handle, audioBytes, offset, length)
//
// audioBytes: interleaved int16 PCM, little-endian
// length:     byte count = numFrames * numChannels * sizeof(int16)
//
// rnnoise operates at 48 kHz only; other rates pass through unchanged.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_cloudwebrtc_webrtc_audio_RnNoiseSuppressor_nativeProcess(
        JNIEnv* env, jobject,
        jlong handle, jbyteArray audioBytes, jint offset, jint length) {

    RnNoiseCtx* ctx = fromHandle(handle);
    if (!ctx || !audioBytes || ctx->states.empty()) return;
    if (ctx->sampleRateHz != 48000) return;

    jbyte* raw = env->GetByteArrayElements(audioBytes, nullptr);
    if (!raw) return;

    const int ch             = ctx->numChannels;
    auto*     pcm            = reinterpret_cast<int16_t*>(raw + offset);
    const int samplesPerChan = (length / static_cast<int>(sizeof(int16_t))) / ch;

    for (int base = 0; base + kFrameSize <= samplesPerChan; base += kFrameSize) {
        for (int c = 0; c < ch; ++c) {
            // Deinterleave + int16 → float (int16-range, NOT normalised to ±1)
            float frameEnergy = 0.0f;
            for (int s = 0; s < kFrameSize; ++s) {
                ctx->in[s]  = static_cast<float>(pcm[(base + s) * ch + c]);
                ctx->dry[s] = ctx->in[s];
                frameEnergy += ctx->in[s] * ctx->in[s];
            }
            frameEnergy /= kFrameSize;

            // --- Pass 1 ---
            float vad = rnnoise_process_frame(ctx->states[c],
                                              ctx->out.data(),
                                              ctx->in.data());

            // --- Transient detection ---
            // Update background energy with slow attack so it never chases a spike.
            float& bg     = ctx->bgEnergy[c];
            float  bgCoeff = (frameEnergy > bg) ? 0.01f : 0.05f;
            bg += (frameEnergy - bg) * bgCoeff;
            if (bg < 1.0f) bg = 1.0f;  // guard against zero

            // A sudden energy spike that rnnoise doesn't identify as speech is an
            // impact/transient. Hard-mute and reset gate: letting rnnoise's partial
            // band suppression through produces crackling worse than silence.
            // Use raw vad here (not smoothed) — transient detection needs instant response.
            bool isTransient = (frameEnergy > bg * kTransientThreshold)
                             && (vad < kTransientVadCeiling);
            if (isTransient) {
                for (int s = 0; s < kFrameSize; ++s)
                    pcm[(base + s) * ch + c] = 0;
                ctx->smoothedGain[c] = 0.0f;
                ctx->holdCount[c]    = 0;
                ctx->holdOffCount[c] = 0;
                continue;
            }

            // --- Level 3: second rnnoise pass ---
            if (ctx->suppressionLevel >= 3 && !ctx->states2.empty()) {
                std::swap(ctx->in, ctx->out);
                rnnoise_process_frame(ctx->states2[c],
                                      ctx->out.data(),
                                      ctx->in.data());
            }

            // --- Level 2+: VAD gate ---
            //
            // Debounce (kVadHoldFrames = 30 ms):
            //   Gate only opens after this many consecutive above-floor frames
            //   so brief transients can never open it.
            //
            // Hold-off (kHoldOffFrames = 150 ms):
            //   Once open, gate stays open for at least 150 ms after VAD drops,
            //   bridging natural speech micro-pauses without chatter.
            if (ctx->suppressionLevel >= 2) {
                // Smooth VAD over ~3 frames so brief dips in quiet speech don't
                // reset the hold counter and cause gate flutter / crackling.
                float& sv = ctx->smoothedVad[c];
                sv += (vad - sv) * 0.33f;

                int& hold    = ctx->holdCount[c];
                int& holdOff = ctx->holdOffCount[c];

                if (sv >= kVadFloor) {
                    hold    = std::min(hold + 1, kVadHoldFrames);
                    holdOff = kHoldOffFrames;
                } else {
                    if (holdOff > 0) {
                        holdOff--;
                    } else {
                        hold = 0;
                    }
                }

                bool gateOpen = (hold >= kVadHoldFrames);
                float target  = gateOpen ? 1.0f : 0.0f;

                float& sg    = ctx->smoothedGain[c];
                float  coeff = (target > sg) ? kGainAttack : kGainRelease;
                sg += (target - sg) * coeff;

                // Output the original signal scaled by gate gain — NOT rnnoise output.
                // rnnoise is used only as a VAD detector here; its spectral processing
                // distorts voice harmonics. The gate (open=pass, closed=silence) is the
                // actual noise suppression mechanism.
                for (int s = 0; s < kFrameSize; ++s) {
                    ctx->out[s] = ctx->dry[s] * sg;
                }
            }

            // float → int16, clamp, interleave back
            for (int s = 0; s < kFrameSize; ++s) {
                float v = ctx->out[s];
                v = std::max(-32768.0f, std::min(32767.0f, v));
                pcm[(base + s) * ch + c] = static_cast<int16_t>(v);
            }
        }
    }

    env->ReleaseByteArrayElements(audioBytes, raw, 0);
}

// ---------------------------------------------------------------------------
// nativeRelease(handle)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_cloudwebrtc_webrtc_audio_RnNoiseSuppressor_nativeRelease(
        JNIEnv*, jobject, jlong handle) {
    RnNoiseCtx* ctx = fromHandle(handle);
    if (!ctx) return;
    freeStates(ctx);
    delete ctx;
}
