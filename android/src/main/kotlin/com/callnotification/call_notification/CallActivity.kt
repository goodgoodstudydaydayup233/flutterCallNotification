package com.callnotification.call_notification

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 全屏来电界面
 *
 * 通过 Notification 的 fullScreenIntent 启动，或点击悬浮通知后进入。
 *
 * 始终展示完整来电界面（含接听/挂断按钮），不区分锁屏/解锁状态。
 *
 * 特性：
 * - 锁屏状态下显示（showWhenLocked）
 * - 自动点亮屏幕（turnScreenOn）
 * - 禁止返回键关闭
 * - 深色半透明背景
 */
class CallActivity : android.app.Activity() {

    companion object {
        /** 当前活跃的 Activity 实例，供 Service 关闭使用 */
        var activeInstance: CallActivity? = null
            private set
    }

    /** 当前来电数据缓存 */
    private var cachedData: HashMap<String, Any>? = null

    /** 来电者名称 */
    private var callerName: String = "未知来电"

    /** 通话类型 */
    private var callType: String = "audio"

    /** 通话 ID */
    private var callId: String = ""

    /** 接听按钮文本 */
    private var answerButtonText: String = "接听"

    /** 拒绝按钮文本 */
    private var rejectButtonText: String = "拒绝"

    /** 是否显示接听按钮（独立控制） */
    private var showAnswerButton: Boolean = true

    /** 是否显示拒绝按钮（独立控制） */
    private var showRejectButton: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowFlags()
        activeInstance = this
        parseIntentAndBuildUI(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntentAndBuildUI(intent)
    }

    override fun onDestroy() {
        if (activeInstance == this) activeInstance = null
        super.onDestroy()
    }

    /**
     * 解析 Intent 数据并构建完整来电界面
     */
    private fun parseIntentAndBuildUI(intent: Intent) {
        @Suppress("DEPRECATION")
        val data = intent.getSerializableExtra(CallNotificationService.EXTRA_CALL_DATA)
                as? HashMap<String, Any>
        cachedData = data
        callerName = data?.get("callerName") as? String ?: "未知来电"
        callType = data?.get("callType") as? String ?: "audio"
        callId = data?.get("callId") as? String ?: ""
        // 按钮文本与显示控制
        answerButtonText = data?.get("answerButtonText") as? String ?: "接听"
        rejectButtonText = data?.get("rejectButtonText") as? String ?: "拒绝"
        showAnswerButton = data?.get("showAnswerButton") as? Boolean != false
        showRejectButton = data?.get("showRejectButton") as? Boolean != false

        setupDefaultUI(callerName, callType, callId)
    }

    //region UI 构建方法

    /**
     * 使用内置默认样式构建全屏来电界面（微信风格）
     *
     * 布局结构（从上到下）：
     * - 上半区：头像 + 名称 + 通话类型副标题（垂直居中偏上）
     * - 下半区：拒绝（红色圆形）+ 接听（绿色圆形）按钮（底部两侧）
     */
    private fun setupDefaultUI(callerName: String, callType: String, callId: String) {
        val callTypeText = (cachedData?.get("subtitle") as? String)
            ?: if (callType == "video") "邀请你视频通话" else "邀请你语音通话"

        // 根容器
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF1A1A2E.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── 上半区：头像 + 名称 + 副标题（占据剩余空间并居中）──
        val topArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        // 头像（圆角矩形，支持自定义 URL 或使用默认图标）
        val avatarSize = dpToPx(96)
        val avatarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply { bottomMargin = dpToPx(24) }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(0xFF3A3A5C.toInt())
            }
        }

        // 尝试加载用户头像，失败则使用默认图标
        val avatarUrl = cachedData?.get("callerAvatarUrl") as? String
        if (!avatarUrl.isNullOrEmpty()) {
            try {
                val avatarView = ImageView(this)
                // TODO: 使用 Glide/Coil 等图片加载库异步加载网络/本地头像
                // 当前先使用默认图标占位
                avatarView.setImageResource(android.R.drawable.ic_menu_call)
                avatarView.setColorFilter(0xB3FFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                val iconSize = dpToPx(48)
                avatarView.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                avatarContainer.addView(avatarView)
            } catch (_: Exception) {
                addDefaultAvatarIcon(avatarContainer)
            }
        } else {
            addDefaultAvatarIcon(avatarContainer)
        }

        // 来电者名称
        val nameText = TextView(this).apply {
            text = callerName
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }

        // 通话类型副标题
        val typeText = TextView(this).apply {
            text = callTypeText
            textSize = 14f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        topArea.addView(avatarContainer)
        topArea.addView(nameText)
        topArea.addView(typeText)

        // ── 下半区：操作按钮（底部固定）──
        val bottomArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER or Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(56)
            }
        }

        // 拒绝按钮（根据 showRejectButton 独立控制显隐）
        if (showRejectButton) {
            val rejectBtn = createCircleButton(
                rejectButtonText,
                R.drawable.ic_hangup
            ) { handleReject(callId) }
            (rejectBtn.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0; weight = 1.0f; gravity = Gravity.END
            }
            bottomArea.addView(rejectBtn)
        }

        // 接听按钮（根据 showAnswerButton 独立控制显隐）
        if (showAnswerButton) {
            val answerBtn = createCircleButton(
                answerButtonText,
                R.drawable.ic_answer
            ) { handleAnswer(callId) }
            (answerBtn.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0; weight = 1.0f; gravity = Gravity.START
            }
            bottomArea.addView(answerBtn)
        }

        rootLayout.addView(topArea)
        rootLayout.addView(bottomArea)

        setContentView(rootLayout)
    }

    /** 添加默认头像图标到容器 */
    private fun addDefaultAvatarIcon(container: LinearLayout) {
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_call)
            val size = dpToPx(48)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setColorFilter(0xB3FFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        container.addView(icon)
    }

    private fun createCircleButton(text: String, iconRes: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val iconSize = dpToPx(64)
            val icon = ImageView(context).apply {
                setImageResource(iconRes); layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            addView(icon)
            // 文字标签始终显示
            val label = TextView(context).apply {
                this.text = text; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(8) }
            }
            addView(label)
            setOnClickListener { onClick() }
        }
    }

    //endregion

    //region 操作处理

    private fun handleAnswer(callId: String) {
        val serviceIntent = Intent(this, CallNotificationService::class.java).apply {
            action = CallNotificationService.ACTION_ANSWER; putExtra("callId", callId)
        }
        startService(serviceIntent)
        launchMainActivity(callId)
        finish()
    }

    private fun handleReject(callId: String) {
        startService(Intent(this, CallNotificationService::class.java).apply {
            action = CallNotificationService.ACTION_REJECT; putExtra("callId", callId)
        })
        finish()
    }

    private fun launchMainActivity(callId: String) {
        try {
            val mainIntent = Intent(this, Class.forName("${packageName}.MainActivity")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("call_action", "answer")
                @Suppress("DEPRECATION")
                val callData = intent.getSerializableExtra(CallNotificationService.EXTRA_CALL_DATA)
                if (callData != null) putExtra(CallNotificationService.EXTRA_CALL_DATA, callData as java.io.Serializable)
            }
            startActivity(mainIntent)
        } catch (_: Exception) {}
    }

    //endregion

    //region 工具方法

    /** 配置窗口属性：锁屏显示、亮屏、全屏 */
    private fun setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        }
    }

    override fun onBackPressed() {} // 拦截返回键

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    //endregion
}
