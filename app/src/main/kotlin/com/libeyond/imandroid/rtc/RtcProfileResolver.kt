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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 IM 的名字与头像注入通话界面（im-rtc 的 [IMProfileResolver]）。
 *
 * **im-rtc 只认 uid**：不注入的话对方显示成 10 位内部 ID。这里按 uid 取用户名片
 * （`GET /api/v1/users/{id}`，本机显示名 = 备注 → 昵称 → @句柄 → 「未命名用户」，末级绝不是 uid），
 * 取回来后通知 Kit 重画（[onResolved] → `IMCallKit.reloadProfiles`）。
 *
 * - Kit 同步来问、名字要走网络：**未缓存时先答 null（Kit 退化成显示 uid），后台取，取到再重画**；
 * - 头像取的是名片里的 `avatar_url`，经 Coil 拉成 [Drawable] 缓存（问一次拿一份新副本，各个 View 不共用同一个实例）；
 * - 备注只在**本机渲染**里出现，不会发给任何人（通话界面不是发出去的内容）。
 */
class RtcProfileResolver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val lookup: suspend (String) -> UserCard,
    private val absolute: (String) -> String,
    private val onResolved: (List<String>) -> Unit,
    private val book: RtcProfileBook = RtcProfileBook(),
    private val now: () -> Long = System::currentTimeMillis,
) : IMProfileResolver {

    private val log = IMLog.tag("IM.Rtc")
    private val avatars = ConcurrentHashMap<String, Drawable>()

    override fun displayName(uid: String): String? {
        prefetch(listOf(uid))
        return book.name(uid)
    }

    override fun avatar(uid: String): Drawable? = avatars[uid]?.constantState?.newDrawable()?.mutate()

    /** 提前取（拨号时对方 uid 已知），别等界面画出来才发现只有 uid。 */
    fun prefetch(uids: List<String>) {
        uids.forEach { uid ->
            if (!book.claim(uid, now())) return@forEach
            scope.launch {
                runCatching { lookup(uid) }
                    .onSuccess { card ->
                        val avatarChanged = book.avatarUrl(uid) != card.avatarUrl || !avatars.containsKey(uid)
                        book.put(uid, card.displayName, card.avatarUrl, now())
                        if (avatarChanged) loadAvatar(uid, card.avatarUrl)
                        onResolved(listOf(uid))
                    }
                    .onFailure {
                        book.fail(uid, now())
                        log.w("rtc_profile_failed", "err" to it.javaClass.simpleName)
                    }
            }
        }
    }

    private fun loadAvatar(uid: String, avatarUrl: String) {
        val url = absolute(avatarUrl)
        if (url.isBlank()) {
            avatars.remove(uid)
            return
        }
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .target(onSuccess = { drawable ->
                avatars[uid] = drawable
                log.d("rtc_avatar_loaded", "uid" to uid)
                onResolved(listOf(uid))
            }, onError = { _ -> log.w("rtc_avatar_failed", "uid" to uid, "url" to url) })
            .build()
        Coil.imageLoader(context).enqueue(request)
    }

    companion object {
        /** 接到 IM 客户端上：名片走 `client.contacts.card`，头像地址按当前服务器补全，取到后让 Kit 重画。 */
        fun forClient(context: Context, client: IMClient) = RtcProfileResolver(
            context = context.applicationContext,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            lookup = { client.contacts.card(it) },
            absolute = { MediaUrl.absolute(it, client.host, BuildConfig.USE_TLS) },
            onResolved = { IMCallKit.reloadProfiles(it) },
        )
    }
}
