import 'package:flutter_webrtc_plus/flutter_webrtc_plus.dart';

// Note: These imports might need adjustment based on your actual project structure.
// The user provided '../../../../../../../all_export.dart' which usually contains 
// app-specific themes (MyColor, MyText) and GetX controllers.

/// Model for FaceUnity Beauty Parameters
class FaceBeautyParams {
  final String name;
  final String? imageName;
  final String? filterName;
  double value;
  final double min;
  final double max;

  FaceBeautyParams({
    required this.name,
    this.imageName,
    this.filterName,
    required this.value,
    required this.min,
    required this.max,
  });
}

/// Utility class for FaceUnity settings with NERTC default values
class FaceunityUtils {
  static int filterIndex = 0;
  static int beautyIndex = 0;

  // Filter names for the horizontal list (example names, usually bailiang, fennen etc.)
  static final List<String> filterNames = [
    'origin', 'bailiang1', 'bailiang2', 'fennen1', 'fennen2', 'fennen3',
    'xiaoqingxin1', 'xiaoqingxin2', 'xiaoqingxin3', 'lengsediao1', 'lengsediao2',
    'nuansediao', 'heibai1', 'heibai2'
  ];

  static final List<FaceBeautyParams> beautyParamList = [
    FaceBeautyParams(
      name: 'RedLevel', // Rosy glow / Lipstick
      imageName: '1',
      value: 1,
      min: 0,
      max: 2,
    ),
    FaceBeautyParams(
      name: 'Microdermabrasion', // Skin smoothing / Blur
      imageName: '4',
      value: 3,
      min: 0,
      max: 6,
    ),
    FaceBeautyParams(
      name: 'Whiten', // Color level
      imageName: '5',
      value: 1,
      min: 0,
      max: 2,
    ),
    FaceBeautyParams(
      name: 'Eye Bright',
      imageName: '6',
      value: 0,
      min: 0,
      max: 1,
    ),
    FaceBeautyParams(
      name: 'Eye Enlarging',
      imageName: '7',
      value: 0,
      min: 0,
      max: 1,
    ),
    FaceBeautyParams(
      name: 'Cheek Thinning',
      imageName: '8',
      value: 0,
      min: 0,
      max: 1,
    ),
    FaceBeautyParams(
      name: 'Filter Level',
      filterName: 'origin',
      value: 0.5,
      min: 0,
      max: 1,
    ),
  ];

  static final List<FaceBeautyParams> resetValue = List.from(beautyParamList);
}

/// CallController that connects UI changes to the WebRTC plugin
class CallController extends GetxController {
  bool isBeautyOn = false;

  Future<void> toggleBeauty() async {
    isBeautyOn = !isBeautyOn;
    update(['call_update']);
    
    if (!isBeautyOn) {
      // Reset all to 0 or defaults if preferred
      await Helper.setSmoothValue(0);
      await Helper.setWhiteValue(0);
      await Helper.setThinFaceValue(0);
      await Helper.setBigEyeValue(0);
      await Helper.setLipstickValue(0);
      await Helper.setFilterLevel(0);
    } else {
      // Apply current values from UI
      applyAllBeautySettings();
    }
  }

  void applyAllBeautySettings() {
    for (var param in FaceunityUtils.beautyParamList) {
      updateBeautyParameter(param);
    }
  }

  Future<void> updateBeautyParameter(FaceBeautyParams param) async {
    if (!isBeautyOn) return;

    switch (param.name) {
      case 'RedLevel':
        await Helper.setLipstickValue(param.value);
        break;
      case 'Microdermabrasion':
        await Helper.setSmoothValue(param.value);
        break;
      case 'Whiten':
        await Helper.setWhiteValue(param.value);
        break;
      case 'Eye Bright':
        await Helper.setEyeBrightValue(param.value);
        break;
      case 'Eye Enlarging':
        await Helper.setBigEyeValue(param.value);
        break;
      case 'Cheek Thinning':
        await Helper.setThinFaceValue(param.value);
        break;
      case 'Filter Level':
        await Helper.setFilterLevel(param.value);
        break;
    }
  }

  Future<void> updateFilterName(String filterName) async {
    if (!isBeautyOn) return;
    await Helper.setFilterName(filterName);
  }
}

class VideoCallBeautySheet extends StatefulWidget {
  const VideoCallBeautySheet({super.key});

  @override
  State<VideoCallBeautySheet> createState() => _VideoCallBeautySheetState();
}

class _VideoCallBeautySheetState extends State<VideoCallBeautySheet>
    with SingleTickerProviderStateMixin {
  late final TabController tabbarController;

  @override
  void initState() {
    super.initState();
    tabbarController = TabController(length: 2, vsync: this);
  }

  @override
  void dispose() {
    tabbarController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 0.4.sh,
      decoration: const BoxDecoration(
        color: Colors.black87,
        borderRadius: BorderRadius.only(
          topLeft: Radius.circular(20),
          topRight: Radius.circular(20),
        ),
      ),
      child: Column(
        children: [
          const SizedBox(height: 10),
          // Add beauty toggle
          GetBuilder<CallController>(
            id: 'call_update',
            builder: (callController) {
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      'Beauty',
                      style: TextStyle(color: Colors.white, fontSize: 16),
                    ),
                    Switch(
                      value: callController.isBeautyOn,
                      onChanged: (value) async {
                        await callController.toggleBeauty();
                      },
                      activeThumbColor: Colors.blue, 
                    ),
                  ],
                ),
              );
            },
          ),
          TabBar(
            controller: tabbarController,
            tabs: const [
              Tab(text: 'Filter'),
              Tab(text: 'Beauty'),
            ],
            indicatorColor: Colors.blue, 
            labelColor: Colors.blue, 
            unselectedLabelColor: Colors.white,
          ),
          Expanded(
            child: TabBarView(
              controller: tabbarController,
              children: const [
                VideoCallFilterTab(),
                VideoCallBeautyTab(),
              ],
            ),
          ),
          const SizedBox(height: 20),
        ],
      ),
    );
  }
}

class VideoCallFilterTab extends StatelessWidget {
  const VideoCallFilterTab({super.key});

  @override
  Widget build(BuildContext context) {
    final length = FaceunityUtils.beautyParamList.length - 1;

    return GetBuilder<CallController>(
      id: 'face_filter_update',
      builder: (controller) {
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 10, right: 10, left: 10),
              child: BeautySlider(index: length),
            ),
            Expanded(
              child: ListView.builder(
                scrollDirection: Axis.horizontal,
                itemCount: FaceunityUtils.filterNames.length,
                itemBuilder: (context, index) => FilterItem(
                  index: index,
                  isSelected: index == FaceunityUtils.filterIndex,
                  name: FaceunityUtils.filterNames[index],
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

class VideoCallBeautyTab extends StatelessWidget {
  const VideoCallBeautyTab({super.key});

  @override
  Widget build(BuildContext context) {
    return GetBuilder<CallController>(
      id: 'face_beauty_update',
      builder: (controller) {
        return Column(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 10, right: 10, left: 10),
              child: BeautySlider(index: FaceunityUtils.beautyIndex),
            ),
            Expanded(
              child: ListView.builder(
                scrollDirection: Axis.horizontal,
                itemCount: FaceunityUtils.beautyParamList.length,
                itemBuilder: (context, index) {
                  if (FaceunityUtils.beautyParamList[index].name ==
                      'Filter Level') {
                    return const SizedBox();
                  }

                  return BeautyItem(
                    index: index,
                    isSelected: FaceunityUtils.beautyIndex == index,
                  );
                },
              ),
            ),
          ],
        );
      },
    );
  }
}

class BeautySlider extends StatelessWidget {
  final int index;
  const BeautySlider({super.key, required this.index});

  @override
  Widget build(BuildContext context) {
    final param = FaceunityUtils.beautyParamList[index];
    return GetBuilder<CallController>(
      builder: (controller) {
        return Slider(
          value: param.value,
          min: param.min,
          max: param.max,
          activeColor: Colors.blue,
          inactiveColor: Colors.white24,
          onChanged: (val) {
            param.value = val;
            controller.updateBeautyParameter(param);
            controller.update(['face_filter_update', 'face_beauty_update']);
          },
        );
      }
    );
  }
}

class FilterItem extends StatelessWidget {
  final int index;
  final bool isSelected;
  final String name;
  const FilterItem({super.key, required this.index, required this.isSelected, required this.name});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () {
        FaceunityUtils.filterIndex = index;
        Get.find<CallController>().updateFilterName(name);
        Get.find<CallController>().update(['face_filter_update']);
      },
      child: Column(
        children: [
          Container(
            width: 50,
            height: 50,
            margin: const EdgeInsets.all(5),
            decoration: BoxDecoration(
              border: isSelected ? Border.all(color: Colors.blue, width: 2) : null,
              color: Colors.grey[800],
              shape: BoxShape.circle,
            ),
            child: Center(child: Text('${index + 1}', style: const TextStyle(color: Colors.white, fontSize: 12))),
          ),
          Text(name, style: TextStyle(color: isSelected ? Colors.blue : Colors.white, fontSize: 10)),
        ],
      ),
    );
  }
}

class BeautyItem extends StatelessWidget {
  final int index;
  final bool isSelected;
  const BeautyItem({super.key, required this.index, required this.isSelected});

  @override
  Widget build(BuildContext context) {
    final param = FaceunityUtils.beautyParamList[index];
    return GestureDetector(
      onTap: () {
        FaceunityUtils.beautyIndex = index;
        Get.find<CallController>().update(['face_beauty_update']);
      },
      child: Column(
        children: [
          Container(
            width: 50,
            height: 50,
            margin: const EdgeInsets.all(5),
            decoration: BoxDecoration(
              border: isSelected ? Border.all(color: Colors.blue, width: 2) : null,
              color: Colors.grey[700],
              shape: BoxShape.circle,
            ),
            child: Icon(Icons.face, color: isSelected ? Colors.blue : Colors.white),
          ),
          Text(param.name, style: TextStyle(color: isSelected ? Colors.blue : Colors.white, fontSize: 10)),
        ],
      ),
    );
  }
}
