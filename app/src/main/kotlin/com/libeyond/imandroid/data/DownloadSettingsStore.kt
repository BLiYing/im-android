package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** 一份带服务端版本号的策略。`version` 为 [DownloadSettingsSync.UNKNOWN] = 还没从服务端拿到过。 */
data class VersionedSettings(val version: Long, val settings: DownloadSettings)

/**
 * 什么时候采纳服务端回来的策略、什么时候为推送帧重拉。**纯函数**，单测钉住。
 *
 * 服务端每次保存/重置都把版本 +1（`internal/downloadsettings`），所以版本号就是先后：
 * - 回来的版本**不小于**已采纳的才用。小于 = 过期应答（两次保存的应答乱序到达、或保存之前发出的
 *   GET 后到），用了就是把刚改的值悄悄改回去。**等于也要用**：保存失败后重拉拿到的正是同一版本，
 *   那就是要回滚到的值。
 * - 推送帧的版本**大于**已采纳的才重拉（PROTOCOL §6.9「据 version 去重」）：本机保存成功后
 *   服务端同样会给本机推一帧，那个版本在 PUT 应答里已经采纳过了。
 */
object DownloadSettingsSync {
    const val UNKNOWN = -1L

    fun shouldApply(applied: Long, incoming: Long): Boolean = incoming >= applied

    fun shouldRefetch(applied: Long, pushed: Long): Boolean = pushed > applied
}

/**
 * 账号级自动下载策略的**唯一持有者**（对齐 iOS `IMDownloadSettingsStore`）。
 *
 * 门控每渲染一格媒体读一次 [current]，设置页订阅 [state]——两处必须是同一份，
 * 各拉各的话，设置页里刚改的值要等下一次重拉才对门控生效。
 *
 * 比 iOS 多做的三件（行为兼容，都是 iOS 没防的时序）：
 * 1. 应答按版本号采纳（[DownloadSettingsSync]），乱序到达的旧应答不会把新值盖回去；
 * 2. 保存失败**先在本地退回改之前的值**再重拉——离线时重拉也会失败，只靠重拉的话，
 *    界面会停在一个根本没存上的值，直到下次连上才被悄悄改回；
 * 3. 切账号时 [forget]，并让上一个账号在途的应答作废（[generation]）。
 *
 * 接口以函数注入而不是直接持有 `DownloadSettingsApi`：那样才能在 JVM 单测里编排乱序与失败。
 */
class DownloadSettingsStore(
    private val fetch: suspend () -> Pair<Long, DownloadSettings>,
    private val put: suspend (DownloadSettings) -> Pair<Long, DownloadSettings>,
    private val reset: suspend () -> Pair<Long, DownloadSettings>,
) {
    private val log = IMLog.tag("IM.Download")

    private val _state = MutableStateFlow(initial())

    /** 设置页订阅这个。 */
    val state: StateFlow<VersionedSettings> = _state.asStateFlow()

    /** 门控读这个。拉到之前是出厂默认——**不是全关**，全关会让所有图片都要手点。 */
    val current: DownloadSettings get() = _state.value.settings

    /** 账号代次：[forget] 一次 +1。发请求前记下，应答回来时代次变了就丢弃（那是上一个账号的）。 */
    private val generation = AtomicInteger(0)

    /** 拉最新。失败保留当前值（出厂默认或上次拉到的）。 */
    suspend fun refresh(reason: String) {
        val gen = generation.get()
        attempt { fetch() }
            .onSuccess { (version, s) -> adopt(gen, version, s, reason) }
            .onFailure { log.w("download_settings_fetch_failed", "reason" to reason, "error" to it.javaClass.simpleName) }
    }

    /** 收到 `capabilities_update`：版本比已采纳的新才重拉。 */
    suspend fun onPushed(version: Long) {
        if (!DownloadSettingsSync.shouldRefetch(_state.value.version, version)) return
        refresh("capabilities_update")
    }

    /**
     * 保存（服务端整份替换）。乐观：先让界面与门控立刻用上新值，再 PUT。
     * @return 是否存上。失败时已退回改之前的值，调用方只需提示。
     */
    suspend fun save(next: DownloadSettings): Boolean {
        val gen = generation.get()
        val before = _state.value.settings
        if (before == next) return true
        _state.update { it.copy(settings = next) }
        return attempt { put(next) }.fold(
            onSuccess = { (version, saved) ->
                adopt(gen, version, saved, "save")
                true
            },
            onFailure = {
                log.w("download_settings_save_failed", "error" to it.javaClass.simpleName, "rollback" to true)
                // 只在界面上仍是**这一次**的乐观值时才退回：期间又改过一次的话，退回会把那次也抹掉
                _state.update { cur ->
                    if (gen == generation.get() && cur.settings == next) cur.copy(settings = before) else cur
                }
                refresh("save_failed")
                false
            },
        )
    }

    /** 恢复出厂默认。@return 是否成功。 */
    suspend fun resetToDefaults(): Boolean {
        val gen = generation.get()
        return attempt { reset() }.fold(
            onSuccess = { (version, s) ->
                adopt(gen, version, s, "reset")
                true
            },
            onFailure = {
                log.w("download_settings_reset_failed", "error" to it.javaClass.simpleName)
                refresh("reset_failed")
                false
            },
        )
    }

    /** 退出登录 / 被踢：回到出厂默认，并让在途应答作废。 */
    fun forget() {
        generation.incrementAndGet()
        _state.value = initial()
    }

    private fun adopt(gen: Int, version: Long, s: DownloadSettings, reason: String) {
        var applied = false
        _state.update { cur ->
            applied = gen == generation.get() && DownloadSettingsSync.shouldApply(cur.version, version)
            if (applied) VersionedSettings(version, s) else cur
        }
        if (!applied) {
            log.i("download_settings_stale_ignored", "reason" to reason, "version" to version, "applied" to _state.value.version)
            return
        }
        // 策略是所有门控判定的输入：不记就答不上「我明明设了 Wi-Fi 高档，为什么还在门控」。
        // version 与服务端 download_settings_saved 对账，即可确认多端同步到没到这一端（iOS 同一行日志）。
        log.i(
            "download_settings_applied",
            "reason" to reason,
            "version" to version,
            "wifi_enabled" to s.wifi.enabled,
            "cellular_enabled" to s.cellular.enabled,
            "wifi_video_max" to s.wifi.video.maxBytes,
            "wifi_file_max" to s.wifi.file.maxBytes,
            "cellular_video_max" to s.cellular.video.maxBytes,
            "cellular_file_max" to s.cellular.file.maxBytes,
        )
    }

    private fun initial() = VersionedSettings(DownloadSettingsSync.UNKNOWN, DownloadPolicy.defaults())

    /** `runCatching`，但不吞协程取消（CODING_STYLE §5）。 */
    private suspend inline fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
