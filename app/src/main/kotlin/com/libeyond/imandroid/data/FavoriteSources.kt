package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.Favorite

/** 收藏页「以聊天模式查看」的一组：同一个来源会话下的全部收藏。 */
data class SourceGroup(val key: String, val items: List<Favorite>) {
    /** 最近收藏的一条（列表行的预览、排序都以它为准）。 */
    val latest: Favorite get() = items.maxBy { it.createdAt }
}

/**
 * 按来源会话分组（对齐 iOS `IMFavoritesViewController` 的 `groupKeyOf:` / `rebuildGroups`）。
 * 判据：`source_conv_id` 为空、或原发送者是我自己 → 归入「我」这一桶；其余按会话 id；
 * 组按最近一条收藏时间倒序。
 */
object FavoriteSources {
    /** 「我」这一桶的哨兵键（不会与真实会话 id 撞）。 */
    const val ME = "__im_fav_me__"

    fun keyOf(f: Favorite, myUid: String): String =
        if (f.sourceConvId.isEmpty() || (myUid.isNotEmpty() && f.sourceFrom == myUid)) ME else f.sourceConvId

    fun group(items: List<Favorite>, myUid: String): List<SourceGroup> =
        items.groupBy { keyOf(it, myUid) }.map { (k, v) -> SourceGroup(k, v) }.sortedByDescending { it.latest.createdAt }

    /**
     * 来源显示名：我 →「我」；会话已知 → 备注 > 群名/昵称；解析不出 →「未知」
     * （iOS 这里落原始会话 id，本端约定界面不露内部 id，见 [DisplayName]）。
     */
    fun nameOf(key: String, conv: ConversationEntity?): String = when {
        key == ME -> Str.s(R.string.common_me)
        conv == null -> Str.s(R.string.fav_source_unknown)
        else -> conv.peerRemark.takeIf { !conv.isGroup && it.isNotBlank() } ?: conv.title.ifBlank { Str.s(R.string.fav_source_unknown) }
    }

    /** 来源搜索：只匹配来源名，忽略大小写；空串不过滤。 */
    fun filter(groups: List<SourceGroup>, query: String, nameOf: (String) -> String): List<SourceGroup> {
        val q = query.trim()
        return if (q.isEmpty()) groups else groups.filter { nameOf(it.key).contains(q, ignoreCase = true) }
    }

    /** 模式持久化值（iOS `im.favorites.viewMode`：0 消息 / 1 聊天，其余回落 0）。 */
    fun isChatMode(stored: Int): Boolean = stored == 1
}
