package com.libeyond.imandroid.data

/**
 * 「我 ▸ 隐私与安全」的页面结构（对齐 iOS `IMPrivacySecurityViewController` 的 `buildGroups`，
 * Web `PrivacySecurityPanel` 同一张表；设计见 `../IMServer/docs/design/PRIVACY_SECURITY_DESIGN.md`）。
 *
 * **纯数据、不碰 Compose**：分组、顺序、文案、占位右值都在这里，页面只负责画——
 * 三端这张表逐字一致，抄错一行由 `PrivacySecurityTest` 当场抓。
 * 图标与底色用语义名，到 Lucide / `IMTheme.settingsIcons` 的映射在页面里（本层不能 import 它们）。
 *
 * 只有第一组两行是真功能；其余四组是**灰置占位**（点了提示开发中）。占位行照样列出来，
 * 理由与「我」页相同：删掉会让三端这一页长得不一样，用户换个端就找不到东西。
 */
enum class PrivacyIcon { Blocked, Key, TwoStep, Passkey, Mail, Timer, Phone, LastSeen, Avatar, Bio, Birthday, Trash, Export }

/** 图标底色（iOS `UIColor.system*`）。 */
enum class PrivacyTint { Red, Blue, Gray, Purple, Teal, Orange, Green, Yellow, Pink }

/** 点一行做什么。 */
enum class PrivacyAction { Blocked, ChangePassword, ComingSoon }

data class PrivacyRow(
    val title: String,
    val icon: PrivacyIcon,
    val tint: PrivacyTint,
    /** 占位行的右值（「关闭」「我的联系人」）。活行的右值由页面按数据填。 */
    val value: String = "",
    val action: PrivacyAction = PrivacyAction.ComingSoon,
) {
    /** 灰置（iOS `isPlaceholder`）：标题降一档、右值再降一档，**图标保留全彩**，chevron 保留。 */
    val isPlaceholder: Boolean get() = action == PrivacyAction.ComingSoon
}

data class PrivacyGroup(val header: String = "", val footer: String = "", val rows: List<PrivacyRow>)

object PrivacySecurity {
    const val TITLE = "隐私与安全"
    const val BLOCKED_TITLE = "已屏蔽的用户"

    /** 第一组的组尾，也是「已屏蔽的用户」页顶部那行说明（iOS 两处同文）。 */
    const val BLOCKED_HINT = "已屏蔽的用户不能给你发消息，也看不到你的资料。"
    const val BLOCKED_EMPTY_TITLE = "暂无已屏蔽的用户"
    const val BLOCKED_EMPTY_SUBTITLE = "你在通讯录或聊天页拉黑对方后，会出现在这里。"
    const val UNBLOCK = "取消屏蔽"

    val groups: List<PrivacyGroup> = listOf(
        PrivacyGroup(
            footer = BLOCKED_HINT,
            rows = listOf(
                PrivacyRow(BLOCKED_TITLE, PrivacyIcon.Blocked, PrivacyTint.Red, action = PrivacyAction.Blocked),
                PrivacyRow("修改密码", PrivacyIcon.Key, PrivacyTint.Blue, action = PrivacyAction.ChangePassword),
            ),
        ),
        PrivacyGroup(
            header = "账号保护",
            footer = "绑定第二因子后，即使密码泄露也无法登录你的账号。",
            rows = listOf(
                PrivacyRow("两步验证", PrivacyIcon.TwoStep, PrivacyTint.Gray, "关闭"),
                PrivacyRow("通行密钥", PrivacyIcon.Passkey, PrivacyTint.Purple, "关闭"),
                PrivacyRow("邮箱登录", PrivacyIcon.Mail, PrivacyTint.Teal),
            ),
        ),
        PrivacyGroup(
            header = "会话隐私",
            footer = "为你开始的每个新会话默认开启阅后自删。",
            rows = listOf(PrivacyRow("自动删除消息", PrivacyIcon.Timer, PrivacyTint.Orange, "关闭")),
        ),
        PrivacyGroup(
            header = "谁能看到",
            footer = "这些设置决定他人在你的资料页看到多少。",
            rows = listOf(
                PrivacyRow("手机号码", PrivacyIcon.Phone, PrivacyTint.Green, "我的联系人"),
                PrivacyRow("上次上线", PrivacyIcon.LastSeen, PrivacyTint.Blue, "我的联系人"),
                PrivacyRow("头像", PrivacyIcon.Avatar, PrivacyTint.Purple, "所有人"),
                PrivacyRow("个人简介", PrivacyIcon.Bio, PrivacyTint.Yellow, "所有人"),
                PrivacyRow("生日", PrivacyIcon.Birthday, PrivacyTint.Pink, "我的联系人"),
            ),
        ),
        PrivacyGroup(
            header = "数据",
            rows = listOf(
                PrivacyRow("清除所有对话", PrivacyIcon.Trash, PrivacyTint.Gray),
                PrivacyRow("导出我的数据", PrivacyIcon.Export, PrivacyTint.Blue),
            ),
        ),
    )

    /**
     * 「已屏蔽的用户」行的右值：**有才显数字**，0 与还没拉到都留空（iOS/Web 同）。
     * 拉取失败时调用方应保留上一次的数，别传 null 把它清掉——否则进出一次页面数字闪没一下。
     */
    fun blockedCountLabel(count: Int?): String = if (count != null && count > 0) count.toString() else ""
}
