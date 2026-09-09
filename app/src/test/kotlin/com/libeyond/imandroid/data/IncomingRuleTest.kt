package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 入站消息的三态口径（`../IMServer/docs/PROTOCOL.md` §6.7「离线收敛」）。
 *
 * ### 这条判据是怎么被发现漏掉的
 * 2026-09-09 在 11 万条的大群里真机撞见：聊天页中间冒出一条
 * `{"op":"delete","conv_id":"g_75dfcc0e9dbb2108","target_conv_seq":110036,…}` 的绿气泡。
 * 裸 JSON 只是**症状**；真正的病是「本端从不应用 `msg_op` 事件行」——
 * 也就是**离线期间别人做的撤回 / 编辑 / 置顶 / 为所有人删除，重连后本端一律不生效**。
 * 那条事件行存在的全部意义就是给错过实时帧的端补课，本端却把它当成了一条聊天消息。
 *
 * 对端 im-web 在 `sdk/imSdk.ts` 的 `processIncoming` 开头就有这两个分支，
 * 本端一直没有——典型的「一条路改对了、对称兄弟没跟」（`SYMMETRY.md` 记的那 13.9%）。
 */
class IncomingRuleTest {

    @Test
    fun `普通消息照常落库`() {
        assertEquals(IncomingKind.Message, IncomingRule.kindOf(ContentType.TEXT, null))
        assertEquals(IncomingKind.Message, IncomingRule.kindOf(ContentType.IMAGE, 0L))
        // 撤回的墓碑**要**落库——它要显示成「你撤回了一条消息」，与 delete 不同
        assertEquals(IncomingKind.Message, IncomingRule.kindOf(ContentType.TEXT, 0L))
    }

    @Test
    fun `msg_op 事件行不是消息——它是协议管道`() {
        assertEquals(IncomingKind.ApplyMsgOp, IncomingRule.kindOf(ContentType.MSG_OP, null))
    }

    @Test
    fun `被为所有人删除的目标行物理移除，不显墓碑`() {
        // 与 recall 的区别就在这：recall 留墓碑，delete 什么都不留（PROTOCOL §6.7）
        assertEquals(IncomingKind.RemoveDeleted, IncomingRule.kindOf(ContentType.TEXT, 1_700_000_000_000L))
    }

    @Test
    fun `先判 msg_op 再判 deleted_at——反了那次删除就永远不被应用`() {
        // 事件行自己也是一条真实存在的行，也可能带 deleted_at。
        // 顺序反过来会把「删除事件」当成「被删的消息」直接扔掉，
        // 于是它要传达的那次删除永远不生效——静默，且只在离线端复现。
        assertEquals(
            IncomingKind.ApplyMsgOp,
            IncomingRule.kindOf(ContentType.MSG_OP, 1_700_000_000_000L),
        )
    }

    @Test
    fun `系统消息仍是消息——它要渲染成居中胶囊`() {
        // system 与 msg_op 在服务端的聚合过滤里是并列排除的（visibleContentFilter），
        // 但在**入库**这一步语义完全不同：系统消息是给人看的，msg_op 不是。
        assertEquals(IncomingKind.Message, IncomingRule.kindOf(ContentType.SYSTEM, null))
    }
}
