package com.libeyond.imandroid.ui.screens

import com.libeyond.imandroid.data.db.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 会话列表预览行右端「有没有未读标记」的判据：无标记时不占间距，时间位置不依赖它（时间在名称行）。 */
class UnreadMarkTest {
    private val base = ConversationEntity(ownerUid = "me", convId = "c1")

    @Test
    fun `有数字徽标或标未读圆点才画`() {
        assertEquals(false, UnreadMark.shown(base))
        assertEquals(true, UnreadMark.shown(base.copy(unread = 3)))
        assertEquals(true, UnreadMark.shown(base.copy(markedUnread = true)))
    }
}
