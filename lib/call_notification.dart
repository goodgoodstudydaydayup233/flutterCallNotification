import 'package:flutter/material.dart';

import 'call_action.dart';
import 'call_data.dart';
import 'call_notification_method_channel.dart';

/// 来电通知管理器
///
/// 提供类似微信音视频来电通知的完整能力，包括：
/// - 全屏来电界面（锁屏状态下显示）
/// - 悬浮通知（解锁状态下显示）
/// - 前台服务保活
/// - 铃声与振动
/// - 接听/拒绝操作回调
///
/// 使用示例：
/// ```dart
/// // 1. 初始化（在 main 中调用）
/// CallNotification.instance.init();
///
/// // 2. 请求权限（必须在来电通知前调用）
/// await CallNotification.instance.requestPermissions();
///
/// // 3. 监听来电操作
/// CallNotification.instance.onAction = (action, callId) {
///   if (action == CallAction.answer) {
///     // 处理接听逻辑
///   } else if (action == CallAction.reject) {
///     // 处理拒绝逻辑
///   }
/// };
///
/// // 4. 显示来电通知
/// await CallNotification.instance.showCallNotification(
///   CallData(
///     callerName: '张三',
///     callType: CallType.video,
///     callId: 'call_123',
///   ),
/// );
/// ```
class CallNotification {
  CallNotification._();

  /// 单例实例
  static final CallNotification instance = CallNotification._();

  final CallNotificationMethodChannel _methodChannel =
      CallNotificationMethodChannel();

  /// 来电操作回调
  ///
  /// 当用户在通知栏或全屏来电界面执行操作时触发，
  /// 回调参数为 [CallAction] 操作类型和 [String] 通话标识。
  Function(CallAction action, String callId)? onAction;

  /// 接听来电回调
  ///
  /// 当用户点击接听且应用被唤出时触发，携带完整的来电数据。
  /// 与 [onAction] 不同，此回调专门用于接听后唤出应用的场景，
  /// 方便应用在接听后执行后续操作（如跳转到通话界面）。
  ///
  /// 回调参数为完整的来电数据 Map，包含：
  /// - callerName: 来电者名称
  /// - callType: 通话类型（audio/video）
  /// - callId: 通话标识
  /// - extra: 附加数据（如有）
  Function(Map<String, dynamic> callData)? onCallAnswered;

  /// 初始化，需在 main 中调用
  ///
  /// 注册 Native → Dart 的回调通道，
  /// 将 Native 端的用户操作转发给 [onAction] 和 [onCallAnswered] 回调。
  void init() {
    _methodChannel.onAction = (action, callId) {
      onAction?.call(action, callId);
    };
    _methodChannel.onCallAnswered = (callData) {
      onCallAnswered?.call(callData);
    };
  }

  /// 显示来电通知
  ///
  /// 调用后 Native 端将启动前台服务，显示全屏来电界面或悬浮通知，
  /// 同时播放铃声和振动。
  Future<void> showCallNotification(CallData data) async {
    await _methodChannel.showCallNotification(data);
  }

  //region 权限管理

  /// 检查通知权限（Android 13+ 需要）
  Future<bool> checkNotificationPermission() async {
    return await _methodChannel.checkNotificationPermission();
  }

  /// 请求通知权限
  ///
  /// 返回 `true` 表示已授权，`false` 表示需要用户授权。
  Future<bool> requestNotificationPermission() async {
    return await _methodChannel.requestNotificationPermission();
  }

  /// 检查悬浮窗权限（在其他应用上层显示）
  ///
  /// 此权限是来电通知能在其他应用打开时弹出悬浮层的关键权限，
  /// 部分国产 ROM（MIUI 等）将此权限与"后台弹出界面"权限关联。
  Future<bool> checkOverlayPermission() async {
    return await _methodChannel.checkOverlayPermission();
  }

  /// 请求悬浮窗权限（打开系统设置页）
  ///
  /// 返回 `true` 表示已授权，`false` 表示需要用户手动授权。
  Future<bool> requestOverlayPermission() async {
    return await _methodChannel.requestOverlayPermission();
  }

  /// 打开应用详情设置页
  ///
  /// 用于引导用户开启国产 ROM 专有权限，例如：
  /// - MIUI：后台弹出界面权限
  /// - EMUI：后台弹出应用权限
  /// - ColorOS：后台弹出界面权限
  ///
  /// 这些权限没有标准 Android API，只能引导用户手动开启。
  Future<void> openAppDetailSettings() async {
    await _methodChannel.openAppDetailSettings();
  }

  /// 检查后台弹出界面权限（国产 ROM 专有）
  ///
  /// 此权限允许应用从后台弹出界面（如来电通知悬浮层），
  /// 为 MIUI、EMUI、ColorOS 等国产 ROM 的专有权限。
  /// 没有标准 Android API 可检测，检测结果不一定 100% 准确。
  Future<bool> checkBackgroundPopupPermission() async {
    return await _methodChannel.checkBackgroundPopupPermission();
  }

  /// 请求后台弹出界面权限（国产 ROM 专有）
  ///
  /// MIUI 会尝试打开安全中心权限编辑页，
  /// 其他 ROM 会打开应用详情设置页引导用户手动开启。
  /// 返回 `true` 表示已授权，`false` 表示需要用户手动授权。
  Future<bool> requestBackgroundPopupPermission() async {
    return await _methodChannel.requestBackgroundPopupPermission();
  }

  /// 获取当前系统 ROM 类型名称
  ///
  /// 用于展示针对性的用户提示文案：
  /// - MIUI → "请在安全中心中开启「后台弹出界面」权限"
  /// - EMUI/HarmonyOS → "请在应用启动管理中允许后台活动"
  /// - ColorOS/OriginOS → "请关闭后台冻结"
  /// - Flyme → "请在通知管理中允许后台弹出"
  /// - Android → 原生系统，无特殊权限需求
  Future<String> getRomName() async {
    return await _methodChannel.getRomName();
  }

  /// 仅请求悬浮窗所需权限
  ///
  /// 悬浮窗通过 WindowManager TYPE_APPLICATION_OVERLAY 渲染，
  /// 仅需 [SYSTEM_ALERT_WINDOW] 权限，不需要其他权限。
  ///
  /// 适用场景：仅使用悬浮窗来电，不需要全屏来电界面/通知栏通知时，
  /// 可用此轻量方法替代完整的 [requestPermissions]，减少用户授权步骤。
  ///
  /// 返回 `true` 表示悬浮窗权限已授权，`false` 表示需要用户手动授权。
  Future<bool> requestOverlayOnlyPermission() async {
    return await _methodChannel.requestOverlayPermission();
  }

  /// 请求来电通知所需的全部权限
  ///
  /// **必须在调用 [showCallNotification] 之前手动调用此函数**，
  /// 否则来电通知将因权限不足而无法正常显示。
  ///
  /// 包含以下权限请求：
  /// - Android 13+：通知权限（POST_NOTIFICATIONS）
  /// - 悬浮窗权限（SYSTEM_ALERT_WINDOW）— 在其他应用上层显示来电界面
  /// - 后台弹出界面权限（国产 ROM 专有）— 从后台弹出悬浮层
  ///
  /// 当 [showPermissionDialog] 为 `true` 且传入 [context] 时，会先检查缺失的权限，
  /// 显示弹窗告知用户需要哪些权限，用户点击"去设置"后再继续执行权限请求。
  /// 若某些权限已授权，则弹窗仅显示缺失的权限。
  ///
  /// 返回 `true` 表示所有必要权限已授权，`false` 表示存在未授权的权限。
  Future<bool> requestPermissions({
    BuildContext? context,
    bool showPermissionDialog = true,
  }) async {
    // 1. 检查缺失的权限
    final missing = <_PermissionItem>[];
    if (!await _methodChannel.checkNotificationPermission()) {
      missing.add(_PermissionItem.notification);
    }
    if (!await _methodChannel.checkOverlayPermission()) {
      missing.add(_PermissionItem.overlay);
    }
    if (!await _methodChannel.checkBackgroundPopupPermission()) {
      missing.add(_PermissionItem.backgroundPopup);
    }

    // 2. 全部已授权，无需任何操作
    if (missing.isEmpty) return true;

    // 3. 显示弹窗告知用户缺失的权限（仅显示未授权项）
    if (showPermissionDialog && context != null && context.mounted) {
      final confirmed = await _showPermissionDialog(context, missing);
      if (!confirmed) return false; // 用户取消
    }

    // 4. 执行权限请求流程（弹系统对话框/跳转设置页）
    bool allGranted = true;
    for (final p in missing) {
      final granted = await _requestPermissionItem(p);
      if (!granted) allGranted = false;
    }
    return allGranted;
  }

  /// 请求单项权限
  Future<bool> _requestPermissionItem(_PermissionItem item) async {
    switch (item) {
      case _PermissionItem.notification:
        return await _methodChannel.requestNotificationPermission();
      case _PermissionItem.overlay:
        return await _methodChannel.requestOverlayPermission();
      case _PermissionItem.backgroundPopup:
        return await _methodChannel.requestBackgroundPopupPermission();
    }
  }

  /// 显示权限说明弹窗
  ///
  /// 返回 `true` 表示用户点击"去设置"，`false` 表示用户取消。
  Future<bool> _showPermissionDialog(
    BuildContext context,
    List<_PermissionItem> missing,
  ) async {
    final description = _buildPermissionDescription(missing);
    final result = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('需要权限'),
        content: Text('为了正常显示来电通知，需要以下权限：\n\n$description'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('去设置'),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  /// 生成缺失权限的描述文案
  String _buildPermissionDescription(List<_PermissionItem> missing) {
    final buffer = StringBuffer();
    for (final item in missing) {
      switch (item) {
        case _PermissionItem.notification:
          buffer.writeln('• 通知权限：显示来电通知');
          break;
        case _PermissionItem.overlay:
          buffer.writeln('• 悬浮窗权限：显示来电悬浮窗');
          break;
        case _PermissionItem.backgroundPopup:
          buffer.writeln('• 后台弹出界面权限：从后台弹出来电界面（国产 ROM 专有）');
          break;
      }
    }
    return buffer.toString().trimRight();
  }

  //endregion
}

/// 权限项枚举（内部使用，标识缺失的权限类型）
enum _PermissionItem {
  notification,
  overlay,
  backgroundPopup,
}
