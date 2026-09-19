package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.api.UserCard
import org.junit.Assert.assertEquals
import org.junit.Test

/** 进单聊信息页拉到的名片并回会话行：规则与 IMProgram / im-web 同口径。 */
class PeerCardMergeTest {

    private val row = ConversationEntity(
        ownerUid = "me", convId = "c1", peerUid = "u1", title = "旧昵称", avatarUrl = "/old.png", peerRemark = "",
    )

    @Test
    fun new_nickname_and_avatar_replace_old_ones() {
        val m = PeerCardMerge.merge(row, UserCard(userId = "u1", nickname = "新昵称", avatarUrl = "/new.png"))
        assertEquals("新昵称", m.title)
        assertEquals("/new.png", m.avatarUrl)
    }

    @Test
    fun remark_beats_nickname_and_is_written_back() {
        val m = PeerCardMerge.merge(row, UserCard(userId = "u1", nickname = "新昵称", remark = "老王"))
        assertEquals("老王", m.title)
        assertEquals("老王", m.peerRemark)
    }

    @Test
    fun cleared_remark_falls_back_to_nickname() {
        val withRemark = row.copy(title = "老王", peerRemark = "老王")
        val m = PeerCardMerge.merge(withRemark, UserCard(userId = "u1", nickname = "小李", remark = ""))
        assertEquals("小李", m.title)
        assertEquals("", m.peerRemark)
    }

    @Test
    fun empty_card_fields_keep_the_usable_old_values() {
        val m = PeerCardMerge.merge(row, UserCard(userId = "u1"))
        assertEquals("旧昵称", m.title)
        assertEquals("/old.png", m.avatarUrl)
    }
}
