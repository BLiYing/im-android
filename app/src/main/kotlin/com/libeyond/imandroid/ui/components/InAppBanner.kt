package com.libeyond.imandroid.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.BannerContent
import com.libeyond.imandroid.data.InAppBannerStore
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.delay

private val BANNER_AVATAR = 36.dp
private const val AUTO_DISMISS_MS = 4_000L
private const val SLIDE_MS = 250

/**
 * 应用内横幅宿主（NOTIFICATIONS_P1_DESIGN §1.2）。挂在主界面根 `Box` 最上层
 * （`ui/MainScreen.kt`——那里才拿得到 `openConv` 导航状态，AppRoot 那一层够不着，
 * 见 `ui/MainScreen.kt` 里的接线注释），订阅 [InAppBannerStore.current] 自己画自己收。
 *
 * @param onOpen 点击/唤起该会话——与点会话列表行同一条导航路径，由调用方决定"同一路径"具体是什么
 *   （`MainScreen` 传的是 `openConv = conversations.find { it.convId == convId }`）。
 */
@Composable
fun InAppBannerHost(onOpen: (String) -> Unit) {
    val current by InAppBannerStore.current.collectAsState()
    // AnimatedVisibility 退场那一下 current 已经是 null 了，得记住"最后一条"才能让滑出动画
    // 带着内容走，而不是瞬间变空白再滑走。
    var shown by remember { mutableStateOf<BannerContent?>(null) }
    LaunchedEffect(current) { if (current != null) shown = current }

    val animate = IMTheme.appearance.animationsEnabled
    AnimatedVisibility(
        visible = current != null,
        enter = if (animate) {
            slideInVertically(tween(SLIDE_MS)) { -it } + fadeIn(tween(SLIDE_MS))
        } else {
            fadeIn(tween(0))
        },
        exit = if (animate) {
            slideOutVertically(tween(SLIDE_MS)) { -it } + fadeOut(tween(SLIDE_MS))
        } else {
            fadeOut(tween(0))
        },
    ) {
        shown?.let { c ->
            BannerCard(
                content = c,
                onOpen = { InAppBannerStore.dismiss(); onOpen(c.convId) },
                onDismiss = InAppBannerStore::dismiss,
            )
        }
    }
}

@Composable
private fun BannerCard(content: BannerContent, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var pressed by remember { mutableStateOf(false) }
    // 计时器的"剩余时长"：token 变（新横幅）才重置为满 4 秒；按住时协程被取消，
    // 在 finally 里把已经过去的时长扣掉，松手后从这个值接着倒计时——不是从头算，是真正的"暂停"。
    var remainingMs by remember(content.token) { mutableLongStateOf(AUTO_DISMISS_MS) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    // 手势那颗 pointerInput 的 key 是 Unit（同 `passThroughTap` 类注释里记的坑）：新横幅到达时
    // `onOpen`/`onDismiss` 换了新闭包（绑的是新 content 的 convId），但手势协程不会重启，
    // 直接把新闭包塞给它只会在**第一次**按下时生效——用 rememberUpdatedState 转一手，
    // 让协程永远读到最新那份，不然点被替换后的横幅会打开上一条横幅的会话。
    val latestOpen by rememberUpdatedState(onOpen)

    LaunchedEffect(content.token, pressed) {
        if (pressed) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        try {
            delay(remainingMs)
            latestDismiss()
        } finally {
            remainingMs = (remainingMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
        }
    }

    Row(
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = d.space2, vertical = d.space1)
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(d.radiusBanner))
            .clip(RoundedCornerShape(d.radiusBanner))
            .background(c.cardBackground)
            .bannerGestures(onTap = { latestOpen() }, onSwipeUp = { latestDismiss() }, onPressedChange = { pressed = it })
            .padding(horizontal = d.space3, vertical = d.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(displayName = content.title, seed = content.avatarSeed, avatarUrl = content.avatarUrl, size = BANNER_AVATAR)
        Spacer(Modifier.width(d.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = content.title,
                color = c.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = content.body,
                color = c.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 横幅的手势：轻点 = 打开会话；上滑 = 收起；**按住不放期间调用方暂停计时**（[onPressedChange]）。
 *
 * 没用 `clickable` + `draggable` 叠加是因为两者都想在按下那一刻抢 down 事件，行为会打架
 * （同 [passThroughTap] 类注释里记的那类坑）；这里跟 down 到抬起，一路量纵向位移，
 * 抬起时按"位移是否够得上上滑阈值 / 是否近似没动过"二选一分派，介于两者之间（滑了一点但没到阈值）
 * 时**什么都不做**——只当作一次"摸了一下让计时器暂停"，与设计稿「按住不计时」一致。
 */
private fun Modifier.bannerGestures(
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
): Modifier = pointerInput(Unit) {
    val tapSlopPx = viewConfiguration.touchSlop
    val swipeThresholdPx = 48.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPressedChange(true)
        var totalDy = 0f
        var up: PointerInputChange? = null
        while (up == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            totalDy += change.positionChange().y
            if (!change.pressed) up = change
        }
        onPressedChange(false)
        when {
            -totalDy > swipeThresholdPx -> { up?.consume(); onSwipeUp() }
            kotlin.math.abs(totalDy) <= tapSlopPx -> { up?.consume(); onTap() }
            else -> Unit // 滑了一截但没到上滑阈值：既不是点也不是滑，什么都不做（计时器已经因为按住暂停过一次）
        }
    }
}
