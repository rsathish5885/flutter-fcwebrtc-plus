#import "AudioManager.h"
#import "AudioProcessingAdapter.h"

@implementation AudioManager {
  RTCDefaultAudioProcessingModule* _audioProcessingModule;
  AudioProcessingAdapter* _capturePostProcessingAdapter;
  AudioProcessingAdapter* _renderPreProcessingAdapter;
  RnNoiseSuppressor* _noiseSuppressor;
}

@synthesize capturePostProcessingAdapter = _capturePostProcessingAdapter;
@synthesize renderPreProcessingAdapter = _renderPreProcessingAdapter;
@synthesize audioProcessingModule = _audioProcessingModule;
@synthesize noiseSuppressor = _noiseSuppressor;

+ (instancetype)sharedInstance {
  static dispatch_once_t onceToken;
  static AudioManager* sharedInstance = nil;
  dispatch_once(&onceToken, ^{
    sharedInstance = [[self alloc] init];
  });
  return sharedInstance;
}

- (instancetype)init {
  if (self = [super init]) {
    _audioProcessingModule = [[RTCDefaultAudioProcessingModule alloc] init];
    _capturePostProcessingAdapter = [[AudioProcessingAdapter alloc] init];
    _renderPreProcessingAdapter = [[AudioProcessingAdapter alloc] init];
    _audioProcessingModule.capturePostProcessingDelegate = _capturePostProcessingAdapter;
    _audioProcessingModule.renderPreProcessingDelegate = _renderPreProcessingAdapter;

    _noiseSuppressor = [[RnNoiseSuppressor alloc] init];
    [_capturePostProcessingAdapter addProcessing:_noiseSuppressor];
  }
  return self;
}

- (void)addLocalAudioRenderer:(nonnull id<RTCAudioRenderer>)renderer {
  [_capturePostProcessingAdapter addAudioRenderer:renderer];
}

- (void)removeLocalAudioRenderer:(nonnull id<RTCAudioRenderer>)renderer {
  [_capturePostProcessingAdapter removeAudioRenderer:renderer];
}

- (void)addRemoteAudioSink:(nonnull id<RTCAudioRenderer>)sink {
  [_renderPreProcessingAdapter addAudioRenderer:sink];
}

- (void)removeRemoteAudioSink:(nonnull id<RTCAudioRenderer>)sink {
  [_renderPreProcessingAdapter removeAudioRenderer:sink];
}

@end
