package com.libeyond.imandroid.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 文本层级——取值抄自 `../IMServer/docs/UI_COLOR.md` §3（三端同值，iOS 单位 pt / Web px / 本端 sp）。
 *
 * | 用途 | 字号 |
 * |---|---|
 * | 页面大标题 | 20 Semibold |
 * | 导航 / 抽屉标题 | 17 Semibold |
 * | 列表主标题 | 16～17 |
 * | 列表副标题 | 13～15，次要文字色 |
 * | 聊天正文 | 用户可调 14～22（走 `IMTheme.appearance.chatFontSize`，不在这里定）|
 * | 时间、状态、辅助标签 | 11～13 |
 *
 * **任何一端都不许固定正文高度**（§3 末）：字号变了行高要自动重算，
 * 故这里只给 fontSize/weight，lineHeight 交给 Compose 按字号推。
 */
val IMTypography = Typography(
    // 页面大标题 20 Semibold
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    // 导航 / 抽屉标题 17 Semibold
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
    ),
    // 列表主标题 16～17
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    // 正文（非聊天正文——聊天正文用户可调，走 appearance）
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    ),
    // 列表副标题 13～15
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    // 时间、状态、辅助标签 11～13
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
    ),
)
