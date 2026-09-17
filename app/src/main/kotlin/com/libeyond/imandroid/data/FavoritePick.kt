package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.Favorite

/**
 * 「从收藏发送」（聊天页附件面板 ▸ 收藏）的选择判据，逐条对齐 iOS `IMFavoritesViewController` 的 pick 模式
 * 与 im-web `FavoritesModal` 的 `mode="pick"`：
 *
 * - **常驻多选、勾选框独立触发**：点行 / 点格仍是打开预览（查看器、文件、链接……），选中只走勾选框。
 *   iOS 曾把 pick 模式下的点行复用成"切换选中"，用户看不到内容就得盲发，后来改掉了——本端直接照改后的做；
 * - 最多 [MAX] 条，超了吐司拒绝；**取消勾选永远允许**（选满了还拒取消，用户就卡死了）；
 * - 发送顺序 = **收藏列表里的顺序**，不是勾选先后（iOS `handlePickSend` 按 `_allItems` 过滤，收端出现顺序与收藏时间线一致）。
 *
 * 发出去走的是与收藏页「转发」同一条路（[Favorites.toMessageEntity] → `MessageService.forward`），
 * 媒体元数据与「转发自」口径因此只有一份。
 */
object FavoritePick {

    /** 一次最多选几条（iOS `kIMFavoritesPickMaxSelection` / Web `FAV_PICK_MAX`，三端都是 9）。 */
    const val MAX = 9

    /** 切换一条的勾选；到上限还要再勾返回 null（调用方吐 [limitText]）。 */
    fun toggle(selected: Set<Long>, id: Long, max: Int = MAX): Set<Long>? {
        if (id in selected) return selected - id
        if (selected.size >= max) return null
        return selected + id
    }

    fun limitText(max: Int = MAX): String = "最多选择 $max 项"

    /** 底栏按钮：没选时是灰着的「发送」，选了是「发送 (N)」（iOS `updatePickSendButton`）。 */
    fun sendLabel(n: Int): String = if (n > 0) "发送 ($n)" else "发送"

    /** 要发的收藏，按列表顺序；内容为空的剔掉（iOS `sendPickedFavorite:` 同样跳过空 content）。 */
    fun picked(items: List<Favorite>, selected: Set<Long>): List<Favorite> =
        items.filter { it.id in selected && it.content.isNotBlank() }

    /**
     * 发完的回执。iOS 是「已发送」/「已发送 N 条」；本端另外**跳过失效媒体**（服务端清理过的文件发出去
     * 对端必 404，收藏页「转发」、聊天页转发都拦着它），所以要把跳过了几条如实说出来。
     */
    fun sentText(sent: Int, expiredSkipped: Int): String {
        if (sent <= 0) return if (expiredSkipped > 0) "所选内容已失效，无法发送" else ""
        val base = if (sent == 1) "已发送" else "已发送 $sent 条"
        return if (expiredSkipped > 0) "$base（$expiredSkipped 条已失效未发送）" else base
    }
}
