package com.callnotification.call_notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 来电操作广播接收器
 *
 * 接收通知栏中接听/拒绝按钮的点击事件，
 * 并将操作转发给前台服务处理。
 *
 * 为什么使用 BroadcastReceiver 而非直接启动 Service：
 * - 通知 Action 按钮只能绑定 PendingIntent
 * - BroadcastReceiver 可以安全地接收广播并转发操作
 * - 避免在通知按钮点击时直接操作 Service 生命周期
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // 将通知栏操作转发给 Service 统一处理
        val serviceIntent = Intent(context, CallNotificationService::class.java).apply {
            this.action = action
            putExtra("callId", intent.getStringExtra("callId"))
        }
        context.startService(serviceIntent)
    }
}
