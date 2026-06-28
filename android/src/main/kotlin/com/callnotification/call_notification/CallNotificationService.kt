package com.callnotification.call_notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.app.NotificationCompat

/**
 * 来电通知前台服务
 *
 * 负责创建通知渠道、显示来电通知、管理铃声与振动。
 * 通过前台服务确保通知不会被系统回收，实现类似微信的来电保活能力。
 *
 * ## 场景支持
 *
 * ### 锁屏状态（不论是否传入 directToFullScreen）
 * - 显示 CallStyle 来电通知在锁屏消息栏（ongoing，不可划掉）
 * - 不显示悬浮通知，不显示全屏
 * - 点击消息 → 唤出解锁界面
 * - 用户解锁后自动切换到解锁模式（悬浮通知或直接全屏）
 *
 * ### 解锁状态 + directToFullScreen=false
 * - 显示 WindowManager 顶部悬浮通知
 * - 消息栏发布可划掉通知（用户划掉可取消来电）
 * - 点击悬浮通知或消息栏消息 → 打开全屏来电界面
 *
 * ### 解锁状态 + directToFullScreen=true
 * - 直接启动 CallActivity 全屏来电界面
 * - 不显示悬浮通知
 *
 * ## 注意事项
 * - 全屏仅在三种情况出现：点击悬浮通知 / 解锁状态点击消息栏消息 / directToFullScreen=true
 * - 任何情况下不可通过 CallStyle 调起触发顶部悬浮通知（已有 WindowManager 悬浮通知）
 * - directToFullScreen=true 时任何情况下不可出现悬浮通知
 * - 锁屏状态下任何情况不可出现全屏显示
 *
 * ## 操作处理
 * 1. ACTION_SHOW → 显示来电通知
 * 2. ACTION_ANSWER → 通知 Dart 端接听、停止服务
 * 3. ACTION_REJECT → 通知 Dart 端拒绝、停止服务
 * 4. ACTION_DISMISS → 用户滑动删除通知，停止铃声与服务
 */
class CallNotificationService : Service() {

    companion object {
        private const val TAG = "CallNotificationService"

        const val ACTION_SHOW = "com.callnotification.call_notification.SHOW"
        const val ACTION_ANSWER = "com.callnotification.call_notification.ANSWER"
        const val ACTION_REJECT = "com.callnotification.call_notification.REJECT"
        const val ACTION_DISMISS = "com.callnotification.call_notification.DISMISS"

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALL_DATA = "call_data"

        /** 锁屏来电通知渠道 ID（IMPORTANCE_HIGH，锁屏消息栏可见） */
        const val CHANNEL_ID_LOCK_SCREEN = "call_notification_channel_lock_screen"

        /** 静默保活 + 可划掉消息栏通知渠道 ID（IMPORTANCE_LOW，不弹 Heads-up） */
        const val CHANNEL_ID_SILENT = "call_notification_channel_silent"

        /** 锁屏来电通知渠道显示名（用户在系统设置中可见） */
        const val CHANNEL_NAME_LOCK_SCREEN = "音视频通话邀请通知"

        /** 静默保活渠道显示名（用户在系统设置中可见） */
        const val CHANNEL_NAME_SILENT = "来电保活"

        /** 前台服务通知 ID（锁屏 CallStyle 或 静默保活） */
        private const val NOTIFICATION_ID = 1001

        /** 解锁状态可划掉消息栏通知 ID */
        private const val CALL_NOTIFICATION_ID = 1002

        /** 当前来电数据，供 CallActivity 等组件读取 */
        var currentCallData: Map<String, Any>? = null
            private set

        /**
         * 创建通知渠道（幂等，可重复调用）
         *
         * 在插件初始化时预创建，便于后续权限检查读取渠道状态。
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // 锁屏来电通知渠道（IMPORTANCE_HIGH，锁屏消息栏可见，ongoing 不可划掉）
            val lockScreenChannel = NotificationChannel(
                CHANNEL_ID_LOCK_SCREEN,
                CHANNEL_NAME_LOCK_SCREEN,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "锁屏状态下的音视频来电邀请通知"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    audioAttributes
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500)
            }

            // 静默保活 + 可划掉消息栏通知渠道（IMPORTANCE_LOW，不弹 Heads-up）
            val silentChannel = NotificationChannel(
                CHANNEL_ID_SILENT,
                CHANNEL_NAME_SILENT,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "来电保活与消息栏通知（不弹出提示）"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setSound(null, null)
                enableVibration(false)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(lockScreenChannel)
            manager.createNotificationChannel(silentChannel)
        }

        /**
         * 来电通知最低权限前置检查
         *
         * 检查项：
         * - 通知权限（POST_NOTIFICATIONS，Android 13+）
         * - 「音视频通话邀请通知」渠道启用
         * - 「来电保活」渠道启用
         *
         * 任一不满足则打印警告日志并返回 `false`，
         * 提示开发者调用 `CallNotification.instance.requestPermissions()` 请求权限。
         *
         * @return `true` 全部满足；`false` 任一缺失（来电通知不执行）
         */
        fun checkPrerequisites(context: Context): Boolean {
            // 1. 检查通知权限（Android 13+ 运行时权限）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = context.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    Log.w(
                        TAG,
                        "来电通知未执行：缺少通知权限（POST_NOTIFICATIONS）。\n" +
                            "请先调用 CallNotification.instance.requestPermissions() 请求权限。"
                    )
                    return false
                }
            }

            // 2. 检查渠道启用状态（Android 8+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 确保渠道已创建（首次调用时创建）
                createNotificationChannel(context)

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val lockChannel = manager.getNotificationChannel(CHANNEL_ID_LOCK_SCREEN)
                if (lockChannel == null || lockChannel.importance == NotificationManager.IMPORTANCE_NONE) {
                    Log.w(
                        TAG,
                        "来电通知未执行：渠道「$CHANNEL_NAME_LOCK_SCREEN」已禁用。\n" +
                            "请引导用户在系统「应用通知设置」中重新启用该渠道。"
                    )
                    return false
                }

                val silentChannel = manager.getNotificationChannel(CHANNEL_ID_SILENT)
                if (silentChannel == null || silentChannel.importance == NotificationManager.IMPORTANCE_NONE) {
                    Log.w(
                        TAG,
                        "来电通知未执行：渠道「$CHANNEL_NAME_SILENT」已禁用。\n" +
                            "请引导用户在系统「应用通知设置」中重新启用该渠道。"
                    )
                    return false
                }
            }

            return true
        }
    }

    private var ringtoneManager: CallRingtoneManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 锁屏→解锁状态监听接收器 */
    private var unlockStateReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        ringtoneManager = CallRingtoneManager(this)
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_SHOW -> handleShow(intent)
            ACTION_ANSWER -> handleAnswer(intent)
            ACTION_REJECT -> handleReject(intent)
            ACTION_DISMISS -> handleDismiss()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    //region 操作处理

    private fun handleShow(intent: Intent) {
        try {
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(EXTRA_CALL_DATA, HashMap::class.java)
            } else {
                intent.getSerializableExtra(EXTRA_CALL_DATA)
            } as? HashMap<String, Any> ?: return
            currentCallData = data.toMap()

            wakeUpScreen()
            ringtoneManager?.startRinging()
            showNotification(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleAnswer(intent: Intent) {
        val callId = intent.getStringExtra("callId")
            ?: currentCallData?.get("callId") as? String ?: ""
        handleAnswer(callId)
    }

    private fun handleReject(intent: Intent) {
        val callId = intent.getStringExtra("callId")
            ?: currentCallData?.get("callId") as? String ?: ""
        handleReject(callId)
    }

    /** 处理接听操作 */
    private fun handleAnswer(callId: String) {
        ringtoneManager?.stopRinging()
        CallNotificationPlugin.instance.notifyCallAction("answer", callId)
        stopCallNotification()
        launchMainActivityWithCallData()
    }

    /** 处理拒绝操作 */
    private fun handleReject(callId: String) {
        ringtoneManager?.stopRinging()
        CallNotificationPlugin.instance.notifyCallAction("reject", callId)
        stopCallNotification()
    }

    /** 处理用户滑动删除通知 */
    private fun handleDismiss() {
        val callId = currentCallData?.get("callId") as? String ?: ""
        ringtoneManager?.stopRinging()
        CallNotificationPlugin.instance.notifyCallAction("timeout", callId)
        stopCallNotification()
    }

    //endregion

    //region 显示通知入口

    private fun showNotification(data: Map<String, Any>) {
        val directToFullScreen = data["directToFullScreen"] as? Boolean == true

        if (isScreenLocked()) {
            // 锁屏：显示 CallStyle 通知，不显示悬浮窗/全屏
            showLockScreenNotification(data)
            return
        }

        // 解锁状态
        if (directToFullScreen) {
            showFullScreenMode(data)
        } else {
            showOverlayMode(data)
        }
    }

    //endregion

    //region 锁屏模式

    /**
     * 锁屏模式：显示 CallStyle 来电通知在锁屏消息栏
     *
     * - 通知 ongoing 不可滑动删除（与微信一致）
     * - 不设置 fullScreenIntent（锁屏不可全屏）
     * - contentIntent 指向 MainActivity（点击唤出解锁界面）
     * - 注册解锁监听，用户解锁后自动切换到解锁模式
     */
    private fun showLockScreenNotification(data: Map<String, Any>) {
        val callerName = data["callerName"] as? String ?: "未知来电"
        val callType = data["callType"] as? String ?: "audio"
        val callId = data["callId"] as? String ?: ""
        val callTypeText = if (callType == "video") "视频通话" else "语音通话"

        // contentIntent 指向 MainActivity（锁屏点击会要求用户解锁）
        val contentPendingIntent = createMainActivityPendingIntent()

        // 构建 CallStyle 锁屏通知（ongoing=true，不可划掉，无 deleteIntent）
        val lockScreenNotification = buildLockScreenCallStyleNotification(
            callerName, callTypeText, callId, data, contentPendingIntent
        )

        startForegroundWithNotification(lockScreenNotification)

        // 注册解锁监听
        registerUnlockStateReceiver()
    }

    //endregion

    //region 解锁模式

    /**
     * 解锁 + 悬浮窗模式
     *
     * - 前台服务：静默保活通知（不弹 Heads-up）
     * - 显示 WindowManager 顶部悬浮通知
     * - 消息栏发布可划掉通知（用户划掉可取消来电）
     * - 点击悬浮通知或消息栏消息 → 打开全屏来电界面
     */
    private fun showOverlayMode(data: Map<String, Any>) {
        val callerName = data["callerName"] as? String ?: "未知来电"
        val callType = data["callType"] as? String ?: "audio"
        val callId = data["callId"] as? String ?: ""
        val callTypeText = if (callType == "video") "视频通话" else "语音通话"
        val subtitle = data["subtitle"] as? String

        // 1. 前台服务保活（静默通知）
        val silentNotification = buildSilentKeepAliveNotification(callerName, callTypeText)
        startForegroundWithNotification(silentNotification)

        // 2. 点击悬浮窗/消息栏 → 打开全屏来电界面
        val fullScreenIntent = Intent(this, CallActivity::class.java).apply {
            putExtra(EXTRA_CALL_DATA, HashMap(data))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. 显示悬浮窗
        val overlay = CallOverlayView.getInstance(this)
        overlay.show(
            callerName = callerName,
            callType = callType,
            callId = callId,
            subtitle = subtitle,
            onAnswer = { handleAnswer(callId) },
            onReject = { handleReject(callId) },
            onOpenFullScreen = {
                CallOverlayView.getInstance(this).dismiss()
                contentPendingIntent.send()
            },
            data = data
        )

        // 4. 发布可划掉消息栏通知（用户可划掉取消来电）
        publishSwipeableNotification(
            callerName, callTypeText, data, contentPendingIntent
        )
    }

    /**
     * 解锁 + 全屏模式
     *
     * - 前台服务：静默保活通知
     * - 直接启动 CallActivity 全屏来电界面
     * - 不显示悬浮通知
     */
    private fun showFullScreenMode(data: Map<String, Any>) {
        val callerName = data["callerName"] as? String ?: "未知来电"
        val callType = data["callType"] as? String ?: "audio"
        val callTypeText = if (callType == "video") "视频通话" else "语音通话"

        // 1. 前台服务保活（静默通知）
        val silentNotification = buildSilentKeepAliveNotification(callerName, callTypeText)
        startForegroundWithNotification(silentNotification)

        // 2. 直接启动全屏来电界面
        val fullScreenIntent = Intent(this, CallActivity::class.java).apply {
            putExtra(EXTRA_CALL_DATA, HashMap(data))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(fullScreenIntent)
    }

    /**
     * 用户解锁后：从锁屏模式切换到解锁模式
     *
     * - 取消锁屏 CallStyle 通知（避免解锁后 CallStyle 弹 Heads-up）
     * - 根据 directToFullScreen 决定显示悬浮通知还是直接全屏
     */
    private fun switchToUnlockedMode() {
        val data = currentCallData ?: return
        val directToFullScreen = data["directToFullScreen"] as? Boolean == true

        // 取消锁屏 CallStyle 通知（NOTIFICATION_ID 由后续 startForeground 替换）
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)

        // 注销解锁监听（已切换到解锁模式）
        unregisterUnlockStateReceiver()

        if (directToFullScreen) {
            showFullScreenMode(data)
        } else {
            showOverlayMode(data)
        }
    }

    //endregion

    //region 通知构建

    /** 构建静默保活通知（前台服务保活，不弹 Heads-up） */
    private fun buildSilentKeepAliveNotification(
        callerName: String,
        callTypeText: String
    ): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_SILENT)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(callerName)
            .setContentText(callTypeText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    /**
     * 构建 CallStyle 锁屏来电通知（仅用于锁屏前台服务通知）
     *
     * 注意：
     * - 不设置 fullScreenIntent（锁屏不可全屏）
     * - ongoing=true（锁屏不可划掉，与微信一致）
     * - 仅作为前台服务通知发布（Android 14+ 要求 CallStyle 必须是前台服务通知）
     */
    private fun buildLockScreenCallStyleNotification(
        callerName: String,
        callTypeText: String,
        callId: String,
        data: Map<String, Any>,
        contentPendingIntent: PendingIntent?
    ): Notification {
        val subtitle = data["subtitle"] as? String ?: callTypeText

        // 接听按钮 → ACTION_ANSWER
        val answerIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = ACTION_ANSWER
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALL_DATA, HashMap(data))
        }
        val answerPendingIntent = PendingIntent.getBroadcast(
            this, System.currentTimeMillis().toInt(), answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 挂断按钮 → ACTION_REJECT
        val rejectIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = ACTION_REJECT
            putExtra(EXTRA_CALL_ID, callId)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            this, System.currentTimeMillis().toInt(), rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 来电者信息（含头像）
        val callerAvatarUrl = data["callerAvatarUrl"] as? String
        val personBuilder = Person.Builder()
            .setName(callerName)
            .setImportant(true)
        if (!callerAvatarUrl.isNullOrEmpty()) {
            try {
                val avatarBitmap = loadAvatarBitmap(callerAvatarUrl)
                if (avatarBitmap != null) {
                    personBuilder.setIcon(IconCompat.createWithBitmap(avatarBitmap))
                }
            } catch (_: Exception) { }
        }

        @Suppress("DEPRECATION")
        val callStyle = NotificationCompat.CallStyle.forIncomingCall(
            personBuilder.build(),
            rejectPendingIntent,
            answerPendingIntent
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_LOCK_SCREEN)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(callerName)
            .setContentText(subtitle)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .apply {
                if (contentPendingIntent != null) {
                    setContentIntent(contentPendingIntent)
                }
                // 设置大图标
                if (!callerAvatarUrl.isNullOrEmpty()) {
                    try {
                        val avatarBitmap = loadAvatarBitmap(callerAvatarUrl)
                        if (avatarBitmap != null) setLargeIcon(avatarBitmap)
                    } catch (_: Exception) { }
                } else {
                    try {
                        val appIcon = getAppIconBitmap()
                        if (appIcon != null) setLargeIcon(appIcon)
                    } catch (_: Exception) { }
                }
            }
            .setStyle(callStyle)
            .build()
    }

    /**
     * 构建可划掉消息栏通知（普通样式，非 CallStyle）
     *
     * Android 14+ 限制：CallStyle 通知必须是前台服务通知或使用 fullScreenIntent。
     * 可划掉通知既不是前台服务通知，也不使用 fullScreenIntent（避免锁屏全屏），
     * 因此不能用 CallStyle，改用普通通知样式。
     *
     * 特性：
     * - 渠道：CHANNEL_ID_SILENT（IMPORTANCE_LOW，不弹 Heads-up）
     * - ongoing=false（可被用户左右滑动删除）
     * - setDeleteIntent（划掉触发 ACTION_DISMISS）
     * - contentIntent（点击打开全屏来电界面）
     */
    private fun buildSwipeableNotification(
        callerName: String,
        callTypeText: String,
        data: Map<String, Any>,
        contentPendingIntent: PendingIntent
    ): Notification {
        val subtitle = data["subtitle"] as? String ?: callTypeText

        return NotificationCompat.Builder(this, CHANNEL_ID_SILENT)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(callerName)
            .setContentText(subtitle)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(false)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(createDismissPendingIntent())
            .apply {
                // 设置大图标
                val callerAvatarUrl = data["callerAvatarUrl"] as? String
                if (!callerAvatarUrl.isNullOrEmpty()) {
                    try {
                        val avatarBitmap = loadAvatarBitmap(callerAvatarUrl)
                        if (avatarBitmap != null) setLargeIcon(avatarBitmap)
                    } catch (_: Exception) { }
                } else {
                    try {
                        val appIcon = getAppIconBitmap()
                        if (appIcon != null) setLargeIcon(appIcon)
                    } catch (_: Exception) { }
                }
            }
            .build()
    }

    /** 发布可划掉消息栏通知（解锁状态，用户可划掉取消来电） */
    private fun publishSwipeableNotification(
        callerName: String,
        callTypeText: String,
        data: Map<String, Any>,
        contentPendingIntent: PendingIntent
    ) {
        val swipeableNotification = buildSwipeableNotification(
            callerName, callTypeText, data, contentPendingIntent
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(CALL_NOTIFICATION_ID, swipeableNotification)
    }

    /** 启动前台服务通知 */
    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 创建滑动删除通知时的 PendingIntent */
    private fun createDismissPendingIntent(): PendingIntent {
        val dismissIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = ACTION_DISMISS
        }
        return PendingIntent.getBroadcast(
            this, 99, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 创建 MainActivity 的 PendingIntent（用于锁屏点击唤出解锁界面） */
    private fun createMainActivityPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    //endregion

    //region 解锁监听

    private fun registerUnlockStateReceiver() {
        if (unlockStateReceiver != null) return
        unlockStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    switchToUnlockedMode()
                }
            }
        }
        try {
            registerReceiver(unlockStateReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        } catch (_: Exception) {}
    }

    private fun unregisterUnlockStateReceiver() {
        unlockStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        unlockStateReceiver = null
    }

    //endregion

    //region 辅助方法

    private fun isScreenLocked(): Boolean {
        try {
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguard != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (keyguard.isKeyguardLocked || keyguard.isDeviceLocked) return true
                } else {
                    @Suppress("DEPRECATION")
                    if (keyguard.inKeyguardRestrictedInputMode()) return true
                }
            }
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            if (powerManager?.isScreenOn != true) return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "CallNotification::WakeLock"
            )
            lock.acquire(30_000L)
            wakeLock = lock

            @Suppress("DEPRECATION")
            if (!powerManager.isScreenOn) {
                @Suppress("DEPRECATION")
                val fullLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "CallNotification::FullWakeLock"
                )
                fullLock.acquire(10_000L)
                fullLock.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadAvatarBitmap(source: String): Bitmap? {
        return try {
            when {
                !source.startsWith("/") && !source.startsWith("http") && !source.startsWith("content") -> {
                    val resId = resources.getIdentifier(source, "drawable", packageName)
                    if (resId != 0) {
                        BitmapFactory.decodeResource(resources, resId)
                    } else {
                        val mipmapId = resources.getIdentifier(source, "mipmap", packageName)
                        if (mipmapId != 0) BitmapFactory.decodeResource(resources, mipmapId) else null
                    }
                }
                source.startsWith("/") -> {
                    val file = java.io.File(source)
                    if (file.exists()) BitmapFactory.decodeFile(source) else null
                }
                source.startsWith("http") -> {
                    val url = java.net.URL(source)
                    val connection = url.openConnection()
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    val inputStream = connection.getInputStream()
                    BitmapFactory.decodeStream(inputStream)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getAppIconBitmap(): Bitmap? {
        return try {
            val packageManager = applicationContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(applicationContext.packageName, 0)
            BitmapFactory.decodeResource(resources, applicationInfo.icon)
        } catch (e: Exception) {
            null
        }
    }

    private fun launchMainActivityWithCallData() {
        try {
            val mainIntent = Intent(applicationContext, Class.forName(
                "${applicationContext.packageName}.MainActivity"
            )).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("call_action", "answer")
                @Suppress("DEPRECATION")
                val callData = currentCallData as? java.io.Serializable
                if (callData != null) {
                    putExtra(EXTRA_CALL_DATA, callData)
                }
            }
            startActivity(mainIntent)
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    //endregion

    //region 生命周期

    private fun stopCallNotification() {
        ringtoneManager?.stopRinging()
        releaseWakeLock()
        currentCallData = null
        unregisterUnlockStateReceiver()
        CallActivity.activeInstance?.finish()
        CallOverlayView.getInstance(this).dismiss()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(CALL_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        ringtoneManager?.stopRinging()
        releaseWakeLock()
        currentCallData = null
        super.onDestroy()
    }

    //endregion
}
