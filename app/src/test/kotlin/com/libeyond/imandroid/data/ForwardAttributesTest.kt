package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.sdk.protocol.MentionSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 转发时随消息走的媒体元数据（[Forward.attributesOf]）。
 *
 * ### 为什么这一组必须有测试
 * 漏带**全程静默**：编译过、消息也确实发出去了，只有收件人看得出来——
 * 视频没 `poster` 就没有封面，没 `media_w/media_h` 就按「像素未知」走
 * [MediaDisplaySize] 的方块兜底，一条 16:9 的视频在对面变成 180×180 的方块
 * （2026-09-16 用户报）。而且这几个字段随消息落库，**事后补不回来**。
 *
 * im-web `useForward.ts` 与 iOS `forwardEchoContent:` 一直带着这几个字段，
 * 本端是这条对称链上最后补齐的一端（IMServer `docs/SYMMETRY.md`）。
 */
class ForwardAttributesTest {

    private fun video(
        poster: String? = "/uploads/p.jpg",
        w: Int? = 1920,
        h: Int? = 1080,
        dur: Int? = 12_000,
        thumb: String? = "data:image/jpeg;base64,xx",
    ) = MessageEntity(
        ownerUid = "me", convId = "c1", convSeq = 7, sender = "u2",
        contentType = ContentType.VIDEO, content = "/uploads/v.mp4",
        poster = poster, mediaW = w, mediaH = h, duration = dur, thumb = thumb,
    )

    @Test
    fun `视频转发带上封面、像素与时长`() {
        val a = Forward.attributesOf(video())
        assertEquals("/uploads/p.jpg", a.poster)
        assertEquals(1920, a.mediaW)
        assertEquals(1080, a.mediaH)
        assertEquals(12_000, a.duration)
        assertEquals("data:image/jpeg;base64,xx", a.thumb)
    }

    @Test
    fun `图片转发带上像素——没有它收端按方块排版`() {
        val img = MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = 8, sender = "u2",
            contentType = ContentType.IMAGE, content = "/uploads/a.jpg",
            mediaW = 800, mediaH = 600,
        )
        val a = Forward.attributesOf(img)
        assertEquals(800, a.mediaW)
        assertEquals(600, a.mediaH)
        assertNull(a.poster)
    }

    // 服务端一直收 waveform（protocol.SanitizeVoiceWaveform，gateway/voice_flow_test.go 钉着），
    // iOS 也一直带；本端 2026-09-16 才补上，此前转发语音在收端只有等高条纹
    @Test
    fun `语音转发带上波形`() {
        val voice = MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = 11, sender = "u2",
            contentType = ContentType.VOICE, content = "/uploads/a.m4a",
            duration = 3200, waveform = "ChwsPU1e",
        )
        val a = Forward.attributesOf(voice)
        assertEquals("ChwsPU1e", a.waveform)
        assertEquals(3200, a.duration)
    }

    @Test
    fun `文本消息一个媒体字段都不带`() {
        val t = MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = 9, sender = "u2",
            contentType = ContentType.TEXT, content = "你好",
        )
        assertEquals(Forward.Attributes(), Forward.attributesOf(t))
    }

    // 空串与 0 一律归一成 null：协议按 omitempty 读，空串与缺省等价但白占字节，
    // 而端上「isNullOrBlank」与「== null」两种判法并存时最容易写岔。
    @Test
    fun `空串与零值归一成 null，不往线上发空字段`() {
        val a = Forward.attributesOf(video(poster = "", w = 0, h = 0, dur = 0, thumb = ""))
        assertNull(a.poster)
        assertNull(a.thumb)
        assertNull(a.mediaW)
        assertNull(a.mediaH)
        assertNull(a.duration)
    }

    // 图说里的 @ 提及随转发跟随（iOS `forwardAttributesForMessage:stripCaption:` 的 `mentions`/`mentionSpans`，
    // 2026-09-22 用户报「转发逻辑与 iOS 对齐」时发现本端一直没带）：不带的话被 @ 的人转发过去
    // 收不到强提醒、caption 里的名字也不再可点高亮。
    @Test
    fun `图片图说里的 @ 提及随转发跟随`() {
        val spans = listOf(MentionSpan(offset = 0, length = 3, uid = "u9"))
        val img = MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = 12, sender = "u2",
            contentType = ContentType.IMAGE, content = "/uploads/a.jpg",
            caption = "@张三 看看这个", mentionSpans = Mention.encodeSpans(spans),
        )
        val a = Forward.attributesOf(img)
        assertEquals(listOf("u9"), a.mentions)
        assertEquals(spans, a.mentionSpans)
    }

    // 与 iOS 同一条取舍：纯文本消息走的是另一条不带 attrs 的转发路径，本就不转发提及，
    // 本端不该在这里多做——只在图片/视频的图说上带。
    @Test
    fun `文本消息不带 @ 提及——同 iOS 只在图视频图说上带`() {
        val spans = listOf(MentionSpan(offset = 0, length = 3, uid = "u9"))
        val text = MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = 13, sender = "u2",
            contentType = ContentType.TEXT, content = "@张三 你好", mentionSpans = Mention.encodeSpans(spans),
        )
        val a = Forward.attributesOf(text)
        assertNull(a.mentions)
        assertNull(a.mentionSpans)
    }

    @Test
    fun `归档目标转成消息时也带着这几个字段——归档查看器的转发走的是这条路`() {
        val target = ArchiveTarget(
            convSeq = 5, sender = "u2", contentType = ContentType.VIDEO, content = "/uploads/v.mp4",
            poster = "/uploads/p.jpg", mediaW = 1280, mediaH = 720, duration = 3_000,
        )
        val a = Forward.attributesOf(target.toMessageEntity("me", "c1"))
        assertEquals("/uploads/p.jpg", a.poster)
        assertEquals(1280, a.mediaW)
        assertEquals(720, a.mediaH)
        assertEquals(3_000, a.duration)
    }
}
