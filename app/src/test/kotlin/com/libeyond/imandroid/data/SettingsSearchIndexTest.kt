package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {
    private val all = SettingsSearchIndex.entries()
    private fun hits(q: String) = SettingsSearchIndex.hits(all, q).map { it.id }

    @Test fun emptyOrBlankQueryHasNoHits() {
        assertTrue(hits("").isEmpty())
        assertTrue(hits("   ").isEmpty())
    }

    @Test fun noMatchIsEmpty() {
        assertTrue(hits("zzzz不存在").isEmpty())
    }

    @Test fun idsAreUnique() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test fun logoutAndComingSoonRowsAreNotIndexed() {
        assertTrue(hits("退出登录").isEmpty())
        assertTrue(hits("文件夹").isEmpty())
    }

    @Test fun matchesTitleCaseInsensitiveAndTrimsQuery() {
        assertTrue(hits("  隐私 ").contains("privacy"))
        assertEquals(hits("省电"), listOf("power_saving"))
    }

    @Test fun soundEntriesDisambiguatedByPath() {
        val sound = SettingsSearchIndex.hits(all, "提示音").filter { it.title == "提示音" }
        assertEquals(listOf("notif_private_sound", "notif_group_sound"), sound.map { it.id })
        assertEquals(listOf("通知与提示音 › 私聊通知", "通知与提示音 › 群聊通知"), sound.map { it.subtitle })
    }

    @Test fun pathOnlyMatchComesAfterTitleMatches() {
        // 「移动数据」只在路径里：其下三个分类页是仅路径命中；网络页自己标题命中，排前面
        val r = hits("移动数据")
        assertEquals("storage_cellular", r.first())
        assertTrue(r.containsAll(listOf("storage_cellular_image", "storage_cellular_video", "storage_cellular_file")))
        assertFalse(r.any { it.startsWith("storage_wifi") })
    }

    @Test fun titleHitsSortBeforePathHitsKeepingRegistryOrder() {
        // 「通知」：通知页本身(标题命中,一级) → 私聊通知/群聊通知(标题命中) → 两条提示音(仅路径命中)
        assertEquals(
            listOf("notifications", "notif_private", "notif_group", "notif_private_sound", "notif_group_sound"),
            hits("通知"),
        )
    }

    @Test fun matchesAcrossPathAndTitleConcatenation() {
        assertEquals(listOf("privacy_password"), hits("隐私与安全 修改密码"))
    }

    @Test fun routesPointToTheRightPages() {
        val byId = all.associateBy { it.id }
        assertEquals(SettingsRoute(MePage.Notifications, SettingsSub.Notification(NotificationPage.Sound, true)), byId.getValue("notif_group_sound").route)
        assertEquals(SettingsRoute(MePage.Privacy, SettingsSub.Privacy(PrivacyPage.Blocked)), byId.getValue("privacy_blocked").route)
        assertEquals(
            SettingsRoute(MePage.DataStorage, SettingsSub.Storage(DownloadNetwork.Wifi, DownloadCategory.Video)),
            byId.getValue("storage_wifi_video").route,
        )
        assertEquals(SettingsRoute(MePage.Language), byId.getValue("language").route)
    }
}
