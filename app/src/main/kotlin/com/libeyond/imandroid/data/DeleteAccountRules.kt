package com.libeyond.imandroid.data

/**
 * 注销账号页的纯判据（状态逻辑与界面分开，便于单测）。
 * 设计：IMServer `docs/design/DELETE_ACCOUNT_DESIGN.md` §3。
 */
object DeleteAccountRules {
    /** 阻断提示里最多列几个群，其余折成「等 N 个」。 */
    const val MAX_LISTED_GROUPS = 3

    /** 红按钮可点：已填密码、不在提交中、没被群主身份阻断。 */
    fun canSubmit(password: String, submitting: Boolean, ownedGroups: List<String>): Boolean =
        password.isNotEmpty() && !submitting && ownedGroups.isEmpty()

    /** 阻断态下展示的群名（最多 [MAX_LISTED_GROUPS] 个）。 */
    fun listedGroups(ownedGroups: List<String>): List<String> = ownedGroups.take(MAX_LISTED_GROUPS)

    /** 超出展示数量的群数；0 表示不需要「等 N 个」。 */
    fun moreCount(ownedGroups: List<String>): Int = (ownedGroups.size - MAX_LISTED_GROUPS).coerceAtLeast(0)
}
