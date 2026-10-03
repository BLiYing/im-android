package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.ServerConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupUpgradeHintTest {
    private val cfg = ServerConfig(maxGroupMembers = 500, supergroupEnabled = true, maxSupergroupMembers = 20000)
    private fun info(count: Int, isSuper: Boolean = false) = GroupInfo(convId = "g", memberCount = count, isSuper = isSuper)

    @Test fun `满员才显示`() {
        assertTrue(GroupUpgradeHint.shows(info(500), cfg))
        assertTrue(GroupUpgradeHint.shows(info(501), cfg))
        assertFalse(GroupUpgradeHint.shows(info(499), cfg))
    }

    @Test fun `超级群不显示`() = assertFalse(GroupUpgradeHint.shows(info(500, isSuper = true), cfg))

    @Test fun `配置没拉到或没开大群就不显示，绝不猜上限`() {
        assertFalse(GroupUpgradeHint.shows(info(500), null))
        assertFalse(GroupUpgradeHint.shows(info(500), cfg.copy(supergroupEnabled = false)))
        assertFalse(GroupUpgradeHint.shows(info(500), cfg.copy(maxGroupMembers = 0)))
    }
}
