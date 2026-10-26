package com.libeyond.imandroid.data

/**
 * 多个头像来源并存时谁说了算。**服务端给空串 = 对方设了「头像不可见」或没有头像**（PRIVACY_VISIBILITY_DESIGN §1），
 * 是权威结论，不能再退到更旧的缓存把已隐藏的头像画回来。只有「该来源里根本没有这个人」才退下一级。
 */
object AvatarPrecedence {

    /** 聊天里发送者头像：成员表里有这个人（哪怕是空串）就以它为准；没有才用全局解析缓存。 */
    fun sender(memberAvatars: Map<String, String>, uid: String, cached: String?): String? =
        memberAvatars[uid] ?: cached

    /** 资料入口的头像：好友表里有这个人（[friendAvatar] 非 null，哪怕空串）就以它为准，否则用调用方带来的。 */
    fun profile(friendAvatar: String?, passed: String): String = friendAvatar ?: passed
}
