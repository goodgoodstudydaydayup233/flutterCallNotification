package com.callnotification.call_notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * 定时触发来电通知的广播接收器
 *
 * 解决 Android 10+ 后台启动限制问题：
 * - 应用在后台时无法直接调用 startForegroundService()
 * - 但通过 AlarmManager 调度的广播可以在后台正常接收
 * - 在 onReceive 中启动前台服务，绕过后台限制
 *
 * 使用方式：
 * 1. 通过 [schedule] 静态方法设置定时任务（可立即或延迟触发）
 * 2. Alarm 触发后 → onReceive() → 启动 CallNotificationService
 */
class CallAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_ALARM_TRIGGER =
            "com.callnotification.call_notification.ALARM_TRIGGER"

        /** 请求码基础值，用于区分不同的定时任务 */
        private const val REQUEST_CODE_BASE = 2000

        /**
         * 调度一个定时来电通知任务
         *
         * @param context   上下文
         * @param data      来电数据（CallData 序列化后的 Map）
         * @param delayMs   延迟毫秒数，0 表示立即触发
         */
        fun schedule(context: Context, data: HashMap<String, Any>, delayMs: Long = 0) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent(context, CallAlarmReceiver::class.java).apply {
                action = ACTION_ALARM_TRIGGER
                putExtra(CallNotificationService.EXTRA_CALL_DATA, data)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt() + REQUEST_CODE_BASE,
                intent,
                flags
            )

            // 使用 ELAPSED_REALTIME_WAKEUP 确保设备休眠时也能唤醒
            // setExactAndAllowWhileIdle：即使在 Doze 模式下也能精确触发
            val triggerTime = SystemClock.elapsedRealtime() + delayMs

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } catch (e: SecurityException) {
                // Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限
                // 降级使用 set 方法（不保证精确时间）
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM_TRIGGER) return

        // 最低权限前置检查：通知权限 + 「音视频通话邀请通知」/「来电保活」渠道启用
        if (!CallNotificationService.checkPrerequisites(context)) return

        try {
            // 从 Intent 中提取来电数据并启动前台服务
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(
                    CallNotificationService.EXTRA_CALL_DATA,
                    HashMap::class.java
                )
            } else {
                intent.getSerializableExtra(CallNotificationService.EXTRA_CALL_DATA)
            } as? HashMap<String, Any> ?: return

            val serviceIntent = Intent(context, CallNotificationService::class.java).apply {
                action = CallNotificationService.ACTION_SHOW
                putExtra(CallNotificationService.EXTRA_CALL_DATA, data)
            }

            // 从 Alarm 广播中启动前台服务不受后台限制
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // 防止反序列化失败或服务启动异常导致应用崩溃
            e.printStackTrace()
        }
    }
}
