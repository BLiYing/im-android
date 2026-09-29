package com.libeyond.imandroid.sdk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AlertResult
import com.libeyond.imandroid.data.NotifSound
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * 应用内提示音 + 振动的播放器（NOTIFICATIONS_DESIGN §3.2）。**进程内一份**（同 `voice.VoicePlayer`/
 * `rtc.RtcCall` 的单例风格）：设置页试听与真实来消息提醒要共用同一个 `SoundPool` 和节流状态。
 *
 * 两条平台落地规则（§3.2 Android 行）：
 * - `AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT`；
 * - 先查 `AudioManager.ringerMode`：非 `NORMAL` 不响；`VIBRATE` 模式下只振不响
 *   （`SILENT` 模式两者都不——比设计文档字面更保守一档，是本端对"静音到底该不该振"的解读，
 *   见任务收尾报告里记的这一条解读）。
 *
 * [AlertDecision] 的判定结果已经算好"该不该响/振"，这里只再叠一层**平台静音开关**——
 * 两层职责不同：前者是"这条消息按设置该不该提醒"，后者是"设备此刻允不允许出声"。
 */
object AlertPlayer {

    private val log = IMLog.tag("IM.Alert")
    private const val VIBRATE_MS = 40L

    private var appContext: Context? = null
    private var pool: SoundPool? = null
    private val streamIdBySound = mutableMapOf<NotifSound, Int>()
    private var lastStreamId = 0

    /** 判定层的节流状态：最近一次**真正**响或振过的时间戳（毫秒）。跟随进程，不持久化。 */
    @Volatile var lastSoundAt: Long = 0L
        private set

    /** [com.libeyond.imandroid.IMApp.onCreate] 里调一次即可。 */
    fun init(context: Context) {
        if (pool != null) return
        appContext = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setAudioAttributes(attrs).setMaxStreams(2).build()
        pool = p
        val ctx = appContext ?: return
        soundResources().forEach { (sound, res) ->
            runCatching { p.load(ctx, res, 1) }
                .onSuccess { streamIdBySound[sound] = it }
                .onFailure { log.w("alert_sound_load_failed", "sound" to sound.wire, "err" to it.javaClass.simpleName) }
        }
    }

    /**
     * 真实来消息时调用：按 [AlertDecision] 的结果响/振一次（[result.sound]/[result.vibrate] 皆假就是空操作，
     * 调用方不必先判）。**这里才是节流状态真正推进的地方**——判定函数只读 [lastSoundAt]、不写它。
     */
    fun play(result: AlertResult) {
        if (!result.sound && !result.vibrate) return
        val ctx = appContext ?: return
        val ringerMode = ringerMode(ctx)
        val soundOk = result.sound && ringerMode == AudioManager.RINGER_MODE_NORMAL
        val vibrateOk = result.vibrate && ringerMode != AudioManager.RINGER_MODE_SILENT
        if (soundOk) playSound(ctx, NotifSound.fromWire(result.soundId))
        if (vibrateOk) vibrateOnce(ctx)
        if (soundOk || vibrateOk) lastSoundAt = System.currentTimeMillis()
    }

    /**
     * 提示音选择页用：点一下即选中并试听一次（§2.4）。**同样遵循设备静音开关**
     * （`notif_sound_footer`「试听遵循设备的静音设置」），不占用/推进 [lastSoundAt] 节流状态——
     * 试听不是一次真实提醒。
     */
    fun preview(sound: NotifSound) {
        if (sound == NotifSound.NONE) return
        val ctx = appContext ?: return
        if (ringerMode(ctx) != AudioManager.RINGER_MODE_NORMAL) return
        playSound(ctx, sound)
    }

    /** 离开提示音选择页时调用：停掉可能还在响的试听（§2.4「返回时停止试听」）。 */
    fun stopPreview() {
        pool?.stop(lastStreamId)
    }

    fun hasVibrator(context: Context): Boolean = vibrator(context)?.hasVibrator() == true

    private fun playSound(context: Context, sound: NotifSound) {
        val p = pool ?: return
        val streamId = streamIdBySound[sound] ?: return
        lastStreamId = p.play(streamId, 1f, 1f, 1, 0, 1f)
    }

    private fun vibrateOnce(context: Context) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun ringerMode(context: Context): Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode
            ?: AudioManager.RINGER_MODE_NORMAL

    private fun soundResources(): Map<NotifSound, Int> = mapOf(
        NotifSound.DEFAULT to R.raw.notif_default,
        NotifSound.CHORD to R.raw.notif_chord,
        NotifSound.CHIME to R.raw.notif_chime,
        NotifSound.RISE to R.raw.notif_rise,
        NotifSound.DROP to R.raw.notif_drop,
    )
}
