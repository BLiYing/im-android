package com.libeyond.imandroid.data

/**
 * 「我的资料」编辑表单的**纯校验/规整逻辑**。
 *
 * 抽出来是为了能变异验证：这几条规则改错了，界面上只表现为「保存后服务端拒了」，
 * 靠肉眼很难发现少了哪一条（CODING_STYLE §7③）。
 *
 * 规则**逐条对齐服务端**（`internal/account/userid.go`），客户端只是前置提示，
 * 服务端仍会再校验一遍——前置不是为了省服务端那道，是为了别让用户点了保存才知道。
 */
object ProfileEdit {

    /** 昵称长度上限（服务端 `maxNicknameRunes` = 32 rune）。 */
    const val MAX_NICKNAME_RUNES = 32

    /** 公开句柄形态：仅小写字母 / 数字 / 下划线，5–32 位（服务端 `usernameRe`）。 */
    private val USERNAME_RE = Regex("^[a-z0-9_]{5,32}$")

    /** 标签分隔符：空格、英文逗号、中文逗号、换行、制表符（对齐 iOS `tagsFromString:`）。 */
    private val TAG_SEPARATORS = Regex("[ ,，\\n\\t]+")

    /**
     * 昵称的前置校验。返回 null = 通过，否则是给用户看的原因。
     *
     * **必填**：它是全端显示名回退链的终点，清空会让各处露出 10 位数字内部 ID。
     */
    fun nicknameError(nickname: String): String? {
        val n = nickname.trim()
        return when {
            n.isEmpty() -> "昵称不能为空"
            n.length > MAX_NICKNAME_RUNES -> "昵称最多 $MAX_NICKNAME_RUNES 个字"
            else -> null
        }
    }

    /**
     * 用户名的前置校验。返回 null = 通过。
     *
     * **不做 toLowerCase**：与服务端同一取舍——静默把「Alice」改成「alice」落库，
     * 会与用户看到的输入不一致。显式拒绝更诚实。
     */
    fun usernameError(username: String): String? {
        val u = username.trim()
        return when {
            u.isEmpty() -> "用户名不能为空"
            !USERNAME_RE.matches(u) -> "用户名只能是小写字母、数字、下划线，5–32 位"
            else -> null
        }
    }

    /**
     * 这次保存要不要发改名请求。
     *
     * **只在真改了才发**：每次保存都发，会把「用户名已被占用」抛给一个压根没动
     * 用户名的人（iOS `saveUsernameIfChangedThenExitEditing` 的原话）。
     */
    fun shouldChangeUsername(input: String, loaded: String): Boolean {
        val u = input.trim()
        return u.isNotEmpty() && u != loaded.trim()
    }

    /** 标签串 → 列表：按分隔符切、去空白、丢空项、去重（保持输入顺序）。 */
    fun tagsFrom(text: String): List<String> =
        text.split(TAG_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 列表 → 标签串（回填输入框用）。 */
    fun tagsText(tags: List<String>): String = tags.joinToString(" ")
}
