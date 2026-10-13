package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ContactCardSend
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.screens.PickListScreen
import com.libeyond.imandroid.ui.screens.PickRow

/**
 * 选联系人发名片（iOS `IMFriendPickerViewController` + `maxSelection = 9`）：**多选、上限
 * [ContactCardSend.MAX_SELECTION]**，按**勾选顺序**交回（首张就是确认预览里那张卡）。
 *
 * 复用通用选人页 [PickListScreen]（搜索 + 拼音分组 + 索引尺 + 已选计数）；超限时它不再让勾。
 * 列表显示备注优先（本机私有），写进名片的公开名由发送侧另取。
 * 选完点「发送」只是进入二次确认（[com.libeyond.imandroid.ui.screens.ContactCardConfirmSheet]），不直接发出。
 */
@Composable
internal fun ContactCardPicker(
    friends: List<FriendEntry>,
    onCancel: () -> Unit,
    onConfirm: (List<FriendEntry>) -> Unit,
) {
    var picked by remember(friends) { mutableStateOf(emptyList<String>()) }
    val rows = remember(friends) {
        friends.map { PickRow(it.userId, DisplayName.ofFriend(it), it.avatarUrl, it.handle) }
    }
    PickListScreen(
        title = stringResource(R.string.contact_card_picker_title),
        rows = rows,
        selected = picked.toSet(),
        multi = true,
        limit = ContactCardSend.MAX_SELECTION,
        emptyText = stringResource(R.string.contact_card_picker_empty),
        confirmText = stringResource(R.string.common_send),
        onToggle = { id ->
            picked = if (id in picked) picked - id else ContactCardSend.normalize(picked + id)
        },
        onConfirm = {
            val byId = friends.associateBy { it.userId }
            val out = ContactCardSend.normalize(picked).mapNotNull(byId::get)
            if (out.isNotEmpty()) onConfirm(out)
        },
        onBack = onCancel,
    )
}
