package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 应用内横幅标题/正文格式化（NOTIFICATIONS_P1_DESIGN §1.2 表格）+ [InAppBannerStore] 的
 * 展示/替换/收起。标题与头像种子的口径必须与会话列表行 `ConversationRow` 一致——这几条测试
 * 就是把那份口径钉死，不是另起一套判据。
 */
class InAppBannerTest {

    private fun conv(
        id: String = "c1",
        group: Boolean = false,
        title: String = "张三",
        peer: String = "u_peer",
        lastContent: String = "在吗",
        lastFrom: String = "u_peer",
        lastContentType: String = ContentType.TEXT,
    ) = ConversationEntity(
        ownerUid = "me", convId = id, isGroup = group, title = title, peerUid = peer,
        lastContent = lastContent, lastFrom = lastFrom, lastContentType = lastContentType,
    )

    @Test
    fun `私聊预览开时正文就是消息摘要`() {
        val c = BannerFormat.of(conv(group = false, title = "张三", lastContent = "在吗"), previewOn = true, myUid = "me")
        assertEquals("张三", c.title)
        assertEquals("在吗", c.body)
        assertEquals("u_peer", c.avatarSeed)
    }

    @Test
    fun `群聊预览开时正文是「发送者：摘要」——复用会话列表的格式化函数`() {
        val c = BannerFormat.of(
            conv(id = "g1", group = true, title = "工作群", lastFrom = "u_peer", lastContent = "开会了"),
            previewOn = true,
            myUid = "me",
        )
        // 没有本地备注解析（nameOf 默认 `{ null }`）时退回服务端昵称快照/uid——ConversationPreview.of 的既有退化路径
        assertEquals("g1", c.avatarSeed) // 群聊头像种子 = convId，不是发送者
        assertEquals(Str.s(R.string.conv_list_sender_prefix, "u_peer", "开会了"), c.body)
    }

    @Test
    fun `该类型消息预览关时正文固定为「新消息」`() {
        val c = BannerFormat.of(conv(lastContent = "机密内容"), previewOn = false, myUid = "me")
        assertEquals(Str.s(R.string.notif_preview_hidden), c.body)
    }

    @Test
    fun `标题取不到时回退 convId——与会话列表行同一口径`() {
        val c = BannerFormat.of(conv(id = "c9", title = ""), previewOn = true, myUid = "me")
        assertEquals("c9", c.title)
    }

    @Test
    fun `系统通知会话的头像种子是系统 uid——IMAvatar 靠它自动换应用图标`() {
        val c = BannerFormat.of(
            conv(group = false, peer = DetailActions.SYSTEM_UID, title = "系统通知"),
            previewOn = true,
            myUid = "me",
        )
        assertEquals(DetailActions.SYSTEM_UID, c.avatarSeed)
    }

    // ——— InAppBannerStore ———

    @Test
    fun `show 原地替换并递增 token——不叠第二条`() {
        InAppBannerStore.dismiss()
        InAppBannerStore.show(BannerFormat.of(conv(id = "c1"), previewOn = true, myUid = "me"))
        val first = InAppBannerStore.current.value
        InAppBannerStore.show(BannerFormat.of(conv(id = "c2"), previewOn = true, myUid = "me"))
        val second = InAppBannerStore.current.value
        assertEquals("c2", second?.convId)
        assertEquals(false, first?.token == second?.token)
    }

    @Test
    fun `dismissIfShowing 只在会话匹配时收起`() {
        InAppBannerStore.show(BannerFormat.of(conv(id = "c1"), previewOn = true, myUid = "me"))
        InAppBannerStore.dismissIfShowing("c2")
        assertEquals("c1", InAppBannerStore.current.value?.convId)
        InAppBannerStore.dismissIfShowing("c1")
        assertNull(InAppBannerStore.current.value)
    }
}
