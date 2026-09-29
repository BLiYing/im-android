package com.libeyond.imandroid.ui.theme

import com.libeyond.imandroid.data.ChatThemeId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主题配色逐值对 iOS `IMAppearance.m`（期望值由 iOS 的浮点与混色公式算出的十六进制）。
 * 派生主题把**公式**钉住；老主题把**手调值**钉住。
 */
class ChatPaletteTest {

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
    private fun all(p: ChatPalette) = listOf(p.accent, p.bubbleMe, p.wallpaperTop, p.wallpaperBottom).map(::hex)

    @Test
    fun `派生主题浅色 = 混白 0_58 与 0_91 0_84`() {
        assertEquals(listOf("#6ECC54", "#C2EAB7", "#F2FAF0", "#E8F7E4"), all(ChatPalettes.of(ChatThemeId.LIME, false)))
        assertEquals(listOf("#D34947", "#EDB3B2", "#FBEFEE", "#F8E2E2"), all(ChatPalettes.of(ChatThemeId.TITIAN, false)))
        assertEquals(listOf("#0D3A69", "#99ACC0", "#E9EDF2", "#D8DFE7"), all(ChatPalettes.of(ChatThemeId.PRUSSIAN_BLUE, false)))
    }

    @Test
    fun `派生主题深色 = 混黑 0_50 与 0_92 0_84`() {
        assertEquals(listOf("#6ECC54", "#37662A", "#091007", "#12210D"), all(ChatPalettes.of(ChatThemeId.LIME, true)))
        assertEquals(listOf("#EB5C20", "#762E10", "#130703", "#260F05"), all(ChatPalettes.of(ChatThemeId.HERMES_ORANGE, true)))
    }

    @Test
    fun `老主题手调值`() {
        assertEquals(listOf("#007AFF", "#C7EBFF", "#B8E8FA", "#94C7F0"), all(ChatPalettes.of(ChatThemeId.OCEAN, false)))
        assertEquals(listOf("#0A84FF", "#14456E", "#0A1F33", "#123D52"), all(ChatPalettes.of(ChatThemeId.OCEAN, true)))
        assertEquals(listOf("#AF52DE", "#E8D6FF", "#E0C9FA", "#B8DBFA"), all(ChatPalettes.of(ChatThemeId.VIOLET, false)))
        assertEquals(listOf("#3D9EE0", "#1A3D57", "#050D1A", "#0D2133"), all(ChatPalettes.of(ChatThemeId.MIDNIGHT, true)))
    }

    @Test
    fun `经典主题等于本端令牌表原值`() {
        val light = ChatPalettes.of(ChatThemeId.CLASSIC, false)
        assertEquals(LightIMColors.accent.value, androidx.compose.ui.graphics.Color(light.accent).value)
        assertEquals(LightIMColors.bubbleMe.value, androidx.compose.ui.graphics.Color(light.bubbleMe).value)
        assertEquals(DarkIMColors.wallpaperBottom.value, androidx.compose.ui.graphics.Color(ChatPalettes.of(ChatThemeId.CLASSIC, true).wallpaperBottom).value)
    }

    @Test
    fun `每个主题都给出不透明配色`() {
        for (t in ChatThemeId.entries) for (dark in listOf(false, true)) {
            ChatPalettes.of(t, dark).let { p ->
                listOf(p.accent, p.bubbleMe, p.wallpaperTop, p.wallpaperBottom).forEach {
                    assertEquals("$t dark=$dark", 0xFF, (it ushr 24) and 0xFF)
                }
            }
        }
    }

    @Test
    fun `套主题只换四样，其余令牌保持中性`() {
        val c = themedColors(LightIMColors, ChatThemeId.CHINA_RED, dark = false)
        assertEquals(LightIMColors.bubbleThem, c.bubbleThem)
        assertEquals(LightIMColors.datePillBackground, c.datePillBackground)
        assertEquals(LightIMColors.checkRead, c.checkRead)
        assertEquals("#C8161D", hex(c.accent.toArgbInt()))
        assertEquals(LightIMColors, themedColors(LightIMColors, ChatThemeId.CLASSIC, dark = false))
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
        ((alpha * 255).toInt() shl 24) or ((red * 255 + 0.5f).toInt() shl 16) or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()
}
