package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话列表那一行的预览文案。
 *
 * 2026-09-16 把原先 `MessageRepository` 里的两份（下行 DTO 一份、落库行一份）合成一份，
 * 这些用例钉的就是"合成之后两条路仍然逐字一致"——两条路分叉的表现很隐蔽：
 * **发出去的那一刻列表显示 A，重进 App 之后显示 B**。
 */
class MessagePreviewTest {

    @Test
    fun text_returns_content() {
        assertEquals("你好", MessagePreview.of(ContentType.TEXT, "你好", null))
    }

    @Test
    fun caption_wins_over_placeholder() {
        assertEquals("海边", MessagePreview.of(ContentType.IMAGE, "/uploads/a.jpg", "海边"))
        assertEquals("跨年", MessagePreview.of(ContentType.VIDEO, "/uploads/a.mp4", "跨年"))
        assertEquals("报表", MessagePreview.of(ContentType.FILE, "/uploads/a.xlsx", "报表"))
    }

    @Test
    fun blank_caption_falls_back_to_placeholder() {
        assertEquals("[图片]", MessagePreview.of(ContentType.IMAGE, "/uploads/a.jpg", "   "))
        assertEquals("[图片]", MessagePreview.of(ContentType.IMAGE, "/uploads/a.jpg", null))
        assertEquals("[视频]", MessagePreview.of(ContentType.VIDEO, "/uploads/a.mp4", ""))
        assertEquals("[文件]", MessagePreview.of(ContentType.FILE, "/uploads/a.xlsx", null))
    }

    /** 语音**刻意不看 caption**：协议上语音没有图说，真有值也不该冒出来。 */
    @Test
    fun voice_ignores_caption() {
        assertEquals("[语音]", MessagePreview.of(ContentType.VOICE, "/uploads/a.amr", "不该显示"))
    }

    @Test
    fun cards_use_fixed_labels() {
        assertEquals("[个人名片]", MessagePreview.of(ContentType.CONTACT, "{\"uid\":\"u1\"}", null))
        assertEquals("[聊天记录]", MessagePreview.of(ContentType.CHAT_RECORD, "{\"items\":[]}", null))
    }

    /**
     * 系统消息与**未知类型**都回正文。未知类型多半是新版本加的消息，
     * 正文至少还能看个大概；显示成空白才是真的丢信息（PROTOCOL §2「未知要忍」）。
     */
    @Test
    fun system_and_unknown_return_content() {
        assertEquals("你已添加了对方", MessagePreview.of(ContentType.SYSTEM, "你已添加了对方", null))
        assertEquals("来自未来的消息", MessagePreview.of("sticker_v2", "来自未来的消息", null))
    }
}
