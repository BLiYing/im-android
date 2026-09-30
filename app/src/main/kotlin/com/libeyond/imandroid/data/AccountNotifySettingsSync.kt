package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** `GET/PUT /api/v1/notify-settings` 的应答形状（`{version,exists,settings}`，PROTOCOL §6.13）。 */
data class NotifySettingsResponse(val version: Long, val exists: Boolean, val fields: AccountNotifyFields)

/** 一份带服务端版本号 + 本地脏标记的账号级通知设置。`dirty` = 上一次 PUT 没存上，还欠服务端一次。 */
data class AccountNotifyState(val version: Long, val fields: AccountNotifyFields, val dirty: Boolean)

/** [AccountNotifySettingsSync.decide] 的结果——GET 应答该怎么处理。 */
sealed interface NotifySyncDecision {
    /** 服务端 `exists=false`：还没有这份设置，把本地现值 PUT 上去（一次性迁移）。 */
    data object Migrate : NotifySyncDecision

    /** `exists=true` 且版本可采纳：覆盖本地为服务端值。 */
    data class Adopt(val version: Long, val fields: AccountNotifyFields) : NotifySyncDecision

    /** 版本比已采纳的旧（乱序应答）：忽略。 */
    data object Stale : NotifySyncDecision
}

/** 冷启动 / WS 重连触发时该做什么。 */
sealed interface NotifyTrigger {
    /** 上一次本地编辑的 PUT 失败过、还没补上：先重试 PUT，**不做 GET**——GET 会用旧服务端值把刚改的本地值冲掉。 */
    data object RetryPut : NotifyTrigger

    /** 没有欠账：按正常流程 GET 一次（决定迁移还是覆盖）。 */
    data object Refresh : NotifyTrigger
}

/**
 * 账号级通知设置什么时候采纳服务端值、什么时候该迁移、什么时候该补 PUT——**纯函数**，单测钉住。
 *
 * 与自动下载策略（[DownloadSettingsSync]）同一版本号纪律：回来的版本不小于已采纳的才用，
 * 推送帧版本严格更新才重拉。多出的一层是「迁移」：账号级设置是从「每台设备本地」搬过来的，
 * 服务端第一次见到这个账号时 `exists=false`，这时候不能覆盖本地——要反过来把本地现值传上去。
 */
object AccountNotifySettingsSync {
    const val UNKNOWN = -1L

    fun shouldApply(applied: Long, incoming: Long): Boolean = incoming >= applied

    fun shouldRefetch(applied: Long, pushed: Long): Boolean = pushed > applied

    /** GET 应答 → 该迁移、该覆盖、还是该丢弃（过期）。 */
    fun decide(applied: Long, response: NotifySettingsResponse): NotifySyncDecision = when {
        !response.exists -> NotifySyncDecision.Migrate
        !shouldApply(applied, response.version) -> NotifySyncDecision.Stale
        else -> NotifySyncDecision.Adopt(response.version, response.fields)
    }

    /** 冷启动 / 重连触发：脏了就先补 PUT，否则走正常 GET。 */
    fun onTrigger(dirty: Boolean): NotifyTrigger = if (dirty) NotifyTrigger.RetryPut else NotifyTrigger.Refresh
}

/**
 * 账号级通知设置（私聊/群聊 `{enabled,preview,sound}` + `badge.include_muted`）的**唯一持有者**
 * （对齐 [DownloadSettingsStore]，M5）。本地存储与判定层仍然只读 [NotificationSettingsStore]——
 * 本类只负责把它的这三项字段与服务端对齐，`inApp`/`desktop` 两项不碰。
 *
 * 三条驱动路径（[IMClient] 接线）：
 * 1. 登录 / 冷启动、WS 真正连上：调 [start]——脏了先补 PUT，没脏就 GET 一次跑 [AccountNotifySettingsSync.decide]；
 * 2. 收到 `notify_settings_update`：调 [onPushed]，版本更新才重拉；
 * 3. 用户在设置页改私聊/群聊/角标三项：调 [save]——**立刻本地生效**，PUT 失败**不回滚**（与自动下载策略不同：
 *    宁可本地领先服务端也不把用户刚点的开关弹回去），只标记 dirty，下次 [start] 时补 PUT。
 *
 * 接口以函数注入（`fetch`/`put`/`localFields`/`applyLocal`），JVM 单测不需要真的 HTTP 客户端或 SharedPreferences。
 */
class AccountNotifySettingsStore(
    private val fetch: suspend () -> NotifySettingsResponse,
    private val put: suspend (AccountNotifyFields) -> NotifySettingsResponse,
    /** 读 [NotificationSettingsStore] 当前的账号级三项（真正的本地现值,来源见类注释）。 */
    private val localFields: () -> AccountNotifyFields,
    /** 把服务端值（或迁移前的本地值，PUT 应答已规整过）写回 [NotificationSettingsStore]。 */
    private val applyLocal: (AccountNotifyFields) -> Unit,
) {
    private val log = IMLog.tag("IM.Notif")

    private val _state = MutableStateFlow(initial())

    /** 设置页可选订阅这个看同步态（当前只在测试里断言，UI 目前不需要"同步中"指示）。 */
    val state: StateFlow<AccountNotifyState> = _state.asStateFlow()

    /** 账号代次：[forget] 一次 +1。发请求前记下，应答回来时代次变了就丢弃（上一个账号的）。 */
    private val generation = AtomicInteger(0)

    /** 冷启动 / WS 连上时调用一次。 */
    suspend fun start(reason: String) {
        val gen = generation.get()
        when (AccountNotifySettingsSync.onTrigger(_state.value.dirty)) {
            NotifyTrigger.RetryPut -> pushLocal(gen, "dirty_retry")
            NotifyTrigger.Refresh -> refresh(reason)
        }
    }

    /** 收到 `notify_settings_update`：版本比已采纳的新才重拉。 */
    suspend fun onPushed(version: Long) {
        if (!AccountNotifySettingsSync.shouldRefetch(_state.value.version, version)) return
        refresh("notify_settings_update")
    }

    /**
     * 用户改了私聊/群聊/角标三项之一：立刻本地生效，再 PUT。
     * @return 是否存上（失败时调用方按需提示；本地值已经生效，不必回滚）。
     */
    suspend fun save(next: AccountNotifyFields): Boolean {
        val gen = generation.get()
        if (_state.value.fields == next) return true
        applyLocal(next)
        _state.update { it.copy(fields = next) }
        return attempt { put(next) }.fold(
            onSuccess = { resp -> adopt(gen, resp.version, resp.fields, "save"); true },
            onFailure = {
                log.w("notify_settings_save_failed", "error" to it.javaClass.simpleName)
                markDirty(gen)
                false
            },
        )
    }

    /** 退出登录 / 被踢：回到"还没同步过"，本地三项也退回出厂默认——否则下一个账号在这台设备上会先看见上一个人的通知设置。 */
    fun forget() {
        generation.incrementAndGet()
        _state.value = initial()
        applyLocal(AccountNotifyFields())
    }

    private suspend fun refresh(reason: String) {
        val gen = generation.get()
        attempt { fetch() }.fold(
            onSuccess = { resp ->
                when (val decision = AccountNotifySettingsSync.decide(_state.value.version, resp)) {
                    is NotifySyncDecision.Adopt -> adopt(gen, decision.version, decision.fields, reason)
                    NotifySyncDecision.Migrate -> pushLocal(gen, "migrate")
                    NotifySyncDecision.Stale ->
                        log.i("notify_settings_stale_ignored", "reason" to reason, "version" to resp.version)
                }
            },
            onFailure = { log.w("notify_settings_fetch_failed", "reason" to reason, "error" to it.javaClass.simpleName) },
        )
    }

    /** 迁移（首次遇到 `exists=false`）与「脏了补 PUT」共用同一条路：都是把本地现值传上去。 */
    private suspend fun pushLocal(gen: Int, reason: String) {
        attempt { put(localFields()) }.fold(
            onSuccess = { resp -> adopt(gen, resp.version, resp.fields, reason) },
            onFailure = {
                log.w("notify_settings_push_local_failed", "reason" to reason, "error" to it.javaClass.simpleName)
                markDirty(gen)
            },
        )
    }

    private fun markDirty(gen: Int) {
        _state.update { cur -> if (gen == generation.get()) cur.copy(dirty = true) else cur }
    }

    private fun adopt(gen: Int, version: Long, fields: AccountNotifyFields, reason: String) {
        var applied = false
        _state.update { cur ->
            applied = gen == generation.get() && AccountNotifySettingsSync.shouldApply(cur.version, version)
            if (applied) AccountNotifyState(version, fields, dirty = false) else cur
        }
        if (!applied) {
            log.i("notify_settings_stale_ignored", "reason" to reason, "version" to version)
            return
        }
        applyLocal(fields)
        log.i("notify_settings_applied", "reason" to reason, "version" to version)
    }

    private fun initial() = AccountNotifyState(AccountNotifySettingsSync.UNKNOWN, AccountNotifyFields(), dirty = false)

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
