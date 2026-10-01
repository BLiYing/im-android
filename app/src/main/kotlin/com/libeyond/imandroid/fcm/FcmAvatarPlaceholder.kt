package com.libeyond.imandroid.fcm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.ui.components.avatarColorForSeed

/** 通知大图标的边长（像素）——真实头像（Coil `CircleCropTransformation`）与占位图共用同一尺寸。 */
const val FCM_AVATAR_PX = 192

/**
 * 通知没有可用头像时画的首字母占位图（PUSH_M5_DESIGN §3.6，同 iOS 通知扩展 `IMAvatarPlaceholder`）：
 * 取字规则、取色算法都和 App 内头像圈（[com.libeyond.imandroid.ui.components.IMAvatar]）一样，
 * **不交给系统去生成**——Android 对会话通知没有大图标时会自己画一个「名字第一个字」的圆，
 * 与本 App「中文取末字/英文取首字母」的规则不一致（2026-10-01 真机实测：群消息通知显示成
 * 发送人名字第一个字，而不是本 App 任何地方会显示的样子）。
 *
 * **画成圆形**（透明底 + 圆形色块），不是实心方图：Android 不会把通知大图标自动裁圆——真实头像
 * 正因为这样才在 [FcmMessagingService] 里显式套了 `CircleCropTransformation`；占位图不裁圆的话，
 * 同一条通知列表里会一半圆一半方，一眼就看出不对。
 *
 * [name] 为空（服务端老格式/异常 payload 没给标题）时退回用 [seed] 取字，见 [fcmAvatarPlaceholderText]。
 */
fun fcmAvatarPlaceholder(name: String, seed: String, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = avatarColorForSeed(seed).toArgb() }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bg)
    val text = fcmAvatarPlaceholderText(name, seed)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sizePx * 0.4f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val textY = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(text, sizePx / 2f, textY, textPaint)
    return bitmap
}

/**
 * 占位图里画的字——从 [fcmAvatarPlaceholder] 拆出来是纯函数，不碰 `android.graphics`，能直接 JVM 单测
 * （画位图本身要 Robolectric，这个仓库没接，所以只测这一步决策逻辑，位图效果走真机肉眼看）。
 *
 * [name] 为空（服务端老格式/异常 payload 没给标题）时退回用 [seed] 取字——同 iOS `IMAvatarPlaceholderPNG`
 * 同一处兜底：`DisplayName.initials("")` 按新规则返回空串，不退回会画出一个没有字的纯色圆，看不出任何信息。
 */
internal fun fcmAvatarPlaceholderText(name: String, seed: String): String =
    DisplayName.initials(name.ifBlank { seed })
