package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
import com.libeyond.imandroid.data.LoginMode
import com.libeyond.imandroid.data.PhoneAuthRules
import com.libeyond.imandroid.ui.LoginPane
import com.libeyond.imandroid.ui.LoginPhoneController
import com.libeyond.imandroid.sdk.api.AboutLinksApi
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
    /** 「长按 Logo ▸ 配置地址」点保存：更新 host 并立即持久化（不等登录）。默认只更新页面状态（预览用）。 */
    onHostSave: (String) -> Unit = onHostChange,
    /**
     * 手机号部分（PHONE_AUTH_DESIGN §4 / §5：页签、验证码登录、完善资料、忘记密码、用户名注册入口）。
     * null = 只有密码登录（预览用）。
     */
    phone: LoginPhoneController? = null,
    onSmsLogin: () -> Unit = {},
    onCompleteProfile: () -> Unit = {},
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var showHost by remember { mutableStateOf(false) }
    var showEndpoints by remember { mutableStateOf(false) }
    val canConfigEndpoints = !busy && twoStep == null
    val pane = phone?.pane ?: LoginPane.Main
    // 注册是否被暂停（S4）从同一份 public-config 推导（[LoginPhoneController.registerClosedFor]，按发起时的 host 记账）
    val registerClosed = phone?.registerClosed(host) == true
    // public-config（短信可用 / 地区 / 注册方式 / 注册开关）：按输入框里的地址防抖后读；进用户名注册页再读一次（开关可能刚改）
    if (phone != null) {
        LaunchedEffect(host, pane == LoginPane.Register) {
            if (host.isBlank()) return@LaunchedEffect
            delay(LINKS_FETCH_DEBOUNCE_MS)
            phone.loadConfig(host)
        }
        // 忘记密码重设成功：回密码登录页并预填手机号
        LaunchedEffect(phone.prefillIdent) { phone.prefillIdent?.let { username = it; password = ""; phone.prefillIdent = null } }
    }
    val tabs = PhoneAuthRules.loginTabs(phone?.config)
    val tab = phone?.shownTab ?: LoginMode.Password

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
        // —— Logo：长按配置服务器 / 音视频地址（对齐 iOS logoLongPressed:）——
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.accent)
                .pointerInput(canConfigEndpoints) {
                    if (canConfigEndpoints) detectTapGestures(onLongPress = { showEndpoints = true })
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.MessageCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                colorFilter = ColorFilter.tint(c.onAccent),
            )
        }
        // 子页（用户名注册 / 完善资料 / 忘记密码）自带标题，不再叠一个「IM」（稿 2-3 / 2-4）
        if (twoStep != null || pane == LoginPane.Main) {
            Text(
                text = "IM",
                style = MaterialTheme.typography.headlineSmall,
                color = c.textPrimary,
            )
        }

        if (showEndpoints && canConfigEndpoints) {
            LoginEndpointDialog(host = host, onDismiss = { showEndpoints = false }, onHostSave = onHostSave)
        }

        if (twoStep != null) {
            LoginTwoStepPane(busy = busy, error = error, failTick = twoStep.failTick, onSubmit = twoStep.onSubmit, onBack = twoStep.onBack, onEdit = twoStep.onEdit)
            return@Column
        }

        when {
            phone != null && pane == LoginPane.Register -> LoginRegisterPane(
                username, { username = it }, password, { password = it }, nickname, { nickname = it },
                registerClosed = registerClosed, error = error, busy = busy,
                onSubmit = { onRegister(username.trim(), password, nickname.trim()) },
                onBack = { phone.open(LoginPane.Main) },
            )
            phone != null && pane == LoginPane.Complete -> LoginCompletePane(phone, onCompleteProfile)
            phone != null && pane == LoginPane.Reset -> LoginResetPane(phone, host)
            else -> {
                // —— 页签：短信可用时「验证码登录 / 密码登录」，否则没有页签条 ——
                if (phone != null && PhoneAuthRules.showLoginTabBar(tabs)) LoginTabBar(tabs, tab, phone::selectTab)

                Column(
                    modifier = Modifier.widthIn(max = LOGIN_FORM_MAX).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(d.space3),
                ) {
                    if (phone != null && tab == LoginMode.Sms) {
                        LoginSmsForm(phone, host, onSmsLogin, notice = error)
                    } else {
                        // 登录名：用户名 / 邮箱 / 手机号，原样传给 /login、不预检（服务端解析，PROTOCOL §7.3）
                        // 一开始输入就钉住页签（配置晚到不再把人切到验证码登录）
                        IMTextField(username, { username = it; phone?.touch() }, stringResource(R.string.login_ident_placeholder), enabled = !busy)
                        IMTextField(password, { password = it; phone?.touch() }, stringResource(R.string.login_password), isPassword = true, enabled = !busy)
                        IMErrorText(error)
                        LoginNotice(error, phone?.notice.orEmpty())
                        IMPrimaryButton(
                            text = stringResource(R.string.login_button_login),
                            onClick = { phone?.notice = ""; onLogin(username.trim(), password) },
                            enabled = username.isNotBlank() && password.isNotBlank(),
                            loading = busy,
                        )
                        if (devLoginEnabled) {
                            IMSecondaryButton(
                                text = stringResource(R.string.login_dev_login),
                                onClick = { onDevLogin(username.trim()) },
                                enabled = username.isNotBlank() && !busy,
                            )
                        }
                    }
                    LoginLinksRow(
                        showForgot = phone != null && PhoneAuthRules.showForgotPassword(phone.config),
                        onForgot = { phone?.open(LoginPane.Reset) },
                        showRegister = phone != null && PhoneAuthRules.showUsernameRegister(phone.config),
                        onRegister = { phone?.open(LoginPane.Register) },
                    )

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
            }
        }

        // 底部隐式同意提示（三端一致，ABOUT_LEGAL_DESIGN 批②）：点「登录/注册」即视为同意，无勾选框。
        // 二步页在上方已 return@Column，不显示；注册 / 完善资料 / 忘记密码子页照常显示。
        LoginAgreeFooter(host = host, onFailed = onLegalFailed)
    }
}

private const val LINKS_FETCH_DEBOUNCE_MS = 600L

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
