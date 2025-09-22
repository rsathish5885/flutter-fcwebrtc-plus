import 'package:flutter_webrtc_plus/flutter_webrtc_plus.dart';

extension VideoRendererExtension on RTCVideoRenderer {
  RTCVideoValue get videoValue => value;
}

abstract class AudioControl {
  Future<void> setVolume(double volume);
}
