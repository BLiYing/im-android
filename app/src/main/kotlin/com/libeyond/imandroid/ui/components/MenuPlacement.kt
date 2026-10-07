package com.libeyond.imandroid.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize

/**
 * 长按菜单的竖向摆放结果（单位 dp）。
 *
 * @param shift 预览整体竖向平移（正 = 下移）：半截在屏外的先挪回屏内，两侧都放不下菜单时再让位。
 * @param scale 预览缩放（≤ 1）：比可用高度还高的预览（长图、长文）缩到能和菜单同屏。以 focus 中心为基准。
 * @param above 菜单在预览**上方**（靠底的那一项：预览不动、菜单翻上去）。
 * @param menuTop 菜单卡片上沿。
 * @param menuHeight 菜单卡片限高（放不下全部项时可滚动）。
 */
data class MenuPlacement(
    val shift: Float,
    val scale: Float,
    val above: Boolean,
    val menuTop: Float,
    val menuHeight: Float,
)

/**
 * 对齐 iOS 系统 `UIContextMenuInteraction` 的摆放（iOS 26 模拟器实测，2026-10-07）：
 * ① 预览半截出屏 → 整体挪回可见区；太高 → 缩小到能和菜单同屏；
 * ② 菜单**下方放得下放下方，否则上方放得下放上方**，预览原地不动——靠底的那一条不会被推走；
 * ③ 两侧都不够 → 放空间大的一侧，预览往反方向让到刚好放下；
 * ④ 菜单项顺序固定，不因翻到上方而倒序（iOS 26 实测：置顶在上、删除在下）。
 *
 * [canMove] = false（没有预览，如右上角 ＋ 的小菜单）时预览不挪不缩，只选上下。
 * [focusTop]/[focusBottom] 是真正浮起的那一块（气泡本体），**必须用未裁剪的窗口坐标**
 * （[unclippedBoundsInWindow]）——`boundsInWindow()` 会把屏外那截裁掉，就无从知道它出屏了多少。
 */
fun placeMenu(
    focusTop: Float,
    focusBottom: Float,
    safeTop: Float,
    safeBottom: Float,
    menuH: Float,
    minMenuH: Float,
    gap: Float,
    canMove: Boolean,
): MenuPlacement {
    val avail = (safeBottom - safeTop).coerceAtLeast(0f)
    val focusH = (focusBottom - focusTop).coerceAtLeast(0f)
    // 给菜单至少留出它的全高，但不超过可用高度的 45%（长菜单本身可滚动）
    val reserve = minOf(menuH, avail * MENU_RESERVE_FRACTION)
    val scale = if (canMove && focusH > 0f) ((avail - gap - reserve) / focusH).coerceIn(MIN_SCALE, 1f) else 1f
    val center = (focusTop + focusBottom) / 2f
    val h = focusH * scale
    var top = center - h / 2f
    var bottom = center + h / 2f
    var shift = 0f
    if (canMove) {
        if (top < safeTop) shift = safeTop - top else if (bottom > safeBottom) shift = safeBottom - bottom
        top += shift
        bottom += shift
    }
    val roomBelow = safeBottom - bottom - gap
    val roomAbove = top - gap - safeTop
    val above: Boolean
    when {
        roomBelow >= menuH -> above = false
        roomAbove >= menuH -> above = true
        roomBelow >= roomAbove -> {
            above = false
            if (canMove) {
                val d = minOf(menuH - roomBelow, (top - safeTop).coerceAtLeast(0f))
                shift -= d; top -= d; bottom -= d
            }
        }
        else -> {
            above = true
            if (canMove) {
                val d = minOf(menuH - roomAbove, (safeBottom - bottom).coerceAtLeast(0f))
                shift += d; top += d; bottom += d
            }
        }
    }
    val room = if (above) top - gap - safeTop else safeBottom - bottom - gap
    val menuHeight = minOf(menuH, room.coerceAtLeast(minOf(menuH, minMenuH)))
    val menuTop = if (above) top - gap - menuHeight else bottom + gap
    return MenuPlacement(shift, scale, above, menuTop, menuHeight)
}

/**
 * 这一项在窗口里的**完整**矩形（含被列表裁到屏外的那截）。长按菜单的锚点一律用它：
 * 预览据此与原位逐像素对齐，再由 [placeMenu] 挪回屏内（iOS 半出屏的图长按后整张滑进来）。
 */
fun LayoutCoordinates.unclippedBoundsInWindow(): Rect = Rect(positionInWindow(), size.toSize())

private const val MENU_RESERVE_FRACTION = 0.45f
private const val MIN_SCALE = 0.3f
