package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ProfileEdit
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMBioRow
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMKeyValueRow
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 编辑表单的四个输入框（状态由 Host 持有，本页只读写它）。 */
data class ProfileForm(
    val nickname: String = "",
    val username: String = "",
    val phone: String = "",
    val bio: String = "",
)

/**
 * 我的资料页（对齐 iOS `IMProfileEditViewController` 的**双态**设计）。
 *
 * 默认**只读**——大头像 + 昵称 + 在线态 + 信息卡；点右上角「编辑」才切到表单。
 * 从「我」页点头像进来的人多数只是想看一眼，直接给一屏输入框既突兀又容易误改。
 *
 * 只读态刻意**不显示内部 ID**（10 位随机数字）；标签已被个性签名取代（PROFILE_BIO_DESIGN，与 iOS 同）。
 * 信息卡顺序：个性签名（有才有）→ 用户名 → 手机号（有才有）。
 */
@Composable
fun MyProfileScreen(
    form: ProfileForm,
    onFormChange: (ProfileForm) -> Unit,
    displayName: String,
    handle: String,
    phone: String,
    bio: String,
    avatarUrl: String,
    seed: String,
    editing: Boolean,
    saving: Boolean,
    error: String,
    onEnterEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
    onPickAvatar: () -> Unit,
    onBack: () -> Unit,
    /** 非 null 时只读态底部显示「注销账号」组（仅本人资料页传；编辑态恒隐藏）。 */
    onDeleteAccount: (() -> Unit)? = null,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    // imePadding：键盘弹出时整页让出键盘高度，内部 verticalScroll 才有可滚的余量，个性签名（表单最后一格）不会被键盘盖住
    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding().imePadding()) {
        IMTopBar(
            title = if (editing) stringResource(R.string.settings_edit_profile) else stringResource(R.string.profile_title_view),
            onLeft = if (editing) onCancelEdit else onBack,
            leftDescription = if (editing) stringResource(R.string.common_cancel) else stringResource(R.string.common_back),
            actionText = if (editing) {
                if (saving) stringResource(R.string.common_saving) else stringResource(R.string.common_save)
            } else {
                stringResource(R.string.common_edit)
            },
            actionEnabled = !saving,
            onAction = if (editing) onSave else onEnterEdit,
        )

        // 左右边距**不加在这一层**：只读态的信息卡是 IMSettingsGroup，它自带 16 边距，
        // 外层再套一层就缩两次。编辑态的表单自己加。
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.space4 * 2))
            if (editing) {
                EditBody(form, onFormChange, avatarUrl, displayName, seed, onPickAvatar)
            } else {
                ReadonlyBody(displayName, handle, phone, bio, avatarUrl, seed, onDeleteAccount)
            }
            IMErrorText(error, Modifier.padding(top = d.space3, start = d.space4, end = d.space4))
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

@Composable
private fun ReadonlyBody(
    displayName: String,
    handle: String,
    phone: String,
    bio: String,
    avatarUrl: String,
    seed: String,
    onDeleteAccount: (() -> Unit)?,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IMAvatar(displayName, seed = seed, avatarUrl = avatarUrl, size = d.profileAvatar)
        Spacer(Modifier.height(14.dp))
        Text(displayName, color = c.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        // 本人页：自己永远在线，不必查 presence（与 iOS 同）
        Text(stringResource(R.string.common_online), color = c.textSecondary, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
    }
    IMSettingsGroup {
        // 没写签名 / 没填手机号就整行不占位（Telegram 同款），不显示「未设置」
        if (bio.isNotEmpty()) {
            IMBioRow(stringResource(R.string.profile_readonly_bio_label), bio)
            IMRowDivider(insetStart = d.space4)
        }
        IMKeyValueRow(
            stringResource(R.string.settings_info_username),
            handle.ifEmpty { stringResource(R.string.settings_info_not_set) },
            valueColor = c.accent,
        )
        if (phone.isNotEmpty()) {
            IMRowDivider(insetStart = d.space4)
            IMKeyValueRow(stringResource(R.string.profile_readonly_phone_label), phone, valueColor = c.accent)
        }
    }
    if (onDeleteAccount != null) {
        // 注销入口：单独一组红字居中行 + 脚注，与信息卡隔一个分组间距（DELETE_ACCOUNT_DESIGN §2）
        Spacer(Modifier.height(d.sectionGap))
        IMSettingsGroup {
            IMSettingsRow(
                title = stringResource(R.string.profile_delete_account_row),
                onClick = onDeleteAccount,
                destructive = true,
            )
        }
        IMSectionFooter(stringResource(R.string.profile_delete_account_footer))
    }
}

@Composable
private fun EditBody(
    form: ProfileForm,
    onChange: (ProfileForm) -> Unit,
    avatarUrl: String,
    displayName: String,
    seed: String,
    onPickAvatar: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(Modifier.fillMaxWidth().padding(horizontal = d.space4)) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(Modifier.clickable(onClick = onPickAvatar)) {
                IMAvatar(displayName, seed = seed, avatarUrl = avatarUrl, size = d.profileEditAvatar)
            }
            Box(
                modifier = Modifier
                    .size(d.cameraBadge)
                    .clip(CircleShape)
                    .background(c.accent)
                    .border(2.dp, c.groupedBackground, CircleShape)
                    .clickable(onClick = onPickAvatar),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = Lucide.Camera,
                    contentDescription = stringResource(R.string.profile_avatar_change_a11y),
                    modifier = Modifier.size(14.dp),
                    colorFilter = ColorFilter.tint(c.onAccent),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.profile_avatar_change_hint),
            color = c.accent,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable(onClick = onPickAvatar),
        )
    }

    Spacer(Modifier.height(d.space4))
    Field(stringResource(R.string.login_nickname), form.nickname) { onChange(form.copy(nickname = it)) }
    Spacer(Modifier.height(d.space3))
    // 用户名（公开句柄）与昵称是两回事：前者是别人搜到我的凭据、也是登录名，规则严格
    Field(
        label = stringResource(R.string.login_username_placeholder),
        value = form.username,
        // 不设 None 的话输入法会自动首字母大写，而服务端只收小写——
        // 用户敲完点保存才被拒，错在输入法，怪到用户头上
        keyboard = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Ascii,
        ),
    ) { onChange(form.copy(username = it)) }
    Spacer(Modifier.height(d.space3))
    Field(stringResource(R.string.settings_info_phone), form.phone, KeyboardOptions(keyboardType = KeyboardType.Phone)) {
        onChange(form.copy(phone = it))
    }
    Spacer(Modifier.height(d.space3))
    BioField(form.bio) { onChange(form.copy(bio = ProfileEdit.flattenBio(it))) }
    }
}

/** 个性签名：多行输入 + 右下角 n/70 计数（≥60 变 danger 色）。换行已在 onChange 里折成空格，超长在保存前校验（见 ProfileEdit.bioError）。 */
@Composable
private fun BioField(value: String, onValueChange: (String) -> Unit) {
    val c = IMTheme.colors
    val n = ProfileEdit.bioRunes(value)
    Box {
        IMTextField(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.profile_field_bio_label),
            singleLine = false,
        )
        Text(
            "$n/${ProfileEdit.MAX_BIO_RUNES}",
            color = if (n >= ProfileEdit.BIO_WARN_RUNES) c.danger else c.textSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onValueChange: (String) -> Unit,
) {
    IMTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        keyboard = keyboard,
    )
}
