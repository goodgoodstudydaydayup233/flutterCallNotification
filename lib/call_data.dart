/// 通话类型
enum CallType {
  /// 语音通话
  audio,

  /// 视频通话
  video,
}

/// 来电数据模型
///
/// 封装来电通知所需的全部信息，通过 MethodChannel 传递至 Native 端。
///
/// ## 最简调用（仅必填参数）
///
/// ```dart
/// // 仅传入来电者名称即可，其余均使用合理默认值
/// CallNotification.instance.showCallNotification(
///   CallData(callerName: '张三'),
/// );
/// ```
class CallData {
  /// 来电者名称，显示在通知与全屏来电界面
  ///
  /// 默认值：无（必填）
  /// 不传时：构造失败
  /// 显示效果："张三"
  final String callerName;

  /// 来电者头像 URL 或本地资源路径
  ///
  /// 默认值：null（使用系统默认头像图标）
  /// 传网络 URL 时 Native 会异步加载并缓存
  /// 支持格式：http(s)://、content://、file://
  ///
  /// 显示效果：
  /// - 有值 → 加载并显示圆形头像
  /// - null → 显示灰色默认人物图标
  final String? callerAvatarUrl;

  /// 通话类型：语音或视频
  ///
  /// 默认值：[CallType.audio]（语音通话）
  /// 影响：
  /// - 副标题默认文案（audio→"邀请你语音通话"，video→"邀请你视频通话"）
  /// - 全屏界面图标样式
  /// - 铃声类型选择
  final CallType callType;

  /// 唯一通话标识，用于回调时匹配具体通话
  ///
  /// 默认值：自动生成 UUID 格式字符串（如 "call_1707890123_abc123"）
  /// 调用方无需手动管理 ID，仅在需要关联业务数据时才传自定义值
  ///
  /// 回调时通过 [CallNotification.onAction] 和 [onCallAnswered] 返回此 ID
  final String callId;

  /// 副标题文本，显示在名称下方
  ///
  /// 默认值：根据 [callType] 自动生成
  /// - [CallType.audio] → "邀请你语音通话"
  /// - [CallType.video] → "邀请你视频通话"
  ///
  /// 传入自定义值时可覆盖默认文案，如：
  /// - "正在呼叫..."
  /// - "对方忙线中"
  /// - "等待接听..."
  final String? subtitle;

  /// 来电超时时间（秒），超时后自动触发 [CallAction.timeout] 回调
  ///
  /// 默认值：0（不超时，需手动取消）
  /// 设为正数时 Native 端启动倒计时，到期自动挂断并回调
  /// 典型值：30（30秒未接听自动挂断，模拟运营商行为）
  final int timeoutSeconds;

  /// 附加信息（可选），可携带业务自定义数据
  ///
  /// 默认值：null
  /// 此数据会随 [onCallAnswered] 回调返回，方便在接听后执行业务操作
  /// 如：{'roomId': 'room_123', 'groupId': 'group_456'}
  final Map<String, dynamic>? extra;

  /// ──────────────── 按钮控制参数 ────────────────

  /// 接听按钮文本
  ///
  /// 默认值："接听"
  /// 影响范围：全屏界面 + 悬浮窗 的接听按钮文案
  final String? answerButtonText;

  /// 拒绝/挂断按钮文本
  ///
  /// 默认值："拒绝"
  /// 影响范围：全屏界面 + 悬浮窗 的拒绝按钮文案
  final String? rejectButtonText;

  /// 是否显示接听按钮
  ///
  /// 默认值：true（显示）
  final bool showAnswerButton;

  /// 是否显示拒绝/挂断按钮
  ///
  /// 默认值：true（显示）
  final bool showRejectButton;

  /// 是否直接唤出全屏来电界面（跳过顶部悬浮窗）
  ///
  /// 默认值：false（显示悬浮窗）
  ///
  /// 设为 true 时：跳过 WindowManager 悬浮窗，直接启动 CallActivity 全屏界面
  ///
  /// 前台服务保活 + 铃声振动 + 屏幕唤醒仍然正常工作。
  final bool directToFullScreen;

  CallData({
    required this.callerName,
    this.callerAvatarUrl,
    this.callType = CallType.audio,
    String? callId,
    this.subtitle,
    this.timeoutSeconds = 0,
    this.extra,
    this.directToFullScreen = false,
    // 按钮文本与显示控制
    this.answerButtonText,
    this.rejectButtonText,
    this.showAnswerButton = true,
    this.showRejectButton = true,
  }) : callId = callId ?? _generateCallId();

  /// 自动生成唯一通话标识
  static String _generateCallId() {
    return 'call_${DateTime.now().millisecondsSinceEpoch}_${_randomString(6)}';
  }

  static String _randomString(int length) {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    final random = DateTime.now().microsecondsSinceEpoch;
    final buffer = StringBuffer();
    for (var i = 0; i < length; i++) {
      buffer.write(chars[(random + i) % chars.length]);
    }
    return buffer.toString();
  }

  /// 获取最终副标题（优先使用自定义值，否则根据 callType 生成）
  String get effectiveSubtitle {
    if (subtitle != null && subtitle!.isNotEmpty) return subtitle!;
    switch (callType) {
      case CallType.video:
        return '邀请你视频通话';
      case CallType.audio:
        return '邀请你语音通话';
    }
  }

  /// 转换为 Map，用于 MethodChannel 传输
  Map<String, dynamic> toMap() {
    return {
      'callerName': callerName,
      'callerAvatarUrl': callerAvatarUrl,
      'callType': callType.name,
      'callId': callId,
      'subtitle': subtitle ?? effectiveSubtitle,
      'timeoutSeconds': timeoutSeconds,
      'extra': extra,
      'directToFullScreen': directToFullScreen,
      // 按钮文本与显示控制
      'answerButtonText': answerButtonText ?? '接听',
      'rejectButtonText': rejectButtonText ?? '拒绝',
      'showAnswerButton': showAnswerButton,
      'showRejectButton': showRejectButton,
    };
  }

  /// 从 Map 创建实例
  factory CallData.fromMap(Map<String, dynamic> map) {
    return CallData(
      callerName: map['callerName'] as String? ?? '未知来电',
      callerAvatarUrl: map['callerAvatarUrl'] as String?,
      callType: CallType.values.firstWhere(
        (e) => e.name == map['callType'],
        orElse: () => CallType.audio,
      ),
      callId: map['callId'] as String? ?? _generateCallId(),
      subtitle: map['subtitle'] as String?,
      timeoutSeconds: (map['timeoutSeconds'] as num?)?.toInt() ?? 0,
      extra: map['extra'] as Map<String, dynamic>?,
      directToFullScreen: map['directToFullScreen'] as bool? ?? false,
      answerButtonText: map['answerButtonText'] as String?,
      rejectButtonText: map['rejectButtonText'] as String?,
      showAnswerButton: map['showAnswerButton'] as bool? ?? true,
      showRejectButton: map['showRejectButton'] as bool? ?? true,
    );
  }
}
