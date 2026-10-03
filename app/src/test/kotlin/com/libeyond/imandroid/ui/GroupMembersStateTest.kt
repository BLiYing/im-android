package com.libeyond.imandroid.ui

import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.GroupMembersPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupMembersStateTest {
    private fun page(vararg m: GroupMember) = GroupMembersPage(items = m.toList())

    /** 撤销最后一位管理员：在途那次重拉发在写之前（回旧角色），写之后的刷新被丢 → 旧数据留屏。 */
    @Test
    fun refreshDuringInFlightRefreshStillConvergesToLatest() = runTest {
        val owner = GroupMember(userId = "o", role = GroupMember.ROLE_OWNER)
        val adminOld = GroupMember(userId = "a", role = GroupMember.ROLE_ADMIN)
        val adminNow = GroupMember(userId = "a", role = GroupMember.ROLE_MEMBER)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val st = GroupMembersState(fetch = {
            val n = ++calls
            if (n == 1) {
                gate.await()
                page(owner, adminOld) // 发于撤销之前
            } else {
                page(owner, adminNow)
            }
        })
        launch { st.refresh() }          // 在途（旧快照）
        advanceUntilIdle()
        launch { st.refresh() }          // 撤销成功后的刷新：被在途挡住
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, st.members.count { it.role == GroupMember.ROLE_ADMIN })
        assertEquals(2, calls)
    }
}
