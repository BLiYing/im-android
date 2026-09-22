package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.QrGroupCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫码/点链接解析结果 → 按钮态的纯映射。钉的是 iOS `IMQRModelsTests`、
 * Web `qr.test.ts` 同一条判据表，三端应该给出同一个分支。
 */
class QrActionsTest {

    @Test
    fun `名片码 relation 到按钮态`() {
        assertEquals(QrUserAction.SELF, qrUserActionFor("self"))
        assertEquals(QrUserAction.MESSAGE, qrUserActionFor("friend"))
        assertEquals(QrUserAction.BLOCKED, qrUserActionFor("blocked"))
        assertEquals(QrUserAction.ADD, qrUserActionFor("stranger"))
        assertEquals(QrUserAction.ADD, qrUserActionFor(""))
    }

    @Test
    fun `名片码主按钮文案`() {
        assertEquals("发消息", qrUserActionLabel(QrUserAction.MESSAGE))
        assertEquals("查看我的资料", qrUserActionLabel(QrUserAction.SELF))
        assertEquals("查看资料", qrUserActionLabel(QrUserAction.BLOCKED))
        assertEquals("添加到通讯录", qrUserActionLabel(QrUserAction.ADD))
    }

    @Test
    fun `名片码 relation 到资料页 knownRelation——self 与 blocked 两端取值本就相同，friend 要转成 accepted`() {
        assertEquals(MemberProfile.RELATION_SELF, qrRelationToProfileRelation("self"))
        assertEquals(FriendEntry.ACCEPTED, qrRelationToProfileRelation("friend"))
        assertEquals(FriendEntry.BLOCKED, qrRelationToProfileRelation("blocked"))
        assertEquals("", qrRelationToProfileRelation("stranger"))
    }

    private fun group(
        joined: Boolean = false,
        joinable: Boolean = true,
        reason: String = "",
    ) = QrGroupCard(groupId = "g1", name = "测试群", joined = joined, joinable = joinable, reason = reason)

    @Test
    fun `群码准入判定到按钮态`() {
        assertEquals(QrGroupAction.DISABLED, qrGroupActionFor(null))
        assertEquals(QrGroupAction.ENTER, qrGroupActionFor(group(joined = true)))
        assertEquals(QrGroupAction.DISABLED, qrGroupActionFor(group(joinable = false, reason = "full")))
        assertEquals(QrGroupAction.DISABLED, qrGroupActionFor(group(joinable = false, reason = "banned")))
        assertEquals(QrGroupAction.DISABLED, qrGroupActionFor(group(joinable = false, reason = "invite_revoked")))
        assertEquals(QrGroupAction.APPLY, qrGroupActionFor(group(reason = "approval")))
        assertEquals(QrGroupAction.JOIN, qrGroupActionFor(group()))
    }

    @Test
    fun `群码主按钮文案`() {
        assertEquals("进入群聊", qrGroupActionLabel(QrGroupAction.ENTER))
        assertEquals("申请加入", qrGroupActionLabel(QrGroupAction.APPLY))
        assertEquals("无法加入", qrGroupActionLabel(QrGroupAction.DISABLED))
        assertEquals("加入群聊", qrGroupActionLabel(QrGroupAction.JOIN))
    }

    @Test
    fun `已在群与可直接加入都没有说明文案，其余四种各自一句`() {
        assertNull(qrGroupActionNote(group(joined = true)))
        assertNull(qrGroupActionNote(group()))
        assertEquals("群成员已达上限，暂时无法加入", qrGroupActionNote(group(joinable = false, reason = "full")))
        assertEquals("你已被移出该群，暂时或永久不可加入", qrGroupActionNote(group(joinable = false, reason = "banned")))
        assertEquals("该群已改为仅管理员可邀请，此邀请已失效", qrGroupActionNote(group(joinable = false, reason = "invite_revoked")))
        assertEquals("该群需管理员审批", qrGroupActionNote(group(reason = "approval")))
    }

    @Test
    fun `一图多码候选列表摘要`() {
        assertEquals("名片码（本应用）", qrScanLabelFor("http://a.com/q/u/tok"))
        assertEquals("群二维码（本应用）", qrScanLabelFor("http://a.com/q/g/tok"))
        assertEquals("登录码（本应用）", qrScanLabelFor("http://a.com/q/l/tok"))
        assertEquals("网址 · evil.com", qrScanLabelFor("https://evil.com/x"))
        assertEquals("（空）", qrScanLabelFor("   "))
        val long = "一段很长很长很长很长很长很长很长很长的纯文本内容超过二十个字符"
        val label = qrScanLabelFor(long)
        assertEquals(long.take(20) + "…", label)
    }

    @Test
    fun `外来码域名判定——只认 http(s)，供二次确认高亮`() {
        assertEquals("evil.com", qrUnknownDomain("https://evil.com/path?x=1"))
        assertEquals("a.com", qrUnknownDomain("  HTTP://a.com "))
        assertNull(qrUnknownDomain("weixin://dl/business"))
        assertNull(qrUnknownDomain("这是一段纯文本"))
        assertNull(qrUnknownDomain(""))
    }
}
