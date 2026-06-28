package com.callnotification.call_notification

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView

/**
 * 来电悬浮窗管理器
 *
 * 通过 WindowManager 在屏幕最顶层渲染来电通知，
 * 不受 ROM 通知限制（MIUI/EMUI/ColorOS 均可正常弹出）。
 *
 * 使用内置默认样式（微信风格）。
 */
class CallOverlayView private constructor(private val context: Context) {

    /** 窗口管理器 */
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 悬浮窗根视图 */
    private var overlayView: View? = null

    /** 右侧按钮区域引用（用于锁屏时隐藏） */
    private var buttonAreaView: View? = null

    /** 当前来电 ID */
    private var currentCallId: String? = null

    /** 缓存的接听/挂断回调（用于锁屏隐藏按钮后，解锁恢复按钮时重新绑定） */
    private var cachedOnAnswer: (() -> Unit)? = null
    private var cachedOnReject: (() -> Unit)? = null
    private var cachedOnOpenFullScreen: (() -> Unit)? = null

    /** 解锁广播接收器：设备解锁后恢复显示操作按钮 */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!isDeviceLocked()) {
                buttonAreaView?.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        @Volatile
        private var instance: CallOverlayView? = null

        fun getInstance(context: Context): CallOverlayView {
            return instance ?: synchronized(this) {
                instance ?: CallOverlayView(context.applicationContext).also { instance = it }
            }
        }
    }

    // ==================== 公开 API ====================

    /**
     * 显示来电悬浮窗（内置默认样式，微信风格）
     *
     * @param callerName 来电者名称
     * @param callType 通话类型：audio/video
     * @param callId 通话标识
     * @param subtitle 副标题（可选，不传则根据 callType 自动生成）
     * @param onAnswer 接听回调
     * @param onReject 挂断回调
     * @param onOpenFullScreen 点击内容区域回调（打开全屏界面）
     *
     * 以下参数从 data Map 中读取（通过 [show] 调用时传入）：
     * - answerButtonText: 接听按钮文案，默认"接听"
     * - rejectButtonText: 拒绝按钮文案，默认"拒绝"
     * - showAnswerButton: 是否显示接听按钮，默认 true
     * - showRejectButton: 是否显示拒绝按钮，默认 true
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(
        callerName: String,
        callType: String,
        callId: String,
        subtitle: String? = null,
        onAnswer: () -> Unit,
        onReject: () -> Unit,
        onOpenFullScreen: () -> Unit,
        // 按钮自定义参数
        data: Map<String, Any>? = null
    ) {
        dismiss()
        currentCallId = callId
        cachedOnAnswer = onAnswer
        cachedOnReject = onReject
        cachedOnOpenFullScreen = onOpenFullScreen

        val effectiveSubtitle = subtitle ?: if (callType == "video") "邀请你视频通话" else "邀请你语音通话"

        // 从 data 中读取按钮自定义参数
        val answerText = data?.get("answerButtonText") as? String ?: "接听"
        val rejectText = data?.get("rejectButtonText") as? String ?: "拒绝"
        val showAnswerBtn = data?.get("showAnswerButton") as? Boolean != false
        val showRejectBtn = data?.get("showRejectButton") as? Boolean != false

        val rootView = buildDefaultOverlay(
            callerName, callType, effectiveSubtitle,
            onAnswer, onReject, onOpenFullScreen,
            answerText, rejectText, showAnswerBtn, showRejectBtn
        )
        attachToWindow(rootView)
    }

    /** 关闭悬浮窗（带出场动画） */
    fun dismiss() {
        val view = overlayView ?: return
        // 立即清空引用，防止：
        // 1. 重复调用导致双重动画
        // 2. 旧动画的 withEndAction 覆盖新视图的引用
        overlayView = null
        currentCallId = null
        buttonAreaView = null

        // 注销解锁广播
        try { context.unregisterReceiver(unlockReceiver) } catch (_: Exception) {}

        // 取消可能正在进行的入场动画
        view.animate().cancel()

        // 纯向上滑出（不做 alpha 渐隐，避免 WindowManager 悬浮窗渲染闪烁）
        view.animate()
            .translationY(dpToPx(-120f))
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator(0.85f))
            .withEndAction {
                // 动画结束后安全移除
                view.visibility = View.GONE
                try { windowManager.removeView(view) } catch (_: Exception) {}
            }
            .start()
    }

    /** 判断悬浮窗是否正在显示 */
    fun isShowing(): Boolean = overlayView != null

    // ==================== 内置默认样式构建 ====================

    /** 构建内置微信风格的默认悬浮窗视图 */
    private fun buildDefaultOverlay(
        callerName: String,
        callType: String,
        subtitle: String,
        onAnswer: () -> Unit,
        onReject: () -> Unit,
        onOpenFullScreen: () -> Unit,
        answerText: String = "接听",
        rejectText: String = "拒绝",
        showAnswerBtn: Boolean = true,
        showRejectBtn: Boolean = true
    ): View {
        // 根容器（水平布局，深色半透明背景，圆角）
        val rootView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createBackgroundDrawable()
            setPadding(dpToPx(16f).toInt(), dpToPx(12f).toInt(), dpToPx(12f).toInt(), dpToPx(12f).toInt())
        }

        // 头像（独立左侧元素，垂直居中，高度固定 = avatarSize）
        val avatarSize = dpToPx(44f).toInt()
        val avatar = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
            setImageResource(android.R.drawable.sym_contact_card)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#E5E5E5"))
            }
            clipToOutline = true
        }
        rootView.addView(avatar)

        // 中间区域：名称 + 副标题（高度与头像一致 = avatarSize，垂直居中排列两行文字）
        val middleArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, avatarSize, 1f).apply {
                marginStart = dpToPx(12f).toInt()
            }
        }

        val nameText = TextView(context).apply {
            text = callerName; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        middleArea.addView(nameText)

        val subtitleText = TextView(context).apply {
            text = subtitle; textSize = 13f; setTextColor(0xB3FFFFFF.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(2f).toInt() }
        }
        middleArea.addView(subtitleText)
        rootView.addView(middleArea)

        // 右侧区域：挂断 + 接听按钮（可选，独立控制显隐）
        if (showAnswerBtn || showRejectBtn) {
            val rightArea = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(12f).toInt(), 0, 0, 0)
            }

            // 拒绝按钮（根据 showRejectBtn 独立控制）
            if (showRejectBtn) {
                val rejectBtn = createRejectButton(rejectText)
                rejectBtn.setOnClickListener { dismiss(); onReject() }
                rightArea.addView(rejectBtn)
            }

            // 接听按钮（根据 showAnswerBtn 独立控制）
            if (showAnswerBtn) {
                val answerBtn = createAnswerButton(answerText)
                answerBtn.setOnClickListener { dismiss(); onAnswer() }
                val answerParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dpToPx(16f).toInt() }
                rightArea.addView(answerBtn, answerParams)
            }

            rootView.addView(rightArea)
            buttonAreaView = rightArea
        }

        // 点击中间内容区域 → 打开全屏界面
        middleArea.setOnClickListener { onOpenFullScreen() }

        // 外层边距容器
        val wrapper = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            val m = dpToPx(16f).toInt()
            setPadding(m, dpToPx(8f).toInt(), m, dpToPx(8f).toInt())
        }
        wrapper.addView(rootView)
        return wrapper
    }

    // ==================== 窗口管理 ====================

    /** 将视图添加到 WindowManager 并播放入场动画 */
    private fun attachToWindow(rootView: View) {
        val params = createWindowParams()
        try {
            windowManager.addView(rootView, params)
            overlayView = rootView
            // 入场：从顶部滑入 + 淡入，使用 DecelerateInterpolator 自然减速
            rootView.translationY = dpToPx(-120f)
            rootView.alpha = 0f
            rootView.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator(0.9f))
                .start()

            // 注册解锁广播，用于锁屏→解锁后恢复按钮显示
            try {
                context.registerReceiver(unlockReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                })
            } catch (_: Exception) {}

            // 锁屏状态下隐藏操作按钮区域
            if (isDeviceLocked()) {
                buttonAreaView?.visibility = View.GONE
            }
        } catch (e: Exception) {
            // 权限不足时静默失败
        }
    }

    // ==================== UI 组件工厂方法 ====================

    private fun createBackgroundDrawable(): GradientDrawable = GradientDrawable().apply {
        setColor(android.graphics.Color.parseColor("#CC000000"))
        cornerRadius = dpToPx(12f)
    }

    private fun createAnswerButton(label: String = "接听"): ImageView {
        val size = dpToPx(48f).toInt()
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(R.drawable.ic_answer)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = label
        }
    }

    private fun createRejectButton(label: String = "挂断"): ImageView {
        val size = dpToPx(48f).toInt()
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(R.drawable.ic_hangup)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = label
        }
    }

    private fun createWindowParams(): WindowManager.LayoutParams {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
        }.apply {
            gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
        }
    }

    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density

    /** 检测设备是否处于锁屏状态 */
    private fun isDeviceLocked(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            km?.isKeyguardLocked == true || km?.isDeviceLocked == true
        } else {
            km?.isKeyguardLocked == true
        }
    }
}
