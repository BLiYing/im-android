package com.libeyond.imandroid.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 覆盖页的触摸屏蔽层：**只占住命中测试，不消费任何事件**。
 *
 * 覆盖页底下的聊天页还在组合里（`PushBase`），Compose 的命中测试会把上层没接住的触摸
 * （详情页的留白处）继续交给下层兄弟——不拦的话，点详情页空白会点到看不见的气泡上。
 * 兄弟节点之间，**上层只要有一个 pointerInput 被命中，命中测试就不再往下层兄弟走**，
 * 所以挂一个空转的 pointerInput 就够了，用不着 consume。
 *
 * ### ⚠️ 千万别在这里 consume（2026-09-17「聊天信息页划不动、有时能划」的根因）
 * 此前是 `awaitPointerEvent().changes.forEach { it.consume() }`，即在 **Main** 阶段全吞。
 * Main 阶段自内向外，列表先处理、本层后吞；而列表的拖动检测在位移**还没过 touch slop** 时，
 * 会再等一轮 **Final** 阶段看这次事件有没有被别人消费——被本层吞掉了，就判定"手势被抢"、整次拖动作废。
 * 于是：手指一下甩得快（首帧就过 slop）能滚，慢慢推、或按住后再划就纹丝不动，
 * 看起来就是「有时能划、有时划不动」，而且落在头部卡片上、宫格上都一样。
 * 回归测试：`androidTest/.../TouchShieldTest`（需真机）。
 */
internal fun Modifier.blockPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
