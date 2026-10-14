package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天文件夹纯逻辑（Tab 角标口径 / 尾部区域 / 横滑判定）：读**三端共用向量** `chat_folder_logic.json`
 * （真源在 IMServer `docs/conformance/`，`src/test/resources/` 是拷贝；真源在旁边时会比对，防漂移——同
 * `MuteStateTest` 的写法）。改口径先改向量。
 */
class ChatFolderLogicTest {

    private val text: String =
        checkNotNull(javaClass.classLoader?.getResource("chat_folder_logic.json")) { "缺 chat_folder_logic.json" }.readText()
    private val root = Json.parseToJsonElement(text).jsonObject

    private fun conv(id: String, unread: Int = 0, muted: Boolean = false, muteUntil: Long = 0, marked: Boolean = false, mention: Boolean = false) =
        ConversationEntity(
            ownerUid = "me", convId = id, unread = unread, muted = muted, muteUntil = muteUntil,
            markedUnread = marked, mentionUnread = mention,
        )

    @Test
    fun `向量与真源一致`() {
        val src = File("../../IMServer/docs/conformance/chat_folder_logic.json")
        if (src.exists()) assertEquals("测试资源与真源漂移，重新拷贝", src.readText().trim(), text.trim())
    }

    @Test
    fun `尾部区域向量`() {
        val cases = root["tail_zone"]!!.jsonArray
        assertTrue(cases.size >= 3)
        for (el in cases) {
            val c = el.jsonObject
            val got = ChatFolderLogic.tailZoneWidth(c["list_width"]!!.jsonPrimitive.float, c["actions_width"]!!.jsonPrimitive.float)
            assertEquals(c["name"]!!.jsonPrimitive.content, c["expect"]!!.jsonPrimitive.float, got, 0.001f)
        }
    }

    @Test
    fun `横滑判定向量`() {
        val cases = root["swipe"]!!.jsonArray
        assertTrue(cases.size >= 9)
        val names = mapOf("none" to FolderSwipe.None, "next" to FolderSwipe.NextTab, "prev" to FolderSwipe.PrevTab, "row" to FolderSwipe.RowSwipe)
        for (el in cases) {
            val c = el.jsonObject
            val got = ChatFolderLogic.swipeOutcome(
                translationX = c["tx"]!!.jsonPrimitive.float, startX = c["start_x"]!!.jsonPrimitive.float,
                listWidth = c["list_width"]!!.jsonPrimitive.float, actionsWidth = c["actions_width"]!!.jsonPrimitive.float,
                hasNext = c["has_next"]!!.jsonPrimitive.boolean, hasPrev = c["has_prev"]!!.jsonPrimitive.boolean,
                rowOpen = c["row_open"]!!.jsonPrimitive.boolean,
            )
            assertEquals(c["name"]!!.jsonPrimitive.content, names[c["expect"]!!.jsonPrimitive.content], got)
        }
    }

    @Test
    fun `Tab 角标向量`() {
        val cases = root["badge"]!!.jsonArray
        assertTrue(cases.size >= 9)
        for (el in cases) {
            val c = el.jsonObject
            val convs = c["convs"]!!.jsonArray.map {
                val d = it.jsonObject
                conv(
                    d["conv_id"]!!.jsonPrimitive.content, d["unread"]!!.jsonPrimitive.int, d["muted"]!!.jsonPrimitive.boolean,
                    d["mute_until"]!!.jsonPrimitive.long, d["marked_unread"]!!.jsonPrimitive.boolean, d["mention_unread"]!!.jsonPrimitive.boolean,
                )
            }
            val ids = c["folder"]
            val folder = if (ids == null || ids is JsonNull) null
            else ChatFolder("f", "x", 0, (ids as JsonArray).map { it.jsonPrimitive.content })
            val got = ChatFolderLogic.badge(convs, folder, c["now"]!!.jsonPrimitive.long)
            val exp = c["expect"]!!.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            assertEquals("$name count", exp["count"]!!.jsonPrimitive.int, got.count)
            assertEquals("$name muted", exp["muted"]!!.jsonPrimitive.boolean, got.muted)
        }
    }

    @Test
    fun `过滤保序且全部原样返回`() {
        val all = listOf(conv("a"), conv("b"), conv("c"))
        val f = ChatFolder("f", "x", 0, listOf("c", "a")) // 文件夹内加入顺序 c,a；列表顺序 a,c
        assertEquals(listOf("a", "c"), ChatFolderLogic.filter(all, f).map { it.convId })
        assertSame(all, ChatFolderLogic.filter(all, null))
    }

    @Test
    fun `tabIndex 选中项被删回落全部`() {
        val fs = listOf(ChatFolder("f1", "a", 0, emptyList()), ChatFolder("f2", "b", 1, emptyList()))
        assertEquals(0, ChatFolderLogic.tabIndex(null, fs))
        assertEquals(2, ChatFolderLogic.tabIndex("f2", fs))
        assertEquals(0, ChatFolderLogic.tabIndex("gone", fs))
    }
}
