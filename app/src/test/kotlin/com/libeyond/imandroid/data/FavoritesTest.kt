package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.api.Favorite
import com.libeyond.imandroid.sdk.api.FavoritePage
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏页（M4-4，对齐 iOS `IMFavoritesCategories` + `IMFavoritesViewController`）的判据。
 *
 * 这一族错了**都不报错**：归错页签是"那条收藏不见了"，翻页不去重是"同一条出现两次"，
 * 删除不减总数是"滚到底一直在拉空页"，转发漏元数据是"收件人那边视频没封面"——全是静默的。
 */
class FavoritesTest {

    private fun fav(
        id: Long = 1,
        type: String = ContentType.TEXT,
        content: String = "hi",
        from: String = "2002",
        conv: String = "u_1001_u_2002",
    ) = Favorite(id = id, contentType = type, content = content, sourceFrom = from, sourceConvId = conv, createdAt = 100 + id)

    // ————————————————— 归类 —————————————————

    @Test
    fun `各类内容归到对应页签，每条只进一签`() {
        val cases = mapOf(
            fav(type = ContentType.IMAGE, content = "/uploads/a.jpg") to FavoriteCategory.Media,
            fav(type = ContentType.VIDEO, content = "/uploads/a.mp4") to FavoriteCategory.Media,
            fav(type = ContentType.FILE, content = "/uploads/a.pdf") to FavoriteCategory.Files,
            fav(type = ContentType.VOICE, content = "/uploads/a.m4a") to FavoriteCategory.Voice,
            fav(type = "audio", content = "/uploads/a.m4a") to FavoriteCategory.Voice,
            fav(content = "纯文本") to FavoriteCategory.Text,
            // 混排文本含 URL 就进链接签（同详情页「链接」签口径）
            fav(content = "看看 https://example.com 这个") to FavoriteCategory.Links,
            fav(type = "link", content = "https://example.com") to FavoriteCategory.Links,
            fav(type = ContentType.CHAT_RECORD, content = """{"t":"聊天记录","items":[]}""") to FavoriteCategory.Record,
            fav(type = ContentType.CONTACT, content = """{"u":"3003","n":"小王"}""") to FavoriteCategory.Contact,
        )
        for ((f, want) in cases) {
            val hit = FavoriteCategory.entries.filter { Favorites.matches(f, it) }
            assertEquals("${f.contentType}:${f.content}", listOf(want), hit)
        }
    }

    @Test
    fun `老收藏把聊天记录存成了 text 也归聊天记录，且不进文本或链接签`() {
        val old = fav(content = """{"t":"群聊的聊天记录","items":[{"n":"a","ct":"text","c":"https://x.com"}]}""")
        assertTrue(Favorites.matches(old, FavoriteCategory.Record))
        assertFalse(Favorites.matches(old, FavoriteCategory.Text))
        assertFalse(Favorites.matches(old, FavoriteCategory.Links))
    }

    @Test
    fun `空内容与脏名片不进任何一签`() {
        assertTrue(FavoriteCategory.entries.none { Favorites.matches(fav(content = ""), it) })
        // 解析不出 uid 的名片：列表里不该出现点不动的空行
        val dirty = fav(type = ContentType.CONTACT, content = """{"n":"没有uid"}""")
        assertTrue(FavoriteCategory.entries.none { Favorites.matches(dirty, it) })
    }

    @Test
    fun `页签只列存在者，顺序照 iOS`() {
        val favs = listOf(
            fav(1, ContentType.CONTACT, """{"u":"3003"}"""),
            fav(2, content = "文本"),
            fav(3, ContentType.FILE, "/uploads/a.pdf"),
            fav(4, ContentType.IMAGE, "/uploads/a.jpg"),
        )
        assertEquals(
            listOf(FavoriteCategory.Media, FavoriteCategory.Files, FavoriteCategory.Text, FavoriteCategory.Contact),
            Favorites.categoriesOf(favs),
        )
        assertTrue(Favorites.categoriesOf(emptyList()).isEmpty())
    }

    @Test
    fun `默认停媒体，没媒体停首个；当前签消失时改停默认`() {
        val withMedia = listOf(FavoriteCategory.Media, FavoriteCategory.Text)
        assertEquals(FavoriteCategory.Media, Favorites.settle(withMedia, null))
        assertEquals(FavoriteCategory.Text, Favorites.settle(withMedia, FavoriteCategory.Text))
        assertEquals(FavoriteCategory.Files, Favorites.settle(listOf(FavoriteCategory.Files, FavoriteCategory.Text), null))
        // 删掉「语音」签的最后一条：别停在一个已经不存在的签上
        assertEquals(FavoriteCategory.Media, Favorites.settle(withMedia, FavoriteCategory.Voice))
        assertNull(Favorites.settle(emptyList(), FavoriteCategory.Voice))
    }

    @Test
    fun `搜索只在当前签内，匹配正文、图说与文件名，不分大小写`() {
        val favs = listOf(
            fav(1, ContentType.FILE, "/uploads/req-1__Report.PDF").copy(fileName = ""),
            fav(2, ContentType.FILE, "/uploads/b.bin").copy(fileName = "预算.xlsx"),
            fav(3, content = "report 写在文本里"),
        )
        assertEquals(listOf(1L), Favorites.filter(favs, FavoriteCategory.Files, "report").map { it.id })
        assertEquals(listOf(2L), Favorites.filter(favs, FavoriteCategory.Files, "预算").map { it.id })
        assertEquals(listOf(1L, 2L), Favorites.filter(favs, FavoriteCategory.Files, "  ").map { it.id })
    }

    // ————————————————— 分页与删除 —————————————————

    @Test
    fun `翻页按 id 去重——删过收藏后 offset 前移会把同一条读两次`() {
        val merged = Favorites.merge(listOf(fav(5), fav(4)), listOf(fav(4), fav(3)))
        assertEquals(listOf(5L, 4L, 3L), merged.map { it.id })
    }

    @Test
    fun `还有没有下一页看总数`() {
        assertTrue(Favorites.hasMore(100, 101))
        assertFalse(Favorites.hasMore(100, 100))
    }

    @Test
    fun `删除一条总数跟着减，删不存在的不动`() {
        val (left, total) = Favorites.afterDelete(listOf(fav(1), fav(2)), 150, 2)
        assertEquals(listOf(1L), left.map { it.id })
        assertEquals(149, total)
        assertEquals(150, Favorites.afterDelete(listOf(fav(1)), 150, 9).second)
        assertEquals(0, Favorites.afterDelete(listOf(fav(1)), 0, 1).second)
    }

    // ————————————————— 菜单 —————————————————

    @Test
    fun `菜单顺序照 iOS，复制只给文本与链接，取消下载只给下载中的文件视频`() {
        assertEquals(
            listOf(FavoriteAction.Forward, FavoriteAction.Copy, FavoriteAction.Delete),
            Favorites.actionsFor(fav(content = "文本"), downloading = false),
        )
        assertEquals(
            listOf(FavoriteAction.Forward, FavoriteAction.CancelDownload, FavoriteAction.Delete),
            Favorites.actionsFor(fav(type = ContentType.FILE, content = "/uploads/a.pdf"), downloading = true),
        )
        // 图片没有分片进度，无从"取消下载"
        assertEquals(
            listOf(FavoriteAction.Forward, FavoriteAction.Delete),
            Favorites.actionsFor(fav(type = ContentType.IMAGE, content = "/uploads/a.jpg"), downloading = true),
        )
        assertEquals("https://x.com", Favorites.copyText(fav(type = "link", content = "https://x.com")))
        assertNull(Favorites.copyText(fav(type = ContentType.VOICE, content = "/uploads/a.m4a")))
    }

    // ————————————————— 换算（复用详情页行 / 转发）—————————————————

    @Test
    fun `换成归档条目时用收藏 id 当键，发送者与元数据原样带过去`() {
        val f = fav(42, ContentType.VIDEO, "/uploads/v.mp4").copy(
            poster = "/uploads/p.jpg", thumb = "data:x", mediaW = 1920, mediaH = 1080, duration = 9000, fileSize = 123,
        )
        val item = Favorites.toConvMediaItem(f)
        assertEquals(42L, item.convSeq)
        assertEquals("2002", item.sender)
        assertEquals(listOf("/uploads/p.jpg", "data:x"), listOf(item.poster, item.thumb))
        assertEquals(listOf(1920, 1080, 9000), listOf(item.mediaW, item.mediaH, item.duration))
        assertEquals(123L, item.fileSize)
    }

    /** 转发的第三个入口（SYMMETRY 登记的那条）：漏带元数据收件人那边没封面、按方块排版，且事后补不回来。 */
    @Test
    fun `转发出去的消息带齐封面尺寸时长缩略与波形`() {
        val v = fav(7, ContentType.VIDEO, "/uploads/v.mp4").copy(
            poster = "/uploads/p.jpg", thumb = "data:x", mediaW = 720, mediaH = 1280, duration = 3000,
        )
        val a = Forward.attributesOf(Favorites.toMessageEntity(v, "1001", "小李"))
        assertEquals(Forward.Attributes(720, 1280, 3000, "/uploads/p.jpg", "data:x", null), a)
        val voice = fav(8, ContentType.VOICE, "/uploads/a.m4a").copy(duration = 2000, waveform = "AAEC")
        assertEquals("AAEC", Forward.attributesOf(Favorites.toMessageEntity(voice, "1001", "")).waveform)
        // convSeq 用收藏 id（>0），否则 Forward.canForward 判它是未确认消息
        assertTrue(Forward.canForward(Favorites.toMessageEntity(v, "1001", "")))
    }

    @Test
    fun `转发自写公开名，绝不写备注`() {
        val f = fav()
        val friend = FriendEntry(userId = "2002", username = "wang", nickname = "小王", remark = "老板")
        val origin = Favorites.originName(f, friend, null)
        assertEquals("小王", origin)
        assertEquals("小王", Forward.originOf(Favorites.toMessageEntity(f, "1001", origin), "1001", "我的名字"))
        assertEquals("@wang", Favorites.originName(f, friend.copy(nickname = ""), null))
    }

    @Test
    fun `转发自解析不出名字时写未命名用户，绝不把原发送者的内部 uid 发出去`() {
        // 复查抓出：刚进收藏页、名片还没补拉回来就发送，originOf 会落到末级兜底 msg.sender（10 位内部 uid）
        val stranger = fav(from = "2002")
        assertEquals("未命名用户", Forward.originOf(Favorites.toMessageEntity(stranger, "1001", ""), "1001", "@me"))
        // 我自己发的仍交给 originOf 写我的公开名
        val mine = fav(from = "1001")
        assertEquals("@me", Forward.originOf(Favorites.toMessageEntity(mine, "1001", ""), "1001", "@me"))
    }

    @Test
    fun `来自X：我、单聊会话名、好友备注、补拉名片，解析不出宁可空着也不落 uid`() {
        val f = fav(from = "2002", conv = "u_1001_u_2002")
        assertEquals("我", Favorites.sourceName(f.copy(sourceFrom = "1001"), "1001", null, null, null))
        val conv = ConversationEntity(ownerUid = "1001", convId = "u_1001_u_2002", peerUid = "2002", title = "小王", peerRemark = "老板")
        assertEquals("老板", Favorites.sourceName(f, "1001", conv, null, null))
        val friend = FriendEntry(userId = "2002", nickname = "小王", remark = "")
        assertEquals("小王", Favorites.sourceName(f, "1001", null, friend, null))
        assertEquals("阿三", Favorites.sourceName(f, "1001", null, null, UserCard(userId = "2002", nickname = "阿三")))
        assertEquals("", Favorites.sourceName(f, "1001", null, null, null))
        assertEquals("", Favorites.sourceName(f.copy(sourceFrom = ""), "1001", null, null, null))
    }

    @Test
    fun `自动下载策略档按来源会话分——群里收藏的按群聊档`() {
        assertTrue(Favorites.fromGroup(fav(conv = "g_1700000000")))
        assertFalse(Favorites.fromGroup(fav(conv = "u_1001_u_2002")))
        assertFalse(Favorites.fromGroup(fav(conv = "")))
        // 串到策略矩阵上：给群聊关了图片自动下载的账号，群里来的图在收藏页不能直出
        val s = DownloadPolicy.defaults().let { d -> d.copy(wifi = d.wifi.copy(image = d.wifi.image.copy(group = false))) }
        val groupFav = fav(type = ContentType.IMAGE, content = "/uploads/a.jpg", conv = "g_1")
        assertFalse(DownloadPolicy.shouldAutoDownload(s, "image", 0, Favorites.fromGroup(groupFav), onWifi = true))
    }

    @Test
    fun `列表响应按服务端字段名解析`() {
        val json = """
            {"favorites":[{"id":9,"content_type":"file","content":"/uploads/a.pdf","caption":"",
              "file_name":"a.pdf","file_size":2048,"duration":0,"waveform":"","thumb":"","poster":"",
              "media_w":0,"media_h":0,"source_conv_id":"g_1","source_conv_seq":33,"source_from":"2002","created_at":1700}],
             "page":{"total":101,"limit":100,"offset":0}}
        """.trimIndent()
        val p = ProtocolJson.decodeFromString(FavoritePage.serializer(), json)
        assertEquals(101, p.page.total)
        val f = p.favorites.single()
        assertEquals(listOf("a.pdf", "g_1", "2002"), listOf(f.fileName, f.sourceConvId, f.sourceFrom))
        assertEquals(listOf(9L, 2048L, 33L, 1700L), listOf(f.id, f.fileSize, f.sourceConvSeq, f.createdAt))
    }
}
