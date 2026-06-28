package com.callnotification.call_notification_plugin_example

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import com.callnotification.call_notification.CallNotificationPlugin

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // 注册来电通知插件
        flutterEngine.plugins.add(CallNotificationPlugin())
    }

    /**
     * 处理接听来电后的 Intent（携带来电数据）
     * 使用方必须重写此方法以支持接听唤出应用时获取参数
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CallNotificationPlugin.instance.handleNewIntent(intent)
    }
}
