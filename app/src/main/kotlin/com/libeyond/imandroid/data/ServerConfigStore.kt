package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ServerConfig
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `GET /server-config` 里后台「系统设置」S2 的六项，**归一后**的值（PROTOCOL `GET /api/v1/server-config`，
 * SYSTEM_SETTINGS_DESIGN §3.1）。端上一律读这里，不读原始 [ServerConfig] 的可空字段。
 *
 * 两个时间窗 **0 = 不限**；其余四项没有「不限」，非正值按缺失处理。
 *
 * S3a 上传策略三项（聊天媒体：图片/视频/文件/语音；头像不受约束）：
 * - [uploadEnabled]：只有**明确的 false** 才算关（缺失 = 老服务端 = 开）；
 * - [uploadMaxFileBytes]：图片/视频/文件的单文件上限，缺失 / ≤0 → 2 GiB（≤0 不是「不限」也不是「全拒」）。
 *   语音另有服务端固定 16MB 上限，不受它约束；
 * - [uploadBlockedExtensions]：小写、带前导点（".exe"），去重；不以点开头 / 只有一个点的条目丢掉。
 * 判据见 [UploadPrecheck]。
 */
data class ServerLimits(
    val recallWindowSec: Long = DEFAULT_RECALL_WINDOW_SEC,
    val editWindowSec: Long = DEFAULT_EDIT_WINDOW_SEC,
    val forwardMaxTargets: Int = Forward.MAX_TARGETS,
    val mergeForwardMax: Int = Forward.MAX_SELECTION,
    val groupNameMaxLen: Int = GroupNameDefault.MAX_LENGTH,
    val groupAnnouncementMaxLen: Int = DEFAULT_ANNOUNCEMENT_MAX_LEN,
    val uploadEnabled: Boolean = true,
    val uploadMaxFileBytes: Long = DEFAULT_UPLOAD_MAX_FILE_BYTES,
    val uploadBlockedExtensions: List<String> = emptyList(),
) {
    companion object {
        /** 老服务端（不下发该字段）时的撤回窗口：2 分钟。**缺失 ≠ 0**，读成 0 就成了永远可撤回。 */
        const val DEFAULT_RECALL_WINDOW_SEC = 120L
        const val DEFAULT_EDIT_WINDOW_SEC = 0L
        const val DEFAULT_ANNOUNCEMENT_MAX_LEN = 500
        /** 老服务端 / 下发 ≤0 时的单文件上限：2 GiB（与 media-picker `MediaPick.DEFAULT_MAX_BYTES` 同值，有测试钉住）。 */
        const val DEFAULT_UPLOAD_MAX_FILE_BYTES = 2L shl 30

        /** 禁用扩展名归一：trim + 小写 + 去重，只收以 '.' 开头且不止一个点的条目。 */
        internal fun normalizeExtensions(raw: List<String>?): List<String> =
            raw.orEmpty().map { it.trim().lowercase() }.filter { it.length > 1 && it.startsWith(".") }.distinct()

        /**
         * 缺失 → 内置默认；时间窗 0 → 不限；有值 → 用值。合并转发再封顶在多选上限 100
         * （服务端范围本就 ≤100，这里防一手：多选栏最多只勾得出 100 条）。
         */
        fun of(cfg: ServerConfig?): ServerLimits {
            val d = ServerLimits()
            if (cfg == null) return d
            return ServerLimits(
                recallWindowSec = cfg.recallWindowSec?.takeIf { it >= 0 } ?: d.recallWindowSec,
                editWindowSec = cfg.editWindowSec?.takeIf { it >= 0 } ?: d.editWindowSec,
                forwardMaxTargets = cfg.forwardMaxTargets?.takeIf { it > 0 } ?: d.forwardMaxTargets,
                mergeForwardMax = (cfg.mergeForwardMax?.takeIf { it > 0 } ?: d.mergeForwardMax)
                    .coerceAtMost(Forward.MAX_SELECTION),
                groupNameMaxLen = cfg.groupNameMaxLen?.takeIf { it > 0 } ?: d.groupNameMaxLen,
                groupAnnouncementMaxLen = cfg.groupAnnouncementMaxLen?.takeIf { it > 0 } ?: d.groupAnnouncementMaxLen,
                uploadEnabled = cfg.uploadEnabled != false,
                uploadMaxFileBytes = cfg.uploadMaxFileBytes?.takeIf { it > 0 } ?: d.uploadMaxFileBytes,
                uploadBlockedExtensions = normalizeExtensions(cfg.uploadBlockedExtensions),
            )
        }

        /**
         * 发出后 [sentAtMs] 的消息此刻（[nowMs]）是否还在 [windowSec] 时间窗内。`windowSec <= 0` = 不限。
         * 撤回 / 编辑共用（菜单显隐只是先挡一道，服务端以自己的当前值为准）。
         */
        fun withinWindow(sentAtMs: Long, nowMs: Long, windowSec: Long): Boolean =
            windowSec <= 0 || nowMs - sentAtMs <= windowSec * 1000
    }
}

/**
 * 什么时候真的发请求（纯函数，单测钉住）。连上 / 回前台都会喊一声，弱网重连与频繁切前后台
 * （选图回来也算一次回前台）会喊得很密：
 * - **本代**已有一个在途 → 不再发（上一代——[ServerConfigStore.forget] 之前发出的——不算，见那里）；
 * - 距上次**成功**不足 [MIN_INTERVAL_MS]（5 秒，与 iOS 同）→ 不发。只为挡住「连上 + 回前台」这类同一时刻的
 *   重复触发，不挡正常的回前台；
 * - 失败不计入节流：失败就保留旧值，下一次触发立刻可以再试。
 */
object ServerConfigRefresh {
    const val MIN_INTERVAL_MS = 5_000L
    const val NEVER = Long.MIN_VALUE

    fun shouldFetch(inFlight: Boolean, lastSuccessAt: Long, now: Long): Boolean =
        !inFlight && (lastSuccessAt == NEVER || now - lastSuccessAt >= MIN_INTERVAL_MS)
}

/**
 * 部署级配置的**进程级唯一持有者**（写法同 [PowerSavingStore] 这类 object：页面 / 纯展示组件不必层层传 client）。
 *
 * 触发点（见 `sdk/IMClientServerConfig.kt`）：进主界面、**每次 WS 连上（含重连）**、App 回到前台。
 * 失败保留上一次的值；从没拿到过就是 [ServerLimits] 的内置默认。退出登录 / 被踢时 [forget]
 * （下一次可能连的是另一台服务器）。
 *
 * **代次（generation）与锁**：[forget] 把代次 +1。
 * - 「核对代次 → 写 config/limits → 写 lastSuccessAt」与 [forget] 在同一把锁里：forget 之后，上一代的应答
 *   **绝不写入任何状态**（否则旧服务器的值被写回，或 lastSuccessAt 被清后又写回、让新账号被节流着一直用默认）；
 * - 「在途」按代记（[inFlightGen]）：forget 之后新一代立刻可以发请求，不被还在飞的旧请求挡住；
 *   旧请求结束时只复位它自己那一代的标志。
 *
 * 原始 [config] 也留着：群上限 / 大群开关 / fcm 这些老字段的页面仍各自现拉（行为不变），
 * 新代码要用可直接读这里。
 */
object ServerConfigStore {
    private val log = IMLog.tag("IM.ServerConfig")

    private val _config = MutableStateFlow<ServerConfig?>(null)
    val config: StateFlow<ServerConfig?> = _config.asStateFlow()

    private val _limits = MutableStateFlow(ServerLimits())
    val limits: StateFlow<ServerLimits> = _limits.asStateFlow()

    /** 非 Composable 处（菜单判据、点击回调）读这个。 */
    val current: ServerLimits get() = _limits.value

    private val lock = Any()

    // 以下三项只在 [lock] 内读写
    private var generation = 0
    /** 在途请求所属的代次；null = 没有在途。 */
    private var inFlightGen: Int? = null
    private var lastSuccessAt = ServerConfigRefresh.NEVER

    /** 仅测试用：在锁内、核对完代次之后、写状态之前调一次，用来把 [forget] 插进这个窗口。 */
    @Volatile internal var beforeAdoptForTest: (() -> Unit)? = null

    /**
     * 按节流拉一次。@return 这次拉到并采纳的配置；被节流 / 失败 / 作废回 null。
     */
    suspend fun refresh(reason: String, now: Long = System.currentTimeMillis(), fetch: suspend () -> ServerConfig): ServerConfig? {
        val gen = synchronized(lock) {
            if (!ServerConfigRefresh.shouldFetch(inFlightGen == generation, lastSuccessAt, now)) return null
            inFlightGen = generation
            generation
        }
        try {
            val cfg = fetch()
            val changed = synchronized(lock) {
                if (gen != generation) return null
                beforeAdoptForTest?.invoke()
                val next = ServerLimits.of(cfg)
                val changed = next != _limits.value
                _config.value = cfg
                _limits.value = next
                lastSuccessAt = now
                changed
            }
            // 只在值变了时记：连上 / 回前台很频繁，每次都记是刷屏
            if (changed) logApplied(ServerLimits.of(cfg), reason)
            return cfg
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("server_config_fetch_failed", "reason" to reason, "error" to e.javaClass.simpleName)
            return null
        } finally {
            synchronized(lock) { if (inFlightGen == gen) inFlightGen = null }
        }
    }

    /**
     * 「请重拉一次」的入口（由 [com.libeyond.imandroid.sdk.startServerConfigSync] 注入，走同一套节流 / 在途去重）。
     * 用途：本地预检按缓存值拦下发送时，缓存可能是旧的（管理员关了上传又打开），不重拉的话要等下次重连 / 回前台才放行。
     */
    @Volatile var refresher: ((reason: String) -> Unit)? = null

    fun requestRefresh(reason: String) {
        refresher?.invoke(reason)
    }

    /** 退出登录 / 被踢：回到内置默认、清节流与在途标志，并让在途应答作废。 */
    fun forget() {
        synchronized(lock) {
            generation++
            inFlightGen = null
            lastSuccessAt = ServerConfigRefresh.NEVER
            _config.value = null
            _limits.value = ServerLimits()
        }
    }

    private fun logApplied(next: ServerLimits, reason: String) {
        log.i(
            "server_config_applied",
            "reason" to reason,
            "recall_window_sec" to next.recallWindowSec,
            "edit_window_sec" to next.editWindowSec,
            "forward_max_targets" to next.forwardMaxTargets,
            "merge_forward_max" to next.mergeForwardMax,
            "group_name_max_len" to next.groupNameMaxLen,
            "group_announcement_max_len" to next.groupAnnouncementMaxLen,
            "upload_enabled" to next.uploadEnabled,
            "upload_max_file_bytes" to next.uploadMaxFileBytes,
            "upload_blocked_extensions" to next.uploadBlockedExtensions.joinToString(","),
        )
    }
}
