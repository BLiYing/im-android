package com.libeyond.imandroid.ui

import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupMembersPage
import com.libeyond.imandroid.sdk.logging.IMLog

/** 拉一页群成员（从 `GroupInfoHost` 拆出，那个文件贴着 600 行硬闸——纯 IO 包装不值得占它的份额）。 */
internal suspend inline fun loadMore(
    client: IMClient,
    convId: String,
    cursor: String,
    onPage: (GroupMembersPage) -> Unit,
) {
    runCatching { client.groups.members(convId, cursor) }
        .onSuccess(onPage)
        .onFailure { IMLog.tag("IM.Group").w("group_members_failed") }
}
