package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 一条入站消息（`new_msg` / `sync_resp` 里的一条）该当成什么处理。
 *
 * **三端同一份口径**（`../IMServer/docs/PROTOCOL.md` §6.7「离线收敛」）：
 * 对端是 im-web `sdk/imSdk.ts` 的 `processIncoming` 开头那两个分支。
 * 要一致的是这三条，不是代码形状（`SYMMETRY.md`）：
 *
 * 1. `content_type=msg_op` 的**事件行**是协议管道，不是聊天内容——
 *    应用它的效果、**不入库为消息**、不作气泡渲染、不计未读、不作会话列表预览。
 * 2. `deleted_at > 0` 的目标行（「为所有人删除」）**物理移除**，不入库为可见消息。
 * 3. 其余照常落库。
 *
 * ### 判错的表现（都不会报错）
 * - 漏了第 1 条 → 聊天页里冒出一条 `{"op":"delete","conv_id":…}` 的裸 JSON 气泡，
 *   而且**离线期间发生的撤回/编辑/置顶/删除永远不生效**（那正是这条事件行存在的意义）。
 *   2026-09-09 在 11 万条的大群里真机撞见前半截；后半截是它更贵的那一半。
 * - 漏了第 2 条 → 别人「为所有人删除」的消息，在只拿到目标行、没拿到事件行时仍然显示
 *   （im-web 的注释里记着这条：「拿到目标行却漏事件行时会误显已删文件」）。
 */
enum class IncomingKind {
    /** 普通消息，照常落库。 */
    Message,

    /** `msg_op` 事件行：把 `content` 当自描述 JSON 解析并应用，不落库。 */
    ApplyMsgOp,

    /** 已被「为所有人删除」：物理移除本地那一条，不落库。 */
    RemoveDeleted,
}

object IncomingRule {

    /**
     * @param contentType 消息的 `content_type`
     * @param deletedAt   服务端下发的 `deleted_at`（软删标记；`null`/`0` = 没删）
     *
     * 顺序有讲究：**先判 `msg_op`**。事件行自己也可能带 `deleted_at`（它是一条真实存在的行），
     * 反过来判会把「删除事件」当成「被删的消息」，于是那次删除永远不被应用。
     */
    fun kindOf(contentType: String, deletedAt: Long?): IncomingKind = when {
        contentType == ContentType.MSG_OP -> IncomingKind.ApplyMsgOp
        (deletedAt ?: 0L) > 0L -> IncomingKind.RemoveDeleted
        else -> IncomingKind.Message
    }
}
