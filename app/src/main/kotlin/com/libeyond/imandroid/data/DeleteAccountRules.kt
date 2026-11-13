package com.libeyond.imandroid.data

/**
 * 注销账号页的纯判据（状态逻辑与界面分开，便于单测）。
 * 设计：IMServer `docs/design/DELETE_ACCOUNT_DESIGN.md` §3。
 */
object DeleteAccountRules {
    /** 阻断提示里最多列几个群，其余折成「等 N 个」。 */
    const val MAX_LISTED_GROUPS = 3

    /**
     * 红按钮可点：不在提交中。身份证明（密码或短信验证码）改在确认身份弹层里输（PHONE_AUTH_DESIGN §7.2）。
     * 群主阻断**不再**禁用按钮：「还有群没转让」不会用掉验证码（PROTOCOL §7.3），用户转让后回到本页可以拿同一个码直接重交。
     */
    fun canSubmit(submitting: Boolean): Boolean = !submitting

    /** 阻断态下展示的群名（最多 [MAX_LISTED_GROUPS] 个）。 */
    fun <T> listedGroups(ownedGroups: List<T>): List<T> = ownedGroups.take(MAX_LISTED_GROUPS)

    /** 超出展示数量的群数；0 表示不需要「等 N 个」。 */
    fun moreCount(ownedGroups: List<*>): Int = (ownedGroups.size - MAX_LISTED_GROUPS).coerceAtLeast(0)
}
