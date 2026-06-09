#import "RnNoiseSuppressor.h"
#include "rnnoise.h"
#include <vector>
#include <algorithm>
#include <cstring>

static const int   kFrameSize            = 480;
static const float kVadFloor             = 0.20f;
static const int   kVadHoldFrames        = 4;
static const int   kHoldOffFrames        = 15;
static const float kTransientThreshold   = 20.0f;
static const float kTransientVadCeiling  = 0.50f;
static const float kGainAttack           = 0.39f;
static const float kGainRelease          = 0.05f;

@implementation RnNoiseSuppressor {
    int _suppressionLevel;
    std::vector<DenoiseState*> _states;
    std::vector<DenoiseState*> _states2;
    std::vector<float> _smoothedVad;
    std::vector<float> _smoothedGain;
    std::vector<int>   _holdCount;
    std::vector<int>   _holdOffCount;
    std::vector<float> _bgEnergy;
    std::vector<float> _dry;
    std::vector<float> _in;
    std::vector<float> _out;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _enabled = YES;
        _suppressionLevel = 2;
        _dry.resize(kFrameSize);
        _in.resize(kFrameSize);
        _out.resize(kFrameSize);
    }
    return self;
}

- (void)setSuppressionLevel:(int)level {
    _suppressionLevel = level;
    if (!_states.empty()) {
        int ch = (int)_states.size();
        [self freeStates];
        [self allocStatesForChannels:ch];
    }
}

- (void)freeStates {
    for (DenoiseState* s : _states)  { if (s) rnnoise_destroy(s); }
    for (DenoiseState* s : _states2) { if (s) rnnoise_destroy(s); }
    _states.clear();
    _states2.clear();
}

- (void)allocStatesForChannels:(int)channels {
    _states.resize(channels);
    _smoothedVad.assign(channels, 0.0f);
    _smoothedGain.assign(channels, 1.0f);
    _holdCount.assign(channels, 0);
    _holdOffCount.assign(channels, 0);
    _bgEnergy.assign(channels, 0.0f);
    for (int c = 0; c < channels; c++) {
        _states[c] = rnnoise_create(nullptr);
    }
    if (_suppressionLevel >= 3) {
        _states2.resize(channels);
        for (int c = 0; c < channels; c++) {
            _states2[c] = rnnoise_create(nullptr);
        }
    }
}

- (void)audioProcessingInitializeWithSampleRate:(size_t)sampleRateHz channels:(size_t)channels {
    [self freeStates];
    if (sampleRateHz != 48000) return;
    [self allocStatesForChannels:(int)channels];
}

- (void)audioProcessingProcess:(RTC_OBJC_TYPE(RTCAudioBuffer) *)audioBuffer {
    if (!_enabled || _states.empty()) return;
    if ((int)audioBuffer.frames != kFrameSize) return;

    int ch = (int)audioBuffer.channels;
    for (int c = 0; c < ch && c < (int)_states.size(); c++) {
        float* buf = [audioBuffer rawBufferForChannel:c];

        // Save dry copy + compute frame energy
        float frameEnergy = 0.0f;
        for (int s = 0; s < kFrameSize; s++) {
            _in[s]  = buf[s];
            _dry[s] = buf[s];
            frameEnergy += buf[s] * buf[s];
        }
        frameEnergy /= kFrameSize;

        // Pass 1 — VAD score
        float vad = rnnoise_process_frame(_states[c], _out.data(), _in.data());

        // Transient detection — hard-mute sudden high-energy non-speech events
        float& bg = _bgEnergy[c];
        float bgCoeff = (frameEnergy > bg) ? 0.01f : 0.05f;
        bg += (frameEnergy - bg) * bgCoeff;
        if (bg < 1.0f) bg = 1.0f;

        bool isTransient = (frameEnergy > bg * kTransientThreshold) && (vad < kTransientVadCeiling);
        if (isTransient) {
            memset(buf, 0, kFrameSize * sizeof(float));
            _smoothedGain[c] = 0.0f;
            _holdCount[c]    = 0;
            _holdOffCount[c] = 0;
            continue;
        }

        // Level 3: second rnnoise pass
        if (_suppressionLevel >= 3 && !_states2.empty()) {
            std::swap(_in, _out);
            rnnoise_process_frame(_states2[c], _out.data(), _in.data());
        }

        if (_suppressionLevel >= 2) {
            // Smooth VAD over ~3 frames to prevent quiet-speech gate flutter
            float& sv = _smoothedVad[c];
            sv += (vad - sv) * 0.33f;

            int& hold    = _holdCount[c];
            int& holdOff = _holdOffCount[c];
            if (sv >= kVadFloor) {
                hold    = std::min(hold + 1, kVadHoldFrames);
                holdOff = kHoldOffFrames;
            } else {
                if (holdOff > 0) holdOff--;
                else hold = 0;
            }

            bool gateOpen = (hold >= kVadHoldFrames);
            float target  = gateOpen ? 1.0f : 0.0f;

            float& sg   = _smoothedGain[c];
            float coeff = (target > sg) ? kGainAttack : kGainRelease;
            sg += (target - sg) * coeff;

            // Level 3: rnnoise-denoised × gate (traffic/heavy noise)
            // Level 2: dry × gate (natural voice quality)
            for (int s = 0; s < kFrameSize; s++) {
                float src = (_suppressionLevel >= 3) ? _out[s] : _dry[s];
                buf[s] = src * sg;
            }
        } else {
            // Level 1: rnnoise output only
            for (int s = 0; s < kFrameSize; s++) {
                buf[s] = _out[s];
            }
        }

        // Clamp to int16 range
        for (int s = 0; s < kFrameSize; s++) {
            if (buf[s] >  32767.0f) buf[s] =  32767.0f;
            if (buf[s] < -32768.0f) buf[s] = -32768.0f;
        }
    }
}

- (void)audioProcessingRelease {
    [self freeStates];
}

- (void)dealloc {
    [self freeStates];
}

@end
