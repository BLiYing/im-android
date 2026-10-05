package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/** 设置项图标（纯数据；UI 层映射到 Lucide 图标，与「我」页同款）。 */
enum class SettingsGlyph { Bookmark, Phone, Laptop, IdCard, Bell, Lock, HardDrive, Contrast, Zap, Globe, Ban, Key, Volume, Download, Smartphone, Wifi, Image, Video, File }

/** 设置项图标底色（UI 层映射到 `IMTheme.settingsIcons`）。 */
enum class SettingsTint { Blue, Green, Orange, Red, Gray, Yellow, Purple, Teal }

/** 「隐私与安全」内部页（原先是 PrivacySecurityHost 的私有枚举，搜索落点需要从外面指定）。 */
enum class PrivacyPage { Main, Blocked, ChangePassword }

/**
 * 二级页内的落点（[SettingsRoute.page] 之下再深的那几层）。`None` = 只开到「我」页的一级页。
 * 各 Host 收到后把它当**初始页**：返回键沿它们原有的链逐级退（如 提示音 → 类型页 → 通知 → 我）。
 */
sealed interface SettingsSub {
    data object None : SettingsSub
    data class Notification(val page: NotificationPage, val group: Boolean) : SettingsSub
    data class Privacy(val page: PrivacyPage) : SettingsSub
    /** [category] 为空 = 只开到「某网络的自动下载」页。 */
    data class Storage(val network: DownloadNetwork, val category: DownloadCategory?) : SettingsSub
}

/** 点中设置搜索结果后要走的路：切到「我」tab → [page] → [sub]。 */
data class SettingsRoute(val page: MePage, val sub: SettingsSub = SettingsSub.None)

/**
 * 设置项搜索的一条登记（SEARCH_DESIGN §3.1）。
 * @param path 所属页面路径（不含自己），副标题显示「A › B」；一级行为空。
 * @param route 「打开动作」——纯数据，由 `MeHost` 消费（登记表本身不碰 UI，才能单测）。
 */
data class SettingsSearchEntry(
    val id: String,
    val title: String,
    val path: List<String>,
    val glyph: SettingsGlyph,
    val tint: SettingsTint,
    val route: SettingsRoute,
) {
    /** 副标题「A › B」；一级行为空串。 */
    val subtitle: String get() = path.joinToString(PATH_SEPARATOR)

    companion object {
        const val PATH_SEPARATOR = " › "
    }
}

/**
 * 设置项**显式登记表** + 匹配口径（对齐 SEARCH_DESIGN §3.1，iOS/Web 同口径）。
 *
 * 不从 UI 行反推：新增一个可独立落点的设置页，要在这里**手工加一条**。
 * 不收：退出登录（破坏性）、文件夹（「开发中」）、纯展示行 / 开关内部子选项。
 * 文案走 [Str]（现烤，切语言立刻生效），所以每次调用 [entries] 重新生成。
 */
object SettingsSearchIndex {

    fun entries(): List<SettingsSearchEntry> = buildList {
        val notif = Str.s(R.string.ios_settings_row_notifications)
        val privacy = Str.s(R.string.settings_row_privacy)
        val storage = Str.s(R.string.ios_settings_row_data_storage)

        fun top(id: String, res: Int, g: SettingsGlyph, t: SettingsTint, page: MePage) =
            add(SettingsSearchEntry(id, Str.s(res), emptyList(), g, t, SettingsRoute(page)))

        top("favorites", R.string.common_saved_messages, SettingsGlyph.Bookmark, SettingsTint.Blue, MePage.Favorites)
        top("calls", R.string.ios_settings_row_recent_calls, SettingsGlyph.Phone, SettingsTint.Green, MePage.CallHistory)
        top("devices", R.string.settings_row_devices, SettingsGlyph.Laptop, SettingsTint.Orange, MePage.Devices)
        top("share_card", R.string.settings_info_share_card, SettingsGlyph.IdCard, SettingsTint.Teal, MePage.ShareCard)
        top("notifications", R.string.ios_settings_row_notifications, SettingsGlyph.Bell, SettingsTint.Red, MePage.Notifications)
        top("privacy", R.string.settings_row_privacy, SettingsGlyph.Lock, SettingsTint.Gray, MePage.Privacy)
        top("storage", R.string.ios_settings_row_data_storage, SettingsGlyph.HardDrive, SettingsTint.Green, MePage.DataStorage)
        top("appearance", R.string.ios_settings_row_appearance, SettingsGlyph.Contrast, SettingsTint.Blue, MePage.Appearance)
        top("power_saving", R.string.ios_settings_row_power_saving, SettingsGlyph.Zap, SettingsTint.Yellow, MePage.PowerSaving)
        top("language", R.string.settings_language_title, SettingsGlyph.Globe, SettingsTint.Purple, MePage.Language)

        // 通知：私聊 / 群聊两个类型页，各自下面一个「提示音」选择页
        for (group in listOf(false, true)) {
            val kind = if (group) "group" else "private"
            val typeTitle = Str.s(if (group) R.string.notif_type_group_title else R.string.notif_type_private_title)
            val typeRoute = SettingsRoute(MePage.Notifications, SettingsSub.Notification(NotificationPage.Type, group))
            add(SettingsSearchEntry("notif_$kind", typeTitle, listOf(notif), SettingsGlyph.Bell, SettingsTint.Red, typeRoute))
            val soundRoute = SettingsRoute(MePage.Notifications, SettingsSub.Notification(NotificationPage.Sound, group))
            add(SettingsSearchEntry("notif_${kind}_sound", Str.s(R.string.notif_type_sound), listOf(notif, typeTitle), SettingsGlyph.Volume, SettingsTint.Red, soundRoute))
        }

        // 隐私与安全
        add(SettingsSearchEntry("privacy_blocked", Str.s(R.string.blocked_title), listOf(privacy), SettingsGlyph.Ban, SettingsTint.Red,
            SettingsRoute(MePage.Privacy, SettingsSub.Privacy(PrivacyPage.Blocked))))
        add(SettingsSearchEntry("privacy_password", Str.s(R.string.settings_change_password), listOf(privacy), SettingsGlyph.Key, SettingsTint.Blue,
            SettingsRoute(MePage.Privacy, SettingsSub.Privacy(PrivacyPage.ChangePassword))))

        // 数据和存储：两个网络页 + 每网络三个媒体分类页（自动下载）
        val autoDownload = Str.s(R.string.autodl_master_switch)
        for (net in DownloadNetwork.entries) {
            val netGlyph = if (net == DownloadNetwork.Wifi) SettingsGlyph.Wifi else SettingsGlyph.Smartphone
            add(SettingsSearchEntry("storage_${net.name.lowercase()}", net.title, listOf(storage, autoDownload), netGlyph, SettingsTint.Green,
                SettingsRoute(MePage.DataStorage, SettingsSub.Storage(net, null))))
            for (cat in DownloadCategory.entries) {
                val glyph = when (cat) { DownloadCategory.Image -> SettingsGlyph.Image; DownloadCategory.Video -> SettingsGlyph.Video; DownloadCategory.File -> SettingsGlyph.File }
                add(SettingsSearchEntry("storage_${net.name.lowercase()}_${cat.name.lowercase()}", Str.s(R.string.autodl_cat_header_prefix, cat.title), listOf(storage, net.title),
                    glyph, SettingsTint.Green, SettingsRoute(MePage.DataStorage, SettingsSub.Storage(net, cat))))
            }
        }
    }

    /**
     * 命中判定：对「路径 + 标题」拼接串做大小写不敏感子串匹配（口径同 [ListSearch.normalizedQuery]），
     * 空查询 = 无命中（调用方据此不出「设置」分组）。
     * 排序：**标题命中排在仅路径命中之前**（搜「通知」时「通知与提示音」页本身先于它下面的子项），
     * 同档保持登记顺序（稳定）。
     */
    fun hits(entries: List<SettingsSearchEntry>, keyword: String): List<SettingsSearchEntry> {
        val q = ListSearch.normalizedQuery(keyword)
        if (q.isEmpty()) return emptyList()
        return entries
            .filter { haystack(it).contains(q, ignoreCase = true) }
            .sortedBy { if (it.title.contains(q, ignoreCase = true)) 0 else 1 }
    }

    private fun haystack(e: SettingsSearchEntry): String = (e.path + e.title).joinToString(" ")
}
