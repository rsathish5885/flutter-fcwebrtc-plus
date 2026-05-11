//
//  RTCBeautyFilter.mm
//  flutter_webrtc
//
//  Created by lambiengcode on 19/03/2024.
//

#import "RTCBeautyFilter.h"
#import "FaceUnity/FlutterRTCFUManager.h"

@implementation RTCBeautyFilter {
    CGFloat _thinFaceValue;
    CGFloat _whithValue;
    CGFloat _redValue;
    CGFloat _beautyValue;
    CGFloat _eyeValue;
    CGFloat _lipstickValue;
    CGFloat _blusherValue;
    CGFloat _eyeBrightValue;
    CGFloat _filterLevel;
    NSString *_filterName;
    BOOL _didConfigureLipstickDefaults;
    BOOL _didConfigureBlusherDefaults;
}

- (void)applyFaceUnityParam:(NSString *)name value:(id)value {
    [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:name value:value];
}

- (void)syncCurrentFaceUnityState {
    if (!self.useFaceUnity || ![[FlutterRTCFUManager shareManager] isInitBeauty]) {
        return;
    }

    [self applyFaceUnityParam:@"blur_level" value:@(_beautyValue)];
    [self applyFaceUnityParam:@"color_level" value:@(_whithValue)];
    [self applyFaceUnityParam:@"red_level" value:@(_redValue)];
    [self applyFaceUnityParam:@"eye_bright" value:@(_eyeBrightValue)];
    [self applyFaceUnityParam:@"use_landmark" value:@(1)];
    [self applyFaceUnityParam:@"face_shape" value:@(4)];
    [self applyFaceUnityParam:@"face_shape_level" value:@(1)];
    [self applyFaceUnityParam:@"eye_enlarging" value:@(_eyeValue)];
    [self applyFaceUnityParam:@"cheek_thinning" value:@(_thinFaceValue)];

    if (_filterName.length > 0) {
        [self applyFaceUnityParam:@"filter_name" value:_filterName];
    }
    [self applyFaceUnityParam:@"filter_level" value:@(_filterLevel)];

    if (_lipstickValue > 0) {
        [self ensureLipstickDefaultsConfigured];
        [self applyFaceUnityParam:@"makeup_intensity_lip" value:@(_lipstickValue)];
    }

    if (_blusherValue > 0) {
        [self ensureBlusherDefaultsConfigured];
        [self applyFaceUnityParam:@"makeup_intensity_blusher" value:@(_blusherValue)];
    }
}

- (void)ensureLipstickDefaultsConfigured {
    if (_didConfigureLipstickDefaults) {
        return;
    }

    _didConfigureLipstickDefaults = YES;
    [self applyFaceUnityParam:@"is_makeup_on" value:@(1)];
    [self applyFaceUnityParam:@"is_two_color" value:@(0)];
    [self applyFaceUnityParam:@"lip_type" value:@(0)];
    [self applyFaceUnityParam:@"makeup_lip_mask" value:@(1)];
    [self applyFaceUnityParam:@"makeup_lip_color" value:@[@(0.82), @(0.24), @(0.32), @(1.0)]];
    [self applyFaceUnityParam:@"makeup_lip_color2" value:@[@(0.0), @(0.0), @(0.0), @(0.0)]];
}

- (void)ensureBlusherDefaultsConfigured {
    if (_didConfigureBlusherDefaults) {
        return;
    }

    _didConfigureBlusherDefaults = YES;
    [self applyFaceUnityParam:@"is_makeup_on" value:@(1)];
    [self applyFaceUnityParam:@"makeup_blusher_color" value:@[@(0.96), @(0.56), @(0.66), @(1.0)]];
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _useFaceUnity = YES;
        _filterName = @"origin";
        _filterLevel = 0.5;
    }
    return self;
}

- (void)releaseInstance {
    NSLog(@"RTCBeautyFilter deallocated");
}

- (void)setUseFaceUnity:(BOOL)useFaceUnity {
    _useFaceUnity = useFaceUnity;

    if (!useFaceUnity) {
        _didConfigureLipstickDefaults = NO;
        _didConfigureBlusherDefaults = NO;
        return;
    }

    [self syncCurrentFaceUnityState];
}

#pragma mark - Property assignment

- (void)setBeautyValue:(CGFloat)value {
    _beautyValue = value;
    if (self.useFaceUnity) {
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"blur_level" value:@(value)];
    }
}

- (void)setWhithValue:(CGFloat)value {
    _whithValue = value;
    if (self.useFaceUnity) {
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"color_level" value:@(value)];
    }
}

- (void)setRedValue:(CGFloat)value {
    _redValue = value;
    if (self.useFaceUnity) {
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"red_level" value:@(value)];
    }
}

- (void)setThinFaceValue:(CGFloat)value {
    _thinFaceValue = value;
    if (self.useFaceUnity) {
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"use_landmark" value:@(1)];
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"face_shape" value:@(4)];
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"face_shape_level" value:@(1)];
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"cheek_thinning" value:@(value)];
    }
}

- (void)setEyeValue:(CGFloat)value {
    _eyeValue = value;
    if (self.useFaceUnity) {
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"use_landmark" value:@(1)];
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"face_shape" value:@(4)];
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"face_shape_level" value:@(1)];
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"eye_enlarging" value:@(value)];
    }
}

- (void)setLipstickValue:(CGFloat)value {
    _lipstickValue = value;
    if (self.useFaceUnity) {
        [self ensureLipstickDefaultsConfigured];
        [self applyFaceUnityParam:@"makeup_intensity_lip" value:@(value)];
    }
}

- (void)setBlusherValue:(CGFloat)value {
    _blusherValue = value;
    if (self.useFaceUnity) {
        [self ensureBlusherDefaultsConfigured];
        [self applyFaceUnityParam:@"makeup_intensity_blusher" value:@(value)];
    }
}

- (void)setEyeBrightValue:(CGFloat)value {
    _eyeBrightValue = value;
    if (self.useFaceUnity) {
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"eye_bright" value:@(value)];
    }
}

- (void)setFilterName:(NSString *)filterName {
    _filterName = [filterName copy];
    if (self.useFaceUnity && filterName.length > 0) {
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"filter_name" value:filterName];
    }
}

- (void)setFilterLevel:(CGFloat)value {
    _filterLevel = value;
    if (self.useFaceUnity) {
        [[FlutterRTCFUManager shareManager] setParamItemAboutType:FUNamaHandleTypeBeauty name:@"filter_level" value:@(value)];
    }
}

- (CVPixelBufferRef)processVideoFrame:(CVPixelBufferRef)imageBuffer {
    CVPixelBufferRef outputBuffer = imageBuffer;

    if (self.useFaceUnity && [[FlutterRTCFUManager shareManager] isInitBeauty]) {
        CVPixelBufferRef renderedBuffer = [[FlutterRTCFUManager shareManager] renderItemsToPixelBuffer:imageBuffer];
        if (renderedBuffer != NULL) {
            outputBuffer = renderedBuffer;
        }
    }

    if (outputBuffer != NULL) {
        CVPixelBufferRetain(outputBuffer);
    }

    return outputBuffer;
}

@end
