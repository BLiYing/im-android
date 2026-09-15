package com.libeyond.imandroid.data

/** 一次成功的会话列表拉取：哪个账号、服务端给了几条（`MessageService.listedConversations`）。 */
data class ListedConversations(val owner: String, val count: Int)

/**
 * 会话列表此刻该画什么：列表 / 空态 / 什么都不画。
 *
 * **「还没有会话」是结论，不是默认值**。此前列表拿 `emptyList()` 当本地库的初值，
 * 于是每次冷启动、每次登录都先闪一下空态，库回第一份数据才换成列表（2026-09-15 用户报）。
 * 结论要两条证据都在才下：本地库确实回了空，**且**服务端也确实说过「没有」。
 *
 * 为什么服务端说「有 N 条」、本地却还空时仍不画空态：会话列表是先写库、库再异步通知 UI 的
 * （Room 的失效通知在另一个线程），拉取完成与列表出现之间隔着一帧到几十毫秒——
 * 只看「拉过了」就会在新装包首次登录时正好闪进这个缝里。
 *
 * iOS 对端是 `IMConversationListViewController` 的 `emptyLabel`（首登前只信本地缓存、拉成功才下结论）；
 * Web 在列表拉完之后才进主界面，本来就没有这一闪。
 */
enum class ConversationListPhase {
    /** 还不知道：不画空态，也不画列表。 */
    Loading,
    Empty,
    List;

    companion object {
        /**
         * @param localCount 本地库回来的会话数；null = 库还没回第一份。
         * @param serverCount 本账号本次进程里最近一次**成功**拉到的会话数；null = 还没拉成过。
         */
        fun of(localCount: Int?, serverCount: Int?): ConversationListPhase = when {
            localCount == null -> Loading
            localCount > 0 -> List
            serverCount == null -> Loading
            serverCount > 0 -> Loading
            else -> Empty
        }
    }
}
