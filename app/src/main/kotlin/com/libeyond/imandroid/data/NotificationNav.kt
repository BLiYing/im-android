package com.libeyond.imandroid.data

/**
 * 「通知与提示音」这条链上「现在显示的是哪一页」。与 [ChatDetailPage] / [GroupInfoPage] 同一套办法：
 * 返回键一处按当前页派发，枚举加一页 `when` 就编译不过，漏不掉。
 *
 * 「私聊 / 群聊」这一位不放进枚举本身——它是 [NotificationPage.Type] 与 [NotificationPage.Sound]
 * 共用的另一维（提示音选择页要知道自己在改哪一类的提示音），由 `ui/NotificationSettingsHost.kt`
 * 另存一个 `group: Boolean`，与 [GroupInfoHost] 用独立布尔标志表达"这一页是关于谁的"同一手法。
 */
enum class NotificationPage(val depth: Int) { Main(0), Type(1), Sound(2) }

object NotificationNav {
    /** 返回后回到哪一页（`null` = 已在最外层，交还给外部 `onBack`）。 */
    fun back(page: NotificationPage): NotificationPage? = when (page) {
        NotificationPage.Sound -> NotificationPage.Type
        NotificationPage.Type -> NotificationPage.Main
        NotificationPage.Main -> null
    }
}
