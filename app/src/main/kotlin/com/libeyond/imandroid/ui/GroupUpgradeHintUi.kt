package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.GroupUpgradeHint
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo

/** 满员提示行要画的东西：两个上限 + 点一下的动作（复制群 ID 并吐司）。 */
class GroupUpgradeHintUi(val maxMembers: Int, val maxSupergroupMembers: Int, val onTap: () -> Unit)

/**
 * 群资料页的「成员已达上限」提示（见 [GroupUpgradeHint]）。`server-config` 每次进页拉一次；拉不到 = 不显示。
 * 点击复制**会话 ID**（升级走客服/管理端，要报这个 ID），不是个升级入口。
 */
@Composable
fun rememberGroupUpgradeHint(client: IMClient, info: GroupInfo, convId: String, onToast: (String) -> Unit): GroupUpgradeHintUi? {
    val clipboard = LocalClipboardManager.current
    val cfg by produceState<com.libeyond.imandroid.sdk.api.ServerConfig?>(null, convId) {
        value = runCatchingCancellable { client.conversationsApi.serverConfig() }.getOrNull()
    }
    val c = cfg ?: return null
    if (!GroupUpgradeHint.shows(info, c)) return null
    return GroupUpgradeHintUi(c.maxGroupMembers, c.maxSupergroupMembers) {
        clipboard.setText(AnnotatedString(convId))
        onToast(Str.s(R.string.chat_detail_group_id_copied))
    }
}
