package com.libeyond.imandroid.sdk.session

import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerializationException

/** 一枚等待服务端吊销的续期凭据。[url] 是退出时那台服务器的地址，之后换了服务器也不会把凭据发给别处。 */
@Serializable
data class PendingLogoutEntry(val token: String, val url: String, val at: Long)

/**
 * 退出登录的服务端吊销：持久化待吊销的续期凭据，失败后重试，直到送达（LOGOUT_REVOKE_RETRY_DESIGN）。
 *
 * 退出时吊销请求若没成功，本地凭据已清，服务端会话和推送令牌却还在，这台设备再也没有办法自己补救。
 * 本端有 `FcmMessagingService` 的登出丢弃兜底，通知不会弹，但这条僵尸会话依然留在「已登录设备」里。
 *
 * 落 [SessionStore] 同一份私有 SP：在用的续期凭据也存在那里，这里的保护级别不比它低
 * （为什么不用 EncryptedSharedPreferences 见 [SessionStore] 类注释；Manifest 已关 allowBackup）。
 *
 * 对端：iOS `IMPendingLogoutQueue`。
 */
class PendingLogoutQueue(
    private val read: () -> String?,
    /** 必须同步落盘：退出后进程随时可能被杀。null = 删除。 */
    private val write: (String?) -> Unit,
    private val send: suspend (PendingLogoutEntry) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val log = IMLog.tag("IM.Session")
    private val stateLock = Mutex()
    /** 同一时间只跑一轮，发送期间不占 [stateLock]，新的退出不会被网络超时卡住。 */
    private val flushLock = Mutex()

    suspend fun pendingCount(): Int = stateLock.withLock { load().size }

    /** 退出登录时调：只落盘不发送，发送交给 [flush]。**必须在清本地会话之前调**。 */
    suspend fun enqueue(refreshToken: String, url: String) {
        if (refreshToken.isEmpty() || url.isEmpty()) return
        stateLock.withLock {
            val updated = load().filter { it.token != refreshToken } + PendingLogoutEntry(refreshToken, url, now())
            save(updated.takeLast(MAX_ENTRIES))
        }
    }

    /**
     * 把所有待吊销的凭据各发一次，返回是否还有值得稍后再试的。
     * 一条失败不中断后面的：不同条目可能属于不同服务器，这台连不上不代表那台也连不上。
     */
    suspend fun flush(): Boolean = flushLock.withLock {
        val cutoff = now() - MAX_AGE_MS
        val snapshot = stateLock.withLock {
            val current = load()
            val alive = current.filter { it.at >= cutoff }
            if (alive.size != current.size) save(alive)
            alive
        }
        var anyRetry = false
        for (entry in snapshot) {
            val retry = try {
                send(entry)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val shouldRetry = shouldRetry(e)
                log.w("pending_logout_failed", "code" to e.code, "retry" to shouldRetry)
                shouldRetry
            } catch (e: IllegalArgumentException) {
                // OkHttp 对非法 URL 直接抛这个（存储内容损坏）。这条永远发不出去，丢弃；
                // 不能往外抛，flush 跑在 scope.launch 里，未捕获会让 App 崩溃。
                log.w("pending_logout_bad_url", "err" to e.javaClass.simpleName)
                false
            }
            if (retry) {
                anyRetry = true
            } else {
                stateLock.withLock { save(load().filter { it.token != entry.token }) }
            }
        }
        anyRetry
    }

    private fun load(): List<PendingLogoutEntry> {
        val raw = read() ?: return emptyList()
        return try {
            ProtocolJson.decodeFromString(ListSerializer(PendingLogoutEntry.serializer()), raw)
        } catch (e: SerializationException) {
            log.w("pending_logout_corrupt", "err" to e.javaClass.simpleName)
            emptyList()
        } catch (e: IllegalArgumentException) {
            log.w("pending_logout_corrupt", "err" to e.javaClass.simpleName)
            emptyList()
        }
    }

    private fun save(entries: List<PendingLogoutEntry>) {
        write(
            if (entries.isEmpty()) null
            else ProtocolJson.encodeToString(ListSerializer(PendingLogoutEntry.serializer()), entries),
        )
    }

    internal companion object {
        /** 服务端续期凭据的绝对寿命是 180 天，会话届时已失效，这之后再存就只是白白留着一枚凭据。 */
        const val MAX_AGE_MS = 180L * 24 * 3600 * 1000
        /** 正常情况下最多一两条。上限防止反复退出又长期连不上时无限累积，超出丢最旧的。 */
        const val MAX_ENTRIES = 16

        /** 网络 / 非 JSON 响应 / 限流 / 服务端内部错误才值得再试；服务端明确拒绝（如参数错误）重试也不会好。 */
        fun shouldRetry(e: ApiException): Boolean =
            e.isTransport || e.code == ErrCode.RATE_LIMITED || e.code == ErrCode.INTERNAL
    }
}

/** 决定 [PendingLogoutQueue] 什么时候发：启动、网络恢复、回前台由调用方触发 [flush]，失败后自己定时再试。 */
class PendingLogoutRunner(
    private val scope: CoroutineScope,
    private val queue: PendingLogoutQueue,
    private val retryIntervalMs: Long = 60_000L,
) {
    @Volatile private var retryJob: Job? = null

    fun flush() {
        scope.launch {
            if (queue.flush()) scheduleRetry()
        }
    }

    /** 兜底：一直停在登录页时既不回前台也没有网络跃迁（服务端临时故障就是这样），靠定时重试。 */
    fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            if (queue.pendingCount() == 0) return@launch
            delay(retryIntervalMs)
            flush()
        }
    }
}
