package com.callnotification.call_notification

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 来电铃声与振动管理器
 *
 * 负责播放系统来电铃声和触发振动效果，
 * 使用系统默认来电铃声，无需额外音频资源文件。
 *
 * 特性：
 * - 播放系统默认来电铃声（循环）
 * - 配合振动模式（500ms 振动 + 500ms 间隔，循环）
 * - 兼容 Android 12+ 的 VibratorManager API
 * - 铃声音量跟随系统来电音量
 */
class CallRingtoneManager(private val context: Context) {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var isRinging = false

    /** 振动模式：等待0ms → 振动500ms → 等待500ms，从索引1循环 */
    private val vibrationPattern = longArrayOf(0, 500, 500)

    /** 开始响铃与振动 */
    fun startRinging() {
        if (isRinging) return
        isRinging = true
        playRingtone()
        startVibration()
    }

    /** 停止响铃与振动 */
    fun stopRinging() {
        if (!isRinging) return
        isRinging = false
        stopRingtone()
        stopVibration()
    }

    /** 播放系统默认来电铃声 */
    private fun playRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri).apply {
                // 设置音频属性为来电类型，确保音量跟随来电音量
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                isLooping = true
                play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 停止铃声 */
    private fun stopRingtone() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        ringtone = null
    }

    /** 开始振动 */
    private fun startVibration() {
        try {
            vibrator = getVibrator()
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(
                        VibrationEffect.createWaveform(
                            vibrationPattern,
                            1 // 从 vibrationPattern 索引1开始循环
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(vibrationPattern, 1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 停止振动 */
    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        vibrator = null
    }

    /**
     * 获取 Vibrator 实例
     *
     * Android 12+ 使用 VibratorManager 获取，
     * 低版本直接从系统服务获取。
     */
    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
