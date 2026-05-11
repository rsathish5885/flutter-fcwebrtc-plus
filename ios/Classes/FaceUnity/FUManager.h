// Compatibility header for stale CocoaPods/Xcode integrations.
// The real FUManager implementation should come from nertc_faceunity.

#import <AVFoundation/AVFoundation.h>
#import <Flutter/Flutter.h>
#import <Foundation/Foundation.h>

typedef NS_ENUM(NSUInteger, FUNamaHandleType) {
  FUNamaHandleTypeBeauty = 0,
  FUNamaHandleTypeItem = 1,
  FUNamaHandleTypeFxaa = 2,
  FUNamaHandleTypeGesture = 3,
  FUNamaHandleTypeChangeface = 4,
  FUNamaHandleTypeComic = 5,
  FUNamaHandleTypeMakeup = 6,
  FUNamaHandleTypePhotolive = 7,
  FUNamaHandleTypeAvtarHead = 8,
  FUNamaHandleTypeAvtarHiar = 9,
  FUNamaHandleTypeAvtarbg = 10,
  FUNamaHandleTypeBodySlim = 11,
  FUNamaHandleTypeBodyAvtar = 12,
  FUNamaHandleTotal = 13,
};

@interface FUManager : NSObject
+ (FUManager *)shareManager;
- (void)setupWithKey:(FlutterStandardTypedData *)key;
- (BOOL)isInitBeauty;
- (void)loadFilter:(NSDictionary *)fliterParams;
- (int)setParamItemAboutType:(FUNamaHandleType)type name:(NSString *)paramName value:(id)value;
- (CVPixelBufferRef)renderItemsToPixelBuffer:(CVPixelBufferRef)pixelBuffer;
- (void)destroyAllItems;
@end
