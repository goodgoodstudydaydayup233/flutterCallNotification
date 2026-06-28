package com.callnotification.call_notification

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry

/**
 * 来电通知插件
 *
 * 负责注册 MethodChannel，处理 Dart 端的方法调用，
 * 并协调前台服务、铃声管理等组件。
 *
 * 设计为可抽取为独立插件，通过 FlutterPlugin 和 ActivityAware
 * 接口与 Flutter 引擎集成。
 */
class CallNotificationPlugin : FlutterPlugin, ActivityAware, MethodCallHandler,
    PluginRegistry.ActivityResultListener {

    companion object {
        /** MethodChannel 通道名称，与 Dart 端约定一致 */
        const val CHANNEL_NAME = "call_notification"

        /** 请求码：通知权限 */
        const val REQUEST_CODE_NOTIFICATION_PERMISSION = 10002

        /** 请求码：悬浮窗权限设置页 */
        const val REQUEST_CODE_OVERLAY_PERMISSION = 10003

        /** 插件单例，供 Service/Activity/Receiver 回调使用 */
        lateinit var instance: CallNotificationPlugin
            private set
    }

    private var methodChannel: MethodChannel? = null
    private var activity: Activity? = null
    private var context: Context? = null

    //region FlutterPlugin 生命周期

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        instance = this
        context = binding.applicationContext
        // 预创建通知渠道，便于后续权限检查读取渠道状态
        CallNotificationService.createNotificationChannel(binding.applicationContext)
        methodChannel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME)
        methodChannel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }

    //endregion

    //region ActivityAware 生命周期

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
        // 检查 Activity 是否携带来电接听数据（从 CallActivity 唤出时携带）
        checkCallActionIntent(binding.activity.intent)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
        checkCallActionIntent(binding.activity.intent)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    //endregion

    //region MethodCallHandler

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "showCallNotification" -> {
                @Suppress("UNCHECKED_CAST")
                val data = call.arguments as Map<String, Any>
                showCallNotification(data)
                result.success(null)
            }
            "checkNotificationPermission" -> {
                result.success(checkNotificationPermission())
            }
            "requestNotificationPermission" -> {
                result.success(requestNotificationPermission())
            }
            // 悬浮窗权限（在其他应用上层显示）
            "checkOverlayPermission" -> {
                result.success(checkOverlayPermission())
            }
            "requestOverlayPermission" -> {
                result.success(requestOverlayPermission())
            }
            // 打开应用详情设置页（用于引导用户开启后台弹出界面等国产 ROM 专有权限）
            "openAppDetailSettings" -> {
                openAppDetailSettings()
                result.success(null)
            }
            // 后台弹出界面权限（国产 ROM 专有）
            "checkBackgroundPopupPermission" -> {
                result.success(checkBackgroundPopupPermission())
            }
            "requestBackgroundPopupPermission" -> {
                result.success(requestBackgroundPopupPermission())
            }
            // 获取当前 ROM 类型名称（用于 Dart 端展示针对性提示）
            "getRomName" -> {
                result.success(getRomName())
            }
            else -> result.notImplemented()
        }
    }

    //endregion

    //region 公开方法

    /**
     * 显示来电通知：启动前台服务
     *
     * 前置检查最低权限（通知权限 + 两个渠道启用），任一缺失则不执行并打印警告。
     * 优先直接启动前台服务；如果因后台限制启动失败，
     * 降级通过 AlarmManager 调度（确保后台场景也能触发）。
     */
    private fun showCallNotification(data: Map<String, Any>) {
        val ctx = context ?: return

        // 最低权限前置检查：通知权限 + 「音视频通话邀请通知」/「来电保活」渠道启用
        if (!CallNotificationService.checkPrerequisites(ctx)) return

        // 优先直接启动前台服务（前台状态下可靠）
        try {
            val intent = Intent(ctx, CallNotificationService::class.java).apply {
                action = CallNotificationService.ACTION_SHOW
                putExtra(CallNotificationService.EXTRA_CALL_DATA, HashMap(data))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            // Android 12+ 后台启动限制：ForegroundServiceStartNotAllowedException
            // 降级通过 AlarmManager 调度（广播上下文不受后台限制）
            CallAlarmReceiver.schedule(ctx, HashMap(data), 0)
        }
    }

    /** 检查通知权限 */
    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        val ctx = context ?: return false
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 请求通知权限 */
    private fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        val act = activity ?: return false
        if (checkNotificationPermission()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            act.requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATION_PERMISSION
            )
        }
        return false
    }

    /**
     * 检查悬浮窗权限（SYSTEM_ALERT_WINDOW）
     *
     * 此权限允许应用在其他应用上层显示界面，
     * 是来电通知能在其他应用打开时弹出悬浮层的关键权限。
     * Android 6.0+ 需用户手动授权，部分国产 ROM（MIUI 等）
     * 将此权限与"后台弹出界面"权限关联。
     */
    private fun checkOverlayPermission(): Boolean {
        val ctx = context ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(ctx)
        }
        return true // Android 6.0 以下默认允许
    }

    /**
     * 请求悬浮窗权限（打开系统设置页）
     *
     * 返回 `true` 表示已授权，`false` 表示需要用户手动授权。
     * 授权后用户需手动返回应用。
     */
    private fun requestOverlayPermission(): Boolean {
        val ctx = context ?: return false
        val act = activity ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(ctx)) return true
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
                act.startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
                return false
            } catch (e: Exception) {
                return false
            }
        }
        return true
    }

    /**
     * 打开应用详情设置页
     *
     * 用于引导用户开启国产 ROM 专有权限，例如：
     * - MIUI：后台弹出界面权限
     * - EMUI：后台弹出应用权限
     * - ColorOS：后台弹出界面权限
     *
     * 这些权限没有标准 Android API，只能引导用户手动开启。
     */
    private fun openAppDetailSettings() {
        val ctx = context ?: return
        val act = activity ?: return
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            act.startActivity(intent)
        } catch (e: Exception) {
            // 回退到应用设置页
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                act.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    //region 国产 ROM 专有权限

    /**
     * 检查后台弹出界面权限
     *
     * 此权限为国产 ROM（MIUI、EMUI、ColorOS 等）专有权限，
     * 允许应用从后台弹出界面（如来电通知悬浮层/Heads-up 通知）。
     * 没有标准 Android API 可检测，采用以下策略：
     * - MIUI：尝试读取系统设置项（非官方，可能随版本变化）
     * - 其他 ROM：回退到悬浮窗权限检查
     *
     * 注意：此检查不一定 100% 准确，建议配合 [requestBackgroundPopupPermission]
     * 引导用户手动确认。
     */
    private fun checkBackgroundPopupPermission(): Boolean {
        val ctx = context ?: return false
        if (isMiui()) {
            return try {
                val uri = Uri.parse("content://com.miui.securitycenter.provider/permission")
                val cursor = ctx.contentResolver.query(
                    uri, null,
                    "pkgName = ? AND permission = ?",
                    arrayOf(ctx.packageName, "background_popup"),
                    null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        it.getInt(it.getColumnIndexOrThrow("status")) == 0
                    } else false
                } ?: false
            } catch (e: Exception) {
                checkOverlayPermission()
            }
        }
        // 非 MIUI 设备，回退到悬浮窗权限（多数国产 ROM 将两者关联）
        return checkOverlayPermission()
    }

    /**
     * 请求后台弹出界面权限（改进版）
     *
     * 针对各国产 ROM 尝试直接跳转到"后台弹出界面"对应设置页：
     * - MIUI：安全中心 → 应用权限 → 后台弹出界面
     * - EMUI：应用启动管理 → 后台活动/允许后台弹出
     * - ColorOS：后台冻结 / 自启动管理
     * - Flyme：通知管理 → 后台弹出
     * - 其他：应用详情设置页
     *
     * 返回 `true` 表示已授权，`false` 表示需要用户手动授权。
     */
    private fun requestBackgroundPopupPermission(): Boolean {
        if (checkBackgroundPopupPermission()) return true
        val act = activity ?: return false
        val ctx = context ?: return false
        val pkg = ctx.packageName

        when {
            isMiui() -> {
                // MIUI：打开安全中心的应用权限编辑页（含后台弹出界面选项）
                try {
                    // 方案1：尝试直接跳转到后台弹出界面详情（部分版本支持）
                    val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setClassName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.permissions.PermissionsEditorActivity"
                        )
                        putExtra("extra_pkgname", pkg)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    act.startActivity(intent)
                    return false
                } catch (_: Exception) {}
                // 方案2：回退到安全中心应用详情页
                try {
                    val intent = Intent().apply {
                        component = android.content.ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.main.MainHomeActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    act.startActivity(intent)
                    return false
                } catch (_: Exception) {}
            }

            isEmui() || isHarmonyOS() -> {
                // EMUI/HarmonyOS：打开应用启动管理页面
                try {
                    val intent = Intent().apply {
                        component = android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.appcontrol.ui.StartupAppControlActivity"
                        )
                        putExtra("packageName", pkg)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    act.startActivity(intent)
                    return false
                } catch (_: Exception) {}
                // 回退：打开应用详情页
                openAppDetailSettings()
                return false
            }

            isColorOS() || isOriginOS() -> {
                // ColorOS/OriginOS：打开后台 freeze 管理
                try {
                    val intent = Intent().apply {
                        component = android.content.ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                        )
                        putExtra("package_name", pkg)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    act.startActivity(intent)
                    return false
                } catch (_: Exception) {}
                // 新版 ColorOS 可能路径不同
                try {
                    val intent = Intent().apply {
                        component = android.content.ComponentName(
                            "com.opos.permissionmanager",
                            "com.opos.permissionmanager.setting.SecureKeyInterceptCheckListActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    act.startActivity(intent)
                    return false
                } catch (_: Exception) {}
                openAppDetailSettings()
                return false
            }

            isFlyme() -> {
                // Flyme：打开应用权限管理中的后台管理
                try {
                    val intent = Intent("com.meizu.safe.security.SHOW_APPSEC").apply {
                        putExtra("packageName", pkg)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    act.startActivity(intent)
                    return false
                } catch (_: Exception) {}
                openAppDetailSettings()
                return false
            }
        }

        // 通用回退：打开应用详情设置页
        openAppDetailSettings()
        return false
    }

    /**
     * 获取当前 ROM 类型名称
     *
     * 返回值用于 Dart 端展示针对性的用户提示文案，
     * 例如："请在 MIUI 安全中心中开启「后台弹出界面」权限"。
     */
    fun getRomName(): String {
        return when {
            isMiui() -> "MIUI"
            isHarmonyOS() -> "HarmonyOS"
            isEmui() -> "EMUI"
            isColorOS() -> "ColorOS"
            isOriginOS() -> "OriginOS"
            isFlyme() -> "Flyme"
            else -> "Android"
        }
    }

    //region ROM 检测

    /** 检测是否为 MIUI */
    private fun isMiui(): Boolean = try {
        !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()
    } catch (_: Exception) { false }

    /** 检测是否为 EMUI（华为） */
    private fun isEmui(): Boolean = try {
        !getSystemProperty("ro.build.version.emui").isNullOrEmpty()
    } catch (_: Exception) { false }

    /** 检测是否为 HarmonyOS（鸿蒙） */
    private fun isHarmonyOS(): Boolean = try {
        getSystemProperty("ro.build.version.harmonyos")?.let { it.isNotEmpty() && it != "UNAVAILABLE" } == true
    } catch (_: Exception) { false }

    /** 检测是否为 ColorOS（OPPO） */
    private fun isColorOS(): Boolean = try {
        !getSystemProperty("ro.build.version.opporom").isNullOrEmpty() ||
        !getSystemProperty("ro.color.os.version").isNullOrEmpty()
    } catch (_: Exception) { false }

    /** 检测是否为 OriginOS（vivo） */
    private fun isOriginOS(): Boolean = try {
        !getSystemProperty("ro.vivo.os.build.display.id").isNullOrEmpty() ||
        !getSystemProperty("ro.vivo.os.version").isNullOrEmpty()
    } catch (_: Exception) { false }

    /** 检测是否为 Flyme（魅族） */
    private fun isFlyme(): Boolean = try {
        !getSystemProperty("ro.build.display.id").orEmpty().contains("Flyme", ignoreCase = true) ||
        !getSystemProperty("ro.build.version.incremental").orEmpty().contains("Flyme", ignoreCase = true) ||
        !getSystemProperty("ro.config.hw_version").orEmpty().contains("Meizu", ignoreCase = true)
    } catch (_: Exception) { false }

    /**
     * 读取系统属性
     */
    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (e: Exception) {
            null
        }
    }

    //endregion

    //endregion

    /**
     * 将来电操作回调给 Dart 端
     *
     * 由 Service/Activity/Receiver 调用，通过 MethodChannel
     * 将用户的接听/拒绝操作传递到 Dart 层。
     */
    fun notifyCallAction(action: String, callId: String) {
        methodChannel?.invokeMethod("onCallAction", mapOf(
            "action" to action,
            "callId" to callId
        ))
        // 接听时额外触发 onCallAnswered 携参回调（与 CallActivity.handleAnswer 一致）
        if (action == "answer") {
            val callData = CallNotificationService.currentCallData?.toMutableMap()
                ?: mutableMapOf()
            callData["action"] = "answer"
            methodChannel?.invokeMethod("onCallAnswered", callData)
        }
    }

    //endregion

    //region 内部方法

    /**
     * 检查 Intent 是否携带来电接听数据
     *
     * 当用户在 CallActivity 中点击接听后，会启动 MainActivity 并携带：
     * - call_action: "answer"（标识来源为接听操作）
     * - call_data: 完整来电数据 Map
     *
     * 读取到数据后，通过 MethodChannel 回调给 Dart 端的 [onCallAnswered] 回调，
     * 以便应用在接听后执行后续操作（如跳转到通话界面）。
     */
    private fun checkCallActionIntent(intent: Intent?) {
        if (intent == null) return
        val callAction = intent.getStringExtra("call_action") ?: return
        if (callAction != "answer") return

        @Suppress("DEPRECATION")
        val callData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(
                CallNotificationService.EXTRA_CALL_DATA,
                HashMap::class.java
            )
        } else {
            intent.getSerializableExtra(CallNotificationService.EXTRA_CALL_DATA)
        } as? HashMap<String, Any> ?: return

        // 回调给 Dart 端
        methodChannel?.invokeMethod("onCallAnswered", HashMap(callData))

        // 清除 Intent 中的标记，避免重复触发
        intent.removeExtra("call_action")
        intent.removeExtra(CallNotificationService.EXTRA_CALL_DATA)
    }

    /**
     * 处理 MainActivity 的 onNewIntent
     *
     * 当应用已在后台运行时，CallActivity 接听后会通过 singleTop 模式
     * 触发 MainActivity 的 onNewIntent，需要在此处检查来电数据。
     *
     * 此方法应在 MainActivity.onNewIntent 中调用。
     */
    fun handleNewIntent(intent: Intent?) {
        checkCallActionIntent(intent)
    }

    //endregion

    //region ActivityResultListener

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        // 权限请求返回结果由各 check 方法重新检查
        return false
    }

    //endregion
}
