// Compatibility shim for stale CocoaPods/Xcode integrations.

#import <Foundation/Foundation.h>

typedef NS_ENUM(NSUInteger, FULiveModelType) {
  FULiveModelTypeBeautifyFace = 0,
};

@interface FULiveModel : NSObject
@property(nonatomic, assign) FULiveModelType type;
@property(nonatomic, copy) NSString *bundleName;
@property(nonatomic, copy) NSString *iconName;
@property(nonatomic, assign) int maxFace;
@end
