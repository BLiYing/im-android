package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

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
    // 惰性取值（[Str.s]）：不能再是编译期 `const val`，切语言要能拿到新文案。
    val TITLE: String get() = Str.s(R.string.settings_row_privacy)
    val BLOCKED_TITLE: String get() = Str.s(R.string.blocked_title)

    /** 第一组的组尾，也是「已屏蔽的用户」页顶部那行说明（iOS 两处同文）。 */
    val BLOCKED_HINT: String get() = Str.s(R.string.blocked_hint)
    val BLOCKED_EMPTY_TITLE: String get() = Str.s(R.string.blocked_empty_title)
    val BLOCKED_EMPTY_SUBTITLE: String get() = Str.s(R.string.blocked_empty_subtitle)
    val UNBLOCK: String get() = Str.s(R.string.blocked_unblock)

    // 惰性求值：**禁止在顶层 val 初始化时求值文案**，改成 get()，每次访问现烤，切语言立刻生效。
    val groups: List<PrivacyGroup> get() = listOf(
        PrivacyGroup(
            footer = BLOCKED_HINT,
            rows = listOf(
                PrivacyRow(BLOCKED_TITLE, PrivacyIcon.Blocked, PrivacyTint.Red, action = PrivacyAction.Blocked),
                PrivacyRow(Str.s(R.string.settings_change_password), PrivacyIcon.Key, PrivacyTint.Blue, action = PrivacyAction.ChangePassword),
            ),
        ),
        PrivacyGroup(
            header = Str.s(R.string.ps_section_account_protection),
            footer = Str.s(R.string.ps_account_protection_footer),
            rows = listOf(
                PrivacyRow(Str.s(R.string.ps_row_two_factor), PrivacyIcon.TwoStep, PrivacyTint.Gray, Str.s(R.string.common_off)),
                PrivacyRow(Str.s(R.string.ps_row_passkey), PrivacyIcon.Passkey, PrivacyTint.Purple, Str.s(R.string.common_off)),
                PrivacyRow(Str.s(R.string.ps_row_email_login), PrivacyIcon.Mail, PrivacyTint.Teal),
            ),
        ),
        PrivacyGroup(
            header = Str.s(R.string.ps_section_chat_privacy),
            footer = Str.s(R.string.ps_chat_privacy_footer),
            rows = listOf(
                PrivacyRow(Str.s(R.string.ps_row_auto_delete_messages), PrivacyIcon.Timer, PrivacyTint.Orange, Str.s(R.string.common_off)),
            ),
        ),
        PrivacyGroup(
            header = Str.s(R.string.ps_section_who_can_see),
            footer = Str.s(R.string.ps_who_can_see_footer),
            rows = listOf(
                PrivacyRow(Str.s(R.string.ps_row_phone_number), PrivacyIcon.Phone, PrivacyTint.Green, Str.s(R.string.common_my_contacts)),
                PrivacyRow(Str.s(R.string.ps_row_last_seen), PrivacyIcon.LastSeen, PrivacyTint.Blue, Str.s(R.string.common_my_contacts)),
                PrivacyRow(Str.s(R.string.ps_row_avatar), PrivacyIcon.Avatar, PrivacyTint.Purple, Str.s(R.string.common_everyone)),
                PrivacyRow(Str.s(R.string.ps_row_bio), PrivacyIcon.Bio, PrivacyTint.Yellow, Str.s(R.string.common_everyone)),
                PrivacyRow(Str.s(R.string.ps_row_birthday), PrivacyIcon.Birthday, PrivacyTint.Pink, Str.s(R.string.common_my_contacts)),
            ),
        ),
        PrivacyGroup(
            header = Str.s(R.string.ps_section_data),
            rows = listOf(
                PrivacyRow(Str.s(R.string.ps_row_clear_all_chats), PrivacyIcon.Trash, PrivacyTint.Gray),
                PrivacyRow(Str.s(R.string.ps_row_export_data), PrivacyIcon.Export, PrivacyTint.Blue),
            ),
        ),
    )

    /**
     * 「已屏蔽的用户」行的右值：**有才显数字**，0 与还没拉到都留空（iOS/Web 同）。
     * 拉取失败时调用方应保留上一次的数，别传 null 把它清掉——否则进出一次页面数字闪没一下。
     */
    fun blockedCountLabel(count: Int?): String = if (count != null && count > 0) count.toString() else ""
}
