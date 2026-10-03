package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.UserCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 一次 `POST /users/batch` 的结果：查到的卡片 + 服务端说「不存在」的 uid（负缓存用，**不是错误**）。 */
data class ProfileBatch(val users: List<UserCard>, val missing: List<String>)

/**
 * uid → 头像/显名 的**全局解析器**（对齐 iOS `IMUserProfileCache`；`POST /users/batch`）。
 *
 * 成员表给不出身份时的兜底：超级群不下发成员表、退群者的历史消息也查不到——此前端上把成员表当身份字典用，
 * 于是群聊气泡的头像永远是首字母色块。内存缓存，**不落库**（身份会变，宁可重新解析）。
 *
 * 调用方在**渲染路径**上调 [request]，所以必须幂等且很便宜；否则会把服务端每分钟 60 次的限额烧穿、把自己锁在外面：
 * - **合批**：缺失的 uid 攒 [COALESCE_MS]（50ms）再发，每批最多 [MAX_BATCH]（100）个；
 * - **去重**：已在待发/在途/已缓存/还在负缓存期内的不再排；
 * - **负缓存**：`missing` 记 [MISSING_TTL_MS]（10 分钟），期满才可重试；
 * - **失败退避**：请求失败 [FAILURE_BACKOFF_MS]（5s）内不发新批、也不排新 uid（渲染路径会天天问，不然就是自己打自己）；
 * - 上限 [MAX_ENTRIES]（2000），先进先出淘汰（不是 LRU）；账号切换 [clear]。
 *
 * 取名链**止于「未命名用户」，绝不退到 uid**——结果还会随合并转发发出去（调用方负责）。
 * 群昵称不进这份全局缓存（A 群的昵称不能漏到 B 群）。
 */
class UserProfileCache(
    private val scope: CoroutineScope,
    private val fetch: suspend (List<String>) -> ProfileBatch,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cards = LinkedHashMap<String, UserCard>()
    private val missingUntil = HashMap<String, Long>()
    private val pending = LinkedHashSet<String>()
    private val inFlight = HashSet<String>()
    private var backoffUntil = 0L
    private var flushJob: Job? = null
    private var generation = 0
    private val lock = Any()

    private val _revision = MutableStateFlow(0)

    /** 每解析完一批 +1：Compose 订阅它就会重组，重新 [peek]。 */
    val revision: StateFlow<Int> = _revision

    /** 只读缓存，不发请求。 */
    fun peek(uid: String): UserCard? = synchronized(lock) { cards[uid] }

    /** 要这个人的资料：缓存命中没事；没命中就排队去补（合批、去重、退避，见类注释）。 */
    fun request(uid: String) {
        if (uid.isEmpty()) return
        // 已在 pending 也不提前返回：失败退避后 pending 里还留着人，没人重新起 flush 就永远卡在首字母色块
        synchronized(lock) {
            if (uid in cards || uid in inFlight) return
            val t = now()
            if ((missingUntil[uid] ?: 0L) > t) return
            if (t < backoffUntil) return // 退避期不排：下次渲染还会再问
            pending.add(uid)
            if (flushJob == null) flushJob = scope.launch { delay(COALESCE_MS); flush() }
        }
    }

    /** 喂已知数据（群成员表 / 好友 / 搜索结果）：总是覆盖。**只喂全局昵称**，别喂群昵称。 */
    fun ingest(list: List<UserCard>) {
        if (list.isEmpty()) return
        synchronized(lock) { list.forEach { put(it) } }
        _revision.value++
    }

    /** 账号切换。 */
    fun clear() {
        synchronized(lock) {
            generation++ // 在途那批回来时作废，别把 A 账号的资料写进 B 账号的缓存
            flushJob?.cancel(); flushJob = null
            cards.clear(); missingUntil.clear(); pending.clear(); inFlight.clear(); backoffUntil = 0L
        }
        _revision.value++
    }

    private suspend fun flush() {
        val batch: List<String>
        val gen: Int
        synchronized(lock) {
            gen = generation
            flushJob = null
            batch = pending.take(MAX_BATCH)
            pending.removeAll(batch.toSet())
            inFlight.addAll(batch)
        }
        if (batch.isEmpty()) return
        val result = runCatching { fetch(batch) }
        synchronized(lock) {
            if (gen != generation) return // clear() 之后回来的：丢弃
            inFlight.removeAll(batch.toSet())
            val r = result.getOrNull()
            if (r == null) {
                // 失败：不记负缓存（不是「查无此人」），进退避期；排队的留着，退避过后下一次渲染会再触发
                backoffUntil = now() + FAILURE_BACKOFF_MS
            } else {
                r.users.forEach { put(it) }
                r.missing.forEach { missingUntil[it] = now() + MISSING_TTL_MS }
            }
        }
        _revision.value++
        // 一批 100 个装不下时，剩下的接着发（同样合批窗口）
        val more = synchronized(lock) { pending.isNotEmpty() && flushJob == null && now() >= backoffUntil }
        if (more) {
            synchronized(lock) { if (flushJob == null) flushJob = scope.launch { delay(COALESCE_MS); flush() } }
        }
    }

    private fun put(c: UserCard) {
        if (c.userId.isEmpty()) return
        cards.remove(c.userId) // 重新插入 = 排到队尾
        cards[c.userId] = c
        while (cards.size > MAX_ENTRIES) cards.remove(cards.keys.first())
    }

    companion object {
        const val COALESCE_MS = 50L
        const val MAX_BATCH = 100
        const val MISSING_TTL_MS = 600_000L
        const val FAILURE_BACKOFF_MS = 5_000L
        const val MAX_ENTRIES = 2000
    }
}
