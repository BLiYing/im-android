package com.libeyond.imandroid.rtc

import android.content.Context
import android.graphics.drawable.Drawable
import coil.Coil
import coil.request.ImageRequest
import com.imrtc.uikit.IMCallKit
import com.imrtc.uikit.IMProfileResolver
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 IM 的名字与头像注入通话界面（im-rtc 的 [IMProfileResolver]）。
 *
 * **通话界面显示的就是 IM 自己的数据**（[RtcProfileSources]：会话行 / 当前群的成员表），
 * 每次 Kit 重画都重新读，IM 变了通话界面就跟着变；**不自己刷新、不在拨号 / 来电时发请求**。
 * 只有 IM 本地一个名字都没有的 uid，才取一次名片兜底（[book]），取回后通知 Kit 补画这一格。
 * 备注只在本机渲染里出现，不会发给任何人。
 */
class RtcProfileResolver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sources: RtcProfileSources,
    private val peerRows: Flow<List<RtcProfileSources.PeerRow>>,
    private val lookup: suspend (String) -> UserCard,
    private val absolute: (String) -> String,
    private val onResolved: (List<String>) -> Unit,
    private val book: RtcProfileBook = RtcProfileBook(),
    private val now: () -> Long = System::currentTimeMillis,
) : IMProfileResolver {

    private val log = IMLog.tag("IM.Rtc")
    private val drawables = ConcurrentHashMap<String, Drawable>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private var feed: Job? = null

    /** 当前这通是群通话时的群号（群成员表按它取）；单聊为空串。由 [RtcCall] 在拨号 / 来电时设。 */
    @Volatile var groupId: String = ""

    /** 开始跟着 IM 的会话数据走。幂等。 */
    fun open() {
        if (feed != null) return
        feed = scope.launch { peerRows.collect { sources.setPeers(it) } }
    }

    fun close() {
        feed?.cancel()
        feed = null
    }

    /** 群资料页加载成员时顺手留一份（不增加请求）。 */
    fun putMembers(convId: String, rows: List<RtcProfileSources.MemberRow>) = sources.putMembers(convId, rows)

    override fun displayName(uid: String): String? {
        sources.name(uid, groupId)?.let { return it }
        // IM 本地一个名字都没有：兜底取一次，取回后补画；取回之前先答 null（Kit 显示 uid）。
        fetchFallback(uid)
        return book.name(uid)
    }

    override fun avatar(uid: String): Drawable? {
        val raw = sources.avatarUrl(uid, groupId) ?: book.avatarUrl(uid).takeIf { it.isNotBlank() } ?: return null
        val url = absolute(raw)
        if (url.isBlank()) return null
        drawables[url]?.let { return it.constantState?.newDrawable()?.mutate() }
        loadAvatar(uid, url)
        return null
    }

    private fun fetchFallback(uid: String) {
        if (!book.claim(uid, now())) return
        scope.launch {
            runCatching { lookup(uid) }
                .onSuccess { card ->
                    book.put(uid, card.displayName, card.avatarUrl)
                    onResolved(listOf(uid))
                }
                .onFailure {
                    book.fail(uid, now())
                    log.w("rtc_profile_failed", "err" to it.javaClass.simpleName)
                }
        }
    }

    private fun loadAvatar(uid: String, url: String) {
        if (!loading.add(url)) return
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .target(
                onSuccess = { drawable ->
                    drawables[url] = drawable
                    loading.remove(url)
                    log.d("rtc_avatar_loaded", "uid" to uid)
                    onResolved(listOf(uid))
                },
                onError = { _ ->
                    loading.remove(url)
                    log.w("rtc_avatar_failed", "uid" to uid, "url" to url)
                },
            )
            .build()
        Coil.imageLoader(context).enqueue(request)
    }

    companion object {
        /** 接到 IM 客户端上：会话行取自本机会话表，群成员由群资料页喂，兜底走 `client.contacts.card`。 */
        fun forClient(context: Context, client: IMClient): RtcProfileResolver {
            val owner = client.uid.orEmpty()
            return RtcProfileResolver(
                context = context.applicationContext,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                sources = RtcProfileSources(),
                peerRows = client.repo.observeConversations(owner).map { rows ->
                    rows.filter { !it.isGroup && it.peerUid.isNotEmpty() }
                        .map { RtcProfileSources.PeerRow(it.peerUid, it.title, it.avatarUrl, it.peerRemark) }
                },
                lookup = { client.contacts.card(it) },
                absolute = { MediaUrl.absolute(it, client.host, BuildConfig.USE_TLS) },
                onResolved = { IMCallKit.reloadProfiles(it) },
            )
        }
    }
}
