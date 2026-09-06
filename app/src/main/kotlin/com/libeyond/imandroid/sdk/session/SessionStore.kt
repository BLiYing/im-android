package com.libeyond.imandroid.sdk.session

import android.content.Context

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
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(v) = prefs.edit().apply { if (v == null) remove(KEY_USERNAME) else putString(KEY_USERNAME, v) }.apply()

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

    fun save(token: String, uid: String, refreshToken: String?, username: String?) {
        this.token = token
        this.uid = uid
        // refresh_token 是**可选**的：会话登记降级时服务端不下发。
        // 这时不能把已有的那枚抹掉——否则一次降级登录就让本机永久失去续期能力。
        if (!refreshToken.isNullOrEmpty()) this.refreshToken = refreshToken
        if (!username.isNullOrEmpty()) this.username = username
    }

    /**
     * 退出登录 / 会话被判定已死时调用。
     * **刻意不清 [host]**：服务器地址是本机配置不是账号数据，
     * 清掉会让用户每次退出后都要重填一遍内网 IP。
     */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_REFRESH).remove(KEY_UID).remove(KEY_USERNAME).apply()
    }

    private companion object {
        const val PREFS = "im_session"
        const val KEY_TOKEN = "token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_UID = "uid"
        const val KEY_USERNAME = "username"
        const val KEY_HOST = "host"
    }
}
