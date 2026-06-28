import 'package:flutter/services.dart';
import 'call_action.dart';
import 'call_data.dart';

/// 来电通知 MethodChannel 通信层
///
/// 封装与 Native 端的所有方法调用与回调，
/// 作为 Dart 层与 Android 原生层之间的桥梁。
class CallNotificationMethodChannel {
  /// 与 Native 端约定的通道名称
  static const _channel = MethodChannel('call_notification');

  /// 来电操作回调，当用户在通知或全屏界面执行操作时触发
  Function(CallAction action, String callId)? onAction;

  /// 接听来电回调，当用户点击接听且应用被唤出时触发
  ///
  /// 携带完整的来电数据，方便应用在接听后执行后续操作（如跳转到通话界面）。
  Function(Map<String, dynamic> callData)? onCallAnswered;

  CallNotificationMethodChannel() {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  /// 处理来自 Native 端的方法调用（Native → Dart）
  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onCallAction':
        final args = call.arguments as Map;
        final action = CallAction.values.firstWhere(
          (e) => e.name == args['action'],
          orElse: () => CallAction.timeout,
        );
        final callId = args['callId'] as String;
        onAction?.call(action, callId);
        break;
      case 'onCallAnswered':
        // 接听来电，携带完整来电数据
        final args = call.arguments as Map;
        onCallAnswered?.call(Map<String, dynamic>.from(args));
        break;
    }
  }

  /// 显示来电通知（Dart → Native）
  Future<void> showCallNotification(CallData data) async {
    await _channel.invokeMethod('showCallNotification', data.toMap());
  }

  /// 检查通知权限（Android 13+ 需要）
  Future<bool> checkNotificationPermission() async {
    final result =
        await _channel.invokeMethod<bool>('checkNotificationPermission');
    return result ?? false;
  }

  /// 请求通知权限
  Future<bool> requestNotificationPermission() async {
    final result = await _channel.invokeMethod<bool>(
        'requestNotificationPermission');
    return result ?? false;
  }

  /// 检查悬浮窗权限（在其他应用上层显示）
  ///
  /// 此权限是来电通知能在其他应用打开时弹出悬浮层的关键权限，
  /// 部分国产 ROM（MIUI 等）将此权限与"后台弹出界面"权限关联。
  Future<bool> checkOverlayPermission() async {
    final result =
        await _channel.invokeMethod<bool>('checkOverlayPermission');
    return result ?? false;
  }

  /// 请求悬浮窗权限（打开系统设置页）
  Future<bool> requestOverlayPermission() async {
    final result = await _channel.invokeMethod<bool>(
        'requestOverlayPermission');
    return result ?? false;
  }

  /// 打开应用详情设置页
  ///
  /// 用于引导用户开启国产 ROM 专有权限（如 MIUI 后台弹出界面权限），
  /// 这些权限没有标准 Android API，只能引导用户手动开启。
  Future<void> openAppDetailSettings() async {
    await _channel.invokeMethod('openAppDetailSettings');
  }

  /// 检查后台弹出界面权限（国产 ROM 专有）
  ///
  /// 此权限允许应用从后台弹出界面（如来电通知悬浮层），
  /// 为 MIUI、EMUI、ColorOS 等国产 ROM 的专有权限。
  Future<bool> checkBackgroundPopupPermission() async {
    final result = await _channel.invokeMethod<bool>(
        'checkBackgroundPopupPermission');
    return result ?? false;
  }

  /// 请求后台弹出界面权限（国产 ROM 专有）
  ///
  /// MIUI 会尝试打开安全中心权限编辑页，
  /// 其他 ROM 会打开应用详情设置页引导用户手动开启。
  Future<bool> requestBackgroundPopupPermission() async {
    final result = await _channel.invokeMethod<bool>(
        'requestBackgroundPopupPermission');
    return result ?? false;
  }

  /// 获取当前系统 ROM 类型名称
  ///
  /// 返回值用于展示针对性的用户提示，例如：
  /// - "MIUI" — 提示用户在安全中心开启"后台弹出界面"
  /// - "EMUI" / "HarmonyOS" — 提示用户在应用启动管理中允许后台活动
  /// - "ColorOS" / "OriginOS" — 提示用户关闭后台冻结
  /// - "Flyme" — 提示用户在通知管理中允许后台弹出
  /// - "Android" — 原生 Android，无特殊权限需求
  Future<String> getRomName() async {
    final result = await _channel.invokeMethod<String>('getRomName');
    return result ?? 'Android';
  }
}
