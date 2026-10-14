package com.libeyond.imandroid.ui

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 从 GroupInfoHost 拆出（那边贴 600 行硬闸）：群管理写操作的统一执行骨架 + 全员禁言开关。

/**
 * 调完写接口统一刷一次群资料 + 成员首页——服务端是权威，别本地猜新状态。对齐 iOS
 * 每个动作后都调 `loadGroupInfo`（含 `resetSuperMemberPaging`，同样重置到第一页，
 * 代价是丢弃已滚动加载的更深几页，两端同一取舍）。**与 `onLoadMoreMembers` 共享
 * `loading` 标志**：避免深分页时"触底加载更多"与"长按管理动作"并发写
 * `members`/`cursor`/`hasMore`，旧游标数据拼接出成员区间空洞（`/code-review` 抓出）。
 *
 * [silentOnSuccess]：开关类操作成功时界面自己已翻转，不再补一句「xx成功」（iOS 同；失败仍提示）。
 */
internal fun runGroupManage(
    scope: CoroutineScope,
    client: IMClient,
    convId: String,
    label: String,
    silentOnSuccess: Boolean,
    setToast: (String) -> Unit,
    setInfo: (GroupInfo) -> Unit,
    refreshMembers: suspend () -> Unit,
    block: suspend () -> Any?,
) {
    scope.launch {
        val r = runCatching { block() }
        r.onFailure { e ->
            val code = (e as? ApiException)?.code
            // 端上放行了但服务端拒——把码带出来，别只说「失败」
            setToast(
                if (code != null) Str.s(R.string.group_manage_op_failed_with_code, label, code)
                else Str.s(R.string.group_manage_op_failed, label),
            )
            IMLog.tag("IM.Group").w("group_manage_failed", "op" to label, "code" to (code ?: -1))
        }
        if (r.isSuccess && !silentOnSuccess) {
            setToast((r.getOrNull() as? ManageToast)?.text ?: Str.s(R.string.group_manage_op_succeeded, label))
        }
        runCatching { client.groups.info(convId) }.onSuccess(setInfo)
        refreshMembers()
    }
}

/** 全员禁言开关的文案与取值：-1 = 永久禁言，0 = 解除。 */
internal object MuteAllToggle {
    fun target(currentlyOn: Boolean): Long = if (currentlyOn) 0L else -1L
    fun label(currentlyOn: Boolean): String =
        Str.s(if (currentlyOn) R.string.group_manage_mute_all_off_label else R.string.group_manage_mute_all_on_label)

    /** 调接口；失败先 [rollback] 本地乐观值再抛回给 runManage 提示；无论成败都 [done]（解除防抖）。 */
    suspend fun apply(client: IMClient, convId: String, currentlyOn: Boolean, rollback: () -> Unit, done: () -> Unit): ManageToast {
        try {
            client.groups.setMuteAll(convId, target(currentlyOn))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            rollback()
            throw e
        } finally {
            done()
        }
        return ManageToast(
            Str.s(if (currentlyOn) R.string.group_manage_mute_all_off_toast else R.string.group_manage_mute_all_on_toast),
        )
    }
}
