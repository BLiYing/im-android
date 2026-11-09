package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.BuildConfig
import com.libeyond.imandroid.data.AboutLinkValues
import com.libeyond.imandroid.data.AboutLinks
import com.libeyond.imandroid.data.AboutLinksStore
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.data.RegisterGate
import com.libeyond.imandroid.sdk.api.AboutLinksApi
import com.libeyond.imandroid.sdk.api.PublicConfigApi
import com.libeyond.imandroid.ui.components.LocalOpenLink
import kotlinx.coroutines.delay
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMErrorText
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMSecondaryButton
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.theme.IMAppTheme
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.ui.theme.IMThemeMode

/** 登录页的两个页签，与 Web 的「密码 / 扫码」结构对齐（扫码待 QR P1 接）。 */
enum class LoginTab { Password, Register }

/** 登录二步页的外部状态与回调（ticket 与请求在 `AppRoot`，这里只有展示所需）。 */
class LoginTwoStepUi(
    val failTick: Int,
    val onSubmit: (code: String) -> Unit,
    val onBack: () -> Unit,
    /** 用户开始改输入 / 切换验证方式：清掉上一次的红字。 */
    val onEdit: () -> Unit,
)

/**
 * 登录页。**纯展示 + 本地表单态**，网络动作全经参数注入（CODING_STYLE §7②）。
 *
 * @param devLoginEnabled 后端开了 `-dev-login` 时显示免密入口。端上无法探知，
 *   故由构建变体决定：debug 显示、release 不显示。
 */
@Composable
fun LoginScreen(
    onLogin: (username: String, password: String) -> Unit,
    onRegister: (username: String, password: String, nickname: String) -> Unit,
    onDevLogin: (username: String) -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    busy: Boolean,
    error: String,
    devLoginEnabled: Boolean,
    /** 底部法务链接拼不出地址（host 为空/非法）时回调：宿主显示「无法打开」。正常情况下链接走应用内浏览器（LocalOpenLink）。 */
    onLegalFailed: () -> Unit = {},
    /** 两步验证第二步：非 null 时同页换成二步内容（页签 / 免密登录 / 表单隐藏）；null = 普通登录。 */
    twoStep: LoginTwoStepUi? = null,
    /** 注册开关状态（[RegisterGate]，在 AppRoot：提交注册回 200007 也会写它）：明确关闭了注册的 host。 */
    registerClosedFor: String? = null,
    /** public-config 回包：宿主按 [RegisterGate.afterResult] 更新 [registerClosedFor]。 */
    onRegisterConfig: (requestedHost: String, enabled: Boolean) -> Unit = { _, _ -> },
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var tab by remember { mutableStateOf(LoginTab.Password) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var showHost by remember { mutableStateOf(false) }
    val registerClosed = rememberRegisterClosed(
        host, active = tab == LoginTab.Register && twoStep == null, registerClosedFor, onRegisterConfig,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.groupedBackground)
            .systemBarsPadding()
            // 输入法弹起时整页上移，否则输入框被键盘盖住（docs/UI_COLOR.md §4.2）
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = d.space4, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d.space3),
    ) {
        // —— Logo ——
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.MessageCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                colorFilter = ColorFilter.tint(c.onAccent),
            )
        }
        Text(
            text = "IM",
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
        )

        if (twoStep != null) {
            LoginTwoStepPane(busy = busy, error = error, failTick = twoStep.failTick, onSubmit = twoStep.onSubmit, onBack = twoStep.onBack, onEdit = twoStep.onEdit)
            return@Column
        }

        // —— 页签 ——
        Row(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(d.space2),
        ) {
            LoginTab.entries.forEach { t ->
                val selected = t == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(d.radiusCard))
                        .background(if (selected) c.accentSoft else c.cardBackground)
                        .clickable { tab = t }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(if (t == LoginTab.Password) R.string.login_button_login else R.string.login_tab_register),
                        color = if (selected) c.accent else c.textSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(d.space3),
        ) {
            // 登录态可填用户名或邮箱（原样发送、保持文本键盘）；注册态仍只收用户名
            IMTextField(
                username, { username = it },
                stringResource(if (tab == LoginTab.Password) R.string.login_identifier_label else R.string.settings_info_username),
                enabled = !busy,
            )
            IMTextField(password, { password = it }, stringResource(R.string.login_password), isPassword = true, enabled = !busy)
            if (tab == LoginTab.Register) {
                IMTextField(nickname, { nickname = it }, stringResource(R.string.login_nickname), enabled = !busy)
                Text(
                    stringResource(R.string.login_register_format_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textTertiary,
                )
            }

            if (tab == LoginTab.Register && registerClosed) {
                // 管理员暂停注册（S4）：提前告知并禁用提交；服务端 /register 照样回 200007 兜底
                Text(
                    stringResource(R.string.err_200007),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(d.radiusCard))
                        .background(c.dangerSoft)
                        .padding(d.space3),
                )
            }

            IMErrorText(error)

            if (tab == LoginTab.Password) {
                IMPrimaryButton(
                    text = stringResource(R.string.login_button_login),
                    onClick = { onLogin(username.trim(), password) },
                    enabled = username.isNotBlank() && password.isNotBlank(),
                    loading = busy,
                )
            } else {
                IMPrimaryButton(
                    text = stringResource(R.string.login_button_register),
                    onClick = { onRegister(username.trim(), password, nickname.trim()) },
                    enabled = !registerClosed && username.isNotBlank() && password.isNotBlank() && nickname.isNotBlank(),
                    loading = busy,
                )
            }

            if (devLoginEnabled && tab == LoginTab.Password) {
                IMSecondaryButton(
                    text = stringResource(R.string.login_dev_login),
                    onClick = { onDevLogin(username.trim()) },
                    enabled = username.isNotBlank() && !busy,
                )
            }

            // —— 服务器地址（开发期常要改，收在一个可折叠行里）——
            Text(
                text = if (showHost) stringResource(R.string.login_server_address) else stringResource(R.string.login_server_summary, host),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { showHost = !showHost },
            )
            if (showHost) {
                IMTextField(host, onHostChange, "host:port", enabled = !busy)
                Text(
                    "模拟器连宿主机用 10.0.2.2:8080（127.0.0.1 在模拟器里指模拟器自己）；" +
                        "真机填开发机内网 IP，并把该 IP 加进 network_security_config.xml",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textTertiary,
                )
            }
        }

        // 底部隐式同意提示（三端一致，ABOUT_LEGAL_DESIGN 批②）：点「登录/注册」即视为同意，无勾选框。
        // 二步页在上方已 return@Column，不显示。
        LoginAgreeFooter(host = host, onFailed = onLegalFailed)
    }
}

private const val LINKS_FETCH_DEBOUNCE_MS = 600L

/**
 * 注册页可见时按输入框里的地址读 `public-config`（S4），返回「该地址明确关闭了注册」。
 *
 * 防串台：结果按**发起时的 host** 记账（[RegisterGate]，状态在宿主），只有与当前 host 相同才生效——
 * 改地址时 LaunchedEffect 会取消在途请求，但就算回包抢在取消前落地，也只会记在旧 host 名下、不影响新地址。
 * 失败 / 字段缺失由 [PublicConfigApi.registerEnabled] 当作开放。
 */
@Composable
private fun rememberRegisterClosed(
    host: String,
    active: Boolean,
    closedFor: String?,
    onConfig: (requestedHost: String, enabled: Boolean) -> Unit,
): Boolean {
    val report by rememberUpdatedState(onConfig)
    LaunchedEffect(host, active) {
        if (!active || host.isBlank()) return@LaunchedEffect
        // host 输入框每敲一个字都会变：防抖后再拉
        delay(LINKS_FETCH_DEBOUNCE_MS)
        report(host, PublicConfigApi.anonymous(host, BuildConfig.USE_TLS).registerEnabled())
    }
    return RegisterGate.isClosed(closedFor, host)
}

/**
 * 「继续即表示同意《服务条款》和《隐私政策》」：复用 about.terms / about.privacy_policy 作链接文案
 * （中文《》在格式串里），两段用 Compose 链接注解可点，点击分流到对应法务页。
 */
@Composable
private fun LoginAgreeFooter(host: String, onFailed: () -> Unit) {
    val c = IMTheme.colors
    // 走应用内浏览器（LocalOpenLink，由外层 WebLinkHost 提供），不跳系统浏览器——与「关于我们」页一致。
    val openLink = LocalOpenLink.current
    // 链接由后台配置：host 输入框每敲一个字都会变，防抖后再匿名拉取；拉不到退回内置路径。
    var links by remember { mutableStateOf(AboutLinkValues.FALLBACK) }
    LaunchedEffect(host) {
        if (host.isBlank()) return@LaunchedEffect
        val key = AboutLinksStore.key(host, authenticated = false)
        // 换了 host：先退回该 host 的缓存/内置路径，别让上一个 host 的绝对链接残留到防抖窗口里
        links = AboutLinksStore.cached(key) ?: AboutLinkValues.FALLBACK
        delay(LINKS_FETCH_DEBOUNCE_MS)
        links = AboutLinksStore.load(key) { AboutLinksApi.anonymous(host, BuildConfig.USE_TLS).fetch() }
    }
    val open = { value: String ->
        val url = AboutLinks.resolve(value, host, BuildConfig.USE_TLS, LanguageStore.resolved)
        if (url != null && openLink != null) openLink(url) else onFailed()
    }
    val terms = stringResource(R.string.about_terms)
    val privacy = stringResource(R.string.about_privacy_policy)
    val full = stringResource(R.string.about_agree_footer, terms, privacy)
    val linkStyles = TextLinkStyles(style = SpanStyle(color = c.accent))
    val text = buildAnnotatedString {
        append(full)
        full.indexOf(terms).takeIf { it >= 0 }?.let {
            addLink(LinkAnnotation.Clickable("terms", linkStyles) { open(links.terms) }, it, it + terms.length)
        }
        full.indexOf(privacy).takeIf { it >= 0 }?.let {
            addLink(LinkAnnotation.Clickable("privacy", linkStyles) { open(links.privacy) }, it, it + privacy.length)
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
    )
}

@Preview(name = "登录·浅色", showBackground = true)
@Composable
private fun PreviewLoginLight() {
    IMAppTheme(mode = IMThemeMode.Light) {
        LoginScreen({ _, _ -> }, { _, _, _ -> }, {}, "10.0.2.2:8080", {}, false, "", true)
    }
}

@Preview(name = "登录·深色", showBackground = true)
@Composable
private fun PreviewLoginDark() {
    IMAppTheme(mode = IMThemeMode.Dark) {
        LoginScreen({ _, _ -> }, { _, _, _ -> }, {}, "10.0.2.2:8080", {}, false, "用户名或密码错误", true)
    }
}
