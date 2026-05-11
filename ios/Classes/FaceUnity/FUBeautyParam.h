// Compatibility shim for stale CocoaPods/Xcode integrations.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface FUBeautyParam : NSObject
@property(nonatomic, copy) NSString *mTitle;
@property(nonatomic, copy) NSString *mParam;
@property(nonatomic, assign) float mValue;
@property(nonatomic, copy) NSString *mImageStr;
@property(nonatomic, assign) BOOL iSStyle101;
@property(nonatomic, assign) float defaultValue;
@end

NS_ASSUME_NONNULL_END
