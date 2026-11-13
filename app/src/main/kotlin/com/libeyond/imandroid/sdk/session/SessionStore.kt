package com.libeyond.imandroid.sdk.session

import android.content.Context
import android.content.SharedPreferences

/**
 * 登录会话的本地持久化。
 *
 * ## 存凭据，不存密码
 * Web 端曾把**明文密码**存在 localStorage 里当"记住登录"。真问题不是"泄露风险大一点"，
 * 而是攻击者拿到的是**密码**而不是会话——「注销某台设备」对它完全无效
 * （注销掉的只是会话，对方用密码立刻重登）。2026-09-06 已改为存 `refresh_token`。
 *
 * 本端从第一版就只存凭据：`token`（24h 访问令牌）+ `refreshToken`（180 天绝对寿命，
 * 绑定设备会话 sid，故设备注销 / 改密下线其它设备 / 封号三条通路都对它生效）。
 *
 * ## 为什么不是 EncryptedSharedPreferences
 * `androidx.security:security-crypto` 已废弃（2024 起不再维护）。当前用普通
 * SharedPreferences——它在应用私有目录里，未 root 的设备上其它应用读不到。
 * 要再加一层就用 Android Keystore 自己包，**列在 TODO 里，别假装已经加密了**。
 */
class SessionStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(v) = prefs.edit().apply { if (v == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, v) }.apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(v) = prefs.edit().apply { if (v == null) remove(KEY_REFRESH) else putString(KEY_REFRESH, v) }.apply()

    /** 内部 ID（10 位随机数字）。**只用于接口参数与本地库主键，绝不上 UI**。 */
    var uid: String?
        get() = prefs.getString(KEY_UID, null)
        set(v) = prefs.edit().apply { if (v == null) remove(KEY_UID) else putString(KEY_UID, v) }.apply()

    /**
     * 公开句柄。**读写都过 [handleOrNull]**：老版本把用户输入的登录名（可能是邮箱 / 手机号）存进来过，
     * 冷启动恢复与续期不经过登录响应、不会改写它——这里读到不像句柄的存量值一律当空并当场清掉，
     * 否则「转发自 @alice@example.com」会在进「我」页校正之前发出去。
     */
    var username: String?
        get() {
            val raw = prefs.getString(KEY_USERNAME, null) ?: return null
            // 本版本起写入的都来自服务端（带 [KEY_USERNAME_TRUSTED]）；没有标记的是老版本存的输入值，再挡掉手机号形态
            val trusted = prefs.getBoolean(KEY_USERNAME_TRUSTED, false)
            return handleOrNull(raw)?.takeIf { trusted || !looksLikePhone(it) }
                ?: run { prefs.edit().remove(KEY_USERNAME).remove(KEY_USERNAME_TRUSTED).apply(); null }
        }
        set(v) = prefs.edit().apply {
            val h = handleOrNull(v)
            if (h == null) remove(KEY_USERNAME).remove(KEY_USERNAME_TRUSTED) else putString(KEY_USERNAME, h).putBoolean(KEY_USERNAME_TRUSTED, true)
        }.apply()

    /**
     * 服务器地址。**必须与凭据一起持久化**：token 存了而 host 没存，重启后就会拿着
     * 上一台服务器的凭据去连默认地址——真机用户改成内网 IP 登录后杀进程重进，
     * host 回落 10.0.2.2（真机不可达），WS 永远连不上，而主界面没有改地址的入口
     * （/code-review 2026-09-07 查出）。空串表示用构建默认值。
     */
    var host: String
        get() = prefs.getString(KEY_HOST, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_HOST, v).apply()

    val isLoggedIn: Boolean get() = !token.isNullOrEmpty() && !uid.isNullOrEmpty()

    /**
     * 登录成功（密码 / 两步验证第二步 / 验证码 / 完善资料）后写入会话。
     *
     * [username] 是**服务端响应里**的公开用户名，**整体覆盖**：没给（老服务端 / 取不到）就清掉旧值，
     * 宁可让「我的公开名」退回「未命名用户」，也不能沿用上一个账号的名字，更不能用用户输入的登录名兜底
     * （那可能是手机号 / 邮箱，会经「转发自 @…」发给别人）。
     */
    fun saveLogin(token: String, uid: String, refreshToken: String?, username: String?) {
        saveRefreshed(token, uid, refreshToken)
        this.username = username
    }

    /** 续期成功后写入：续期响应不带用户名，**不动** [username]。 */
    fun saveRefreshed(token: String, uid: String, refreshToken: String?) {
        this.token = token
        this.uid = uid
        // refresh_token 是**可选**的：会话登记降级时服务端不下发。
        // 这时不能把已有的那枚抹掉——否则一次降级登录就让本机永久失去续期能力。
        if (!refreshToken.isNullOrEmpty()) this.refreshToken = refreshToken
    }

    /**
     * 退出登录 / 会话被判定已死时调用。
     * **刻意不清 [host]**：服务器地址是本机配置不是账号数据，
     * 清掉会让用户每次退出后都要重填一遍内网 IP。
     */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_REFRESH).remove(KEY_UID).remove(KEY_USERNAME).remove(KEY_USERNAME_TRUSTED).remove(KEY_MY_PROFILE).commit() // 同步落盘：apply 异步，进程在落盘前被杀会让已死 token / 已注销账号的登录态复活
    }

    /**
     * 待吊销的续期凭据（JSON，见 [PendingLogoutQueue]）。**[clear] 刻意不清**：它正是要活过退出登录。
     * 用 commit 同步落盘：退出后进程随时可能被杀，丢了就再也没法补吊销。
     */
    val pendingLogoutJson: String? get() = prefs.getString(KEY_PENDING_LOGOUT, null)

    /** 返回是否写成功（磁盘满等会失败）；调用方失败时仍保留内存里的那份，见 [PendingLogoutQueue]。 */
    fun writePendingLogoutJson(v: String?): Boolean =
        prefs.edit().apply { if (v == null) remove(KEY_PENDING_LOGOUT) else putString(KEY_PENDING_LOGOUT, v) }.commit()

    /**
     * 本人资料的本地副本（「我」页头部）：断网/断连时 `GET /users/me` 拿不到，头部不能退回「未命名用户」+ 首字母圈。
     * 带 uid 存、按 uid 取（[MyProfileCodec]），换号读不到上一个账号的；[clear] 一并擦掉。
     */
    var myProfileJson: String?
        get() = prefs.getString(KEY_MY_PROFILE, null)
        set(v) = prefs.edit().apply { if (v == null) remove(KEY_MY_PROFILE) else putString(KEY_MY_PROFILE, v) }.apply()

    /**
     * 「历史遗留 `msg_op` 事件行已收敛过」的一次性标记（按账号）。
     *
     * **必须是一次性的**：那次收敛按 `contentType` 扫全表，而 `message` 表上没有这一列的索引
     * ——20 多万行的库上每次启动扫一遍会让首屏卡住（2026-09-09 模拟器实测：两次
     * `Long db operation`，从启动到会话列表可用约 90 秒）。一次性的数据订正就该配一次性标记，
     * 而不是为它加一条只用一次的索引。
     */
    fun msgOpConverged(uid: String): Boolean = prefs.getBoolean(KEY_MSGOP_CONVERGED + uid, false)

    fun markMsgOpConverged(uid: String) {
        prefs.edit().putBoolean(KEY_MSGOP_CONVERGED + uid, true).apply()
    }

    companion object {
        /** 服务端用户名规则 `^[a-z0-9_]{5,32}$`（PROTOCOL §11）；不符合（含 `@` / `+` / 空）返回 null。 */
        private val HANDLE = Regex("^[a-z0-9_]{5,32}$")
        fun handleOrNull(v: String?): String? = v?.trim()?.takeIf { HANDLE.matches(it) }

        /** 登录名解析里会被当成手机号的形态（5–15 位纯数字，PROTOCOL §7.3）：老版本存量值里出现即视为手机号。 */
        private val PHONE_LIKE = Regex("^[0-9]{5,15}$")
        fun looksLikePhone(v: String): Boolean = PHONE_LIKE.matches(v)

        /** 我的公开名：`@句柄`，没有可信句柄时「未命名用户」（见 `IMClient.myPublicName`）。 */
        fun publicName(handle: String?): String = handleOrNull(handle)?.let { "@$it" } ?: "未命名用户"

        private const val PREFS = "im_session"
        private const val KEY_TOKEN = "token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_UID = "uid"
        private const val KEY_USERNAME = "username"
        /** 存的用户名来自服务端（登录响应 / 本人资料），不是老版本存下的输入值。 */
        private const val KEY_USERNAME_TRUSTED = "username_from_server"
        private const val KEY_HOST = "host"
        private const val KEY_MY_PROFILE = "my_profile"
        private const val KEY_PENDING_LOGOUT = "pending_logout"
        private const val KEY_MSGOP_CONVERGED = "msgop_converged_"
    }
}
