## 1.0.0

* 初始版本
* 支持悬浮通知（WindowManager TYPE_APPLICATION_OVERLAY）
* 支持全屏来电界面（CallActivity）
* 支持锁屏 CallStyle 通知（Android 14+ 兼容，前台服务通知不可划掉）
* 支持可划掉消息栏通知（解锁状态，用户可左右滑动取消来电）
* 支持铃声与振动（系统默认来电铃声 + 波形振动）
* 支持接听/拒绝/超时回调
* 支持接听唤出应用（onCallAnswered 回调携带完整来电数据）
* 支持锁屏→解锁自动切换通知模式（ACTION_USER_PRESENT 监听）
* 内置国产 ROM 权限引导（MIUI/EMUI/ColorOS/Flyme 等）
* 最低权限前置检查（通知权限 + 两个通知渠道，缺失时不执行并打印日志）
