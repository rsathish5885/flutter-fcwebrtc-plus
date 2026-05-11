// Copyright (c) 2022 NetEase, Inc. All rights reserved.
// Use of this source code is governed by a MIT license that can be
// found in the LICENSE file.

#import "FlutterRTCFUManager.h"
#import <objc/message.h>

static FlutterRTCFUManager *shareManager = NULL;

@implementation FlutterRTCFUManager

+ (FlutterRTCFUManager *)shareManager {
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    shareManager = [[FlutterRTCFUManager alloc] init];
  });

  return shareManager;
}

- (id)sharedFaceUnityManager {
  Class managerClass = NSClassFromString(@"FUManager");
  if (managerClass == Nil) {
    NSLog(@"nertc_faceunity FUManager class is unavailable");
    return nil;
  }

  SEL shareSelector = @selector(shareManager);
  if (![managerClass respondsToSelector:shareSelector]) {
    NSLog(@"nertc_faceunity FUManager does not respond to shareManager");
    return nil;
  }

  id (*sendShareManager)(id, SEL) = (id (*)(id, SEL))objc_msgSend;
  return sendShareManager(managerClass, shareSelector);
}

- (BOOL)setupWithKey:(FlutterStandardTypedData *)key {
  if (key == nil || key.data.length == 0) {
    return NO;
  }

  id manager = [self sharedFaceUnityManager];
  SEL selector = @selector(setupWithKey:);
  if (manager != nil && [manager respondsToSelector:selector]) {
    void (*sendSetup)(id, SEL, id) = (void (*)(id, SEL, id))objc_msgSend;
    sendSetup(manager, selector, key);
  }

  return [self isInitBeauty];
}

- (BOOL)isInitBeauty {
  id manager = [self sharedFaceUnityManager];
  SEL selector = @selector(isInitBeauty);
  if (manager == nil || ![manager respondsToSelector:selector]) {
    return NO;
  }

  BOOL (*sendIsInitBeauty)(id, SEL) = (BOOL (*)(id, SEL))objc_msgSend;
  return sendIsInitBeauty(manager, selector);
}

- (int)setParamItemAboutType:(FUNamaHandleType)type name:(NSString *)paramName value:(id)value {
  id manager = [self sharedFaceUnityManager];
  SEL selector = @selector(setParamItemAboutType:name:value:);
  if (manager == nil || ![manager respondsToSelector:selector] ||
      paramName.length == 0 || value == nil) {
    return -1;
  }

  int (*sendSetParam)(id, SEL, NSUInteger, id, id) =
      (int (*)(id, SEL, NSUInteger, id, id))objc_msgSend;
  return sendSetParam(manager, selector, type, paramName, value);
}

/**将道具绘制到pixelBuffer*/
- (CVPixelBufferRef)renderItemsToPixelBuffer:(CVPixelBufferRef)pixelBuffer {
  id manager = [self sharedFaceUnityManager];
  SEL selector = @selector(renderItemsToPixelBuffer:);
  if (manager == nil || ![manager respondsToSelector:selector] || pixelBuffer == NULL) {
    return NULL;
  }

  CVPixelBufferRef (*sendRender)(id, SEL, CVPixelBufferRef) =
      (CVPixelBufferRef (*)(id, SEL, CVPixelBufferRef))objc_msgSend;
  return sendRender(manager, selector, pixelBuffer);
}

- (void)destroyAllItems {
  id manager = [self sharedFaceUnityManager];
  SEL selector = @selector(destroyAllItems);
  if (manager != nil && [manager respondsToSelector:selector]) {
    void (*sendDestroyAllItems)(id, SEL) = (void (*)(id, SEL))objc_msgSend;
    sendDestroyAllItems(manager, selector);
  }
}

- (void)loadFilter:(NSDictionary *)fliterParams {
  id manager = [self sharedFaceUnityManager];
  SEL selector = @selector(loadFilter:);
  if (manager != nil && [manager respondsToSelector:selector]) {
    void (*sendLoadFilter)(id, SEL, id) = (void (*)(id, SEL, id))objc_msgSend;
    sendLoadFilter(manager, selector, fliterParams ?: @{});
  }
}

- (void)releaseResources {
  // Intentionally a no-op on iOS.
  // nertc_faceunity owns the shared FaceUnity SDK lifecycle.
}

@end
