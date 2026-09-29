package com.libeyond.imandroid.data

/**
 * "用户此刻真的在看哪个会话"（[AlertContext.viewingConv] 的移动端判据来源）。
 *
 * 由 [com.libeyond.imandroid.ui.MainScreen] 维护（那里持有 `openConv`，是这条信息的唯一权威来源）；
 * `data/` 层（[MessageService] 的 NEW_MSG 分支）只读它，不碰 UI。**只是"最后打开的会话"**，
 * 不叠加"App 是不是前台"——两者是分开的输入（见 [AppActive]），组合判断交给调用方
 * （NOTIFICATIONS_DESIGN §3.1："viewingConv" 的移动端口径本就是"App 前台且在该会话页"两件事的合取）。
 */
object ViewingConv {
    @Volatile var current: String? = null
}
