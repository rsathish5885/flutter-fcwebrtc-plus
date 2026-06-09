#import <Foundation/Foundation.h>
#import "AudioProcessingAdapter.h"

NS_ASSUME_NONNULL_BEGIN

@interface RnNoiseSuppressor : NSObject <ExternalAudioProcessingDelegate>

@property(nonatomic, assign, getter=isEnabled) BOOL enabled;

- (void)setSuppressionLevel:(int)level;  // 1 = mild, 2 = moderate (default), 3 = aggressive

@end

NS_ASSUME_NONNULL_END
