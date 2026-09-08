package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档长按菜单的可见性判据（对齐 iOS `contentMenuConfigForMessage:`）。
 *
 * ### 为什么抽成纯函数 + 单测
 * `docs/UI_PARITY_IOS.md §5` 那条复盘的原话：**「谁看得见哪个按钮」是最容易漏、也最容易验的一类**。
 * 而这一族错得最静默的方向是**多给**——把「为所有人删除」给到不该有的人，端上不会报错
 * （服务端会拒，回 300006），用户看到的只是"点了没用"，没人会想到是判据反了。
 * `perm_invite`/`perm_edit_info` 那两条判据就正反着写过一次，还被单测钉住了错的那版。
 */
class ArchiveActionsTest {

    private fun actions(
        convSeq: Long = 10,
        downloading: Boolean = false,
        mine: Boolean = false,
        isGroup: Boolean = false,
        iAmManager: Boolean = false,
    ) = ArchiveActions.availableFor(convSeq, downloading, mine, isGroup, iAmManager)

    // ————————————————— 前置门槛 —————————————————

    @Test
    fun `没有 conv_seq 的项一律不弹菜单`() {
        // 同 iOS：`m.convSeq <= 0` 直接返回 nil。没有序号就既定位不了、也删不掉，
        // 弹一个全是死项的菜单比不弹更糟。
        assertTrue(actions(convSeq = 0).isEmpty())
        assertTrue(actions(convSeq = -1, mine = true).isEmpty())
    }

    // ————————————————— 顺序与恒显项 —————————————————

    @Test
    fun `转发与定位到聊天恒显，且转发在前——顺序照抄 iOS`() {
        val a = actions()
        assertEquals(ArchiveAction.Forward, a[0])
        assertEquals(ArchiveAction.LocateInChat, a[1])
    }

    @Test
    fun `破坏性的删除一律排在最后`() {
        val a = actions(mine = true, downloading = true)
        assertEquals(
            listOf(
                ArchiveAction.Forward,
                ArchiveAction.LocateInChat,
                ArchiveAction.CancelDownload,
                ArchiveAction.HideForMe,
                ArchiveAction.DeleteForEveryone,
            ),
            a,
        )
    }

    // ————————————————— 取消下载 —————————————————

    @Test
    fun `只有下载中或已暂停才给取消下载`() {
        assertTrue(ArchiveAction.CancelDownload in actions(downloading = true))
        assertFalse(ArchiveAction.CancelDownload in actions(downloading = false))
    }

    // ————————————————— 删除的两个档位 —————————————————

    @Test
    fun `自己发的才能为所有人删除——单聊`() {
        assertTrue(ArchiveAction.DeleteForEveryone in actions(mine = true))
        assertFalse(ArchiveAction.DeleteForEveryone in actions(mine = false))
    }

    @Test
    fun `群里管理员可以删别人的——普通成员不行`() {
        assertTrue(ArchiveAction.DeleteForEveryone in
            actions(mine = false, isGroup = true, iAmManager = true))
        assertFalse(ArchiveAction.DeleteForEveryone in
            actions(mine = false, isGroup = true, iAmManager = false))
    }

    @Test
    fun `管理员身份在单聊里不算数`() {
        // isGroup=false 时 iAmManager 恒无意义；漏掉这个 && 会让"我在别的群是管理员"
        // 泄漏到单聊里，把别人发的消息也给出「为所有人删除」
        assertFalse(ArchiveActions.canDeleteForEveryone(
            mine = false, isGroup = false, iAmManager = true))
    }

    @Test
    fun `仅删除自己恒显——谁都能把自己这一侧清掉`() {
        assertTrue(ArchiveAction.HideForMe in actions(mine = false))
        assertTrue(ArchiveAction.HideForMe in actions(mine = true, isGroup = true, iAmManager = true))
    }

    // ————————————————— 两种来源的适配 —————————————————

    @Test
    fun `服务端归档项与本地消息转出来的目标一致`() {
        // 链接页签的行是本地 MessageEntity，其余三格是服务端 ConvMediaItem——
        // 菜单那一层只认 ArchiveTarget，两条路必须给出同样的字段
        val fromServer = com.libeyond.imandroid.sdk.api.ConvMediaItem(
            convSeq = 7, sender = "u1", contentType = ContentType.FILE,
            content = "/uploads/a.pdf", fileName = "报表.pdf", fileSize = 120, timestamp = 99,
        ).toArchiveTarget()
        val fromLocal = com.libeyond.imandroid.data.db.MessageEntity(
            ownerUid = "me", convId = "c1", convSeq = 7, sender = "u1",
            contentType = ContentType.FILE, content = "/uploads/a.pdf",
            fileName = "报表.pdf", fileSize = 120, timestamp = 99,
        ).toArchiveTarget()
        assertEquals(fromLocal, fromServer)
    }

    @Test
    fun `服务端的空串要转成 null，不能原样带进转发`() {
        // ConvMediaItem 的字段缺省是空串（JSON 不带就是 ""），MessageEntity 那侧是 null。
        // 不归一的话，同一条消息从两个页签转发出去，一个带着 fileName=""、一个带 null
        val t = com.libeyond.imandroid.sdk.api.ConvMediaItem(
            convSeq = 3, sender = "u1", contentType = ContentType.IMAGE, content = "/uploads/x.jpg",
        ).toArchiveTarget()
        assertEquals(null, t.fileName)
        assertEquals(null, t.caption)
        assertEquals(null, t.fileSize)
    }

    @Test
    fun `转成待转发消息时只填转发真正会读的字段`() {
        val m = ArchiveTarget(
            convSeq = 5, sender = "u1", contentType = ContentType.IMAGE,
            content = "/uploads/x.jpg", caption = "白板",
        ).toMessageEntity(owner = "me", convId = "c1")
        assertEquals("me", m.ownerUid)
        assertEquals("c1", m.convId)
        assertEquals("/uploads/x.jpg", m.content)
        assertEquals("白板", m.caption)
        // 引用/转发来源/相册分组都不该被凭空编出来——它们要么随原消息走，要么由发送侧现算
        assertEquals(null, m.replyToConvSeq)
        assertEquals(null, m.forwardFrom)
        assertEquals(null, m.groupId)
    }
}
