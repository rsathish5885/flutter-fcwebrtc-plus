#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html
#
Pod::Spec.new do |s|
  s.name             = 'flutter_webrtc'
  s.version          = '1.2.0'
  s.summary          = 'Flutter WebRTC plugin for iOS.'
  s.description      = <<-DESC
A new flutter plugin project.
                       DESC
  s.homepage         = 'https://github.com/cloudwebrtc/flutter-webrtc'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'CloudWebRTC' => 'duanweiwei1982@gmail.com' }
  s.source           = { :path => '.' }
  s.prepare_command = <<-CMD
    if [ ! -d "Classes/rnnoise" ]; then
      git clone --depth=1 https://github.com/xiph/rnnoise.git /tmp/rnnoise_pod_build
      mkdir -p Classes/rnnoise
      cp /tmp/rnnoise_pod_build/src/*.c /tmp/rnnoise_pod_build/src/*.h Classes/rnnoise/
      cp /tmp/rnnoise_pod_build/include/rnnoise.h Classes/rnnoise/
      rm -rf /tmp/rnnoise_pod_build
    fi
  CMD

  s.source_files = 'Classes/**/*.{h,m,mm,swift,c,cpp}'
  s.public_header_files = 'Classes/**/*.h'
  s.exclude_files = [
    'Classes/FaceUnity/FUManager.*',
    'Classes/FaceUnity/FUBeautyParam.*',
    'Classes/FaceUnity/FULiveModel.*',
  ]
  s.dependency 'Flutter'
  s.dependency 'nertc_faceunity'
  s.dependency 'WebRTC-SDK', '137.7151.04'
  s.ios.deployment_target = '13.0'
  s.static_framework = true
  s.pod_target_xcconfig = {
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++14',
    'USER_HEADER_SEARCH_PATHS' => '"$(PODS_TARGET_SRCROOT)/Classes" "$(PODS_TARGET_SRCROOT)/Classes/rnnoise" "$(PODS_TARGET_SRCROOT)/Classes/FaceUnity" "$(PODS_TARGET_SRCROOT)/../common/darwin/Classes"',
    'OTHER_CFLAGS' => '-DOUTSIDE_SPEEX -DRANDOM_PREFIX=rnnoise',
    'FRAMEWORK_SEARCH_PATHS' => '$(inherited)'
  }
  s.libraries = 'c++'
  s.preserve_paths = 'FURenderKit.framework'
end
