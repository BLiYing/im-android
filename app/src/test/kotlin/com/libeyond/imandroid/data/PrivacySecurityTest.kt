package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「隐私与安全」页结构逐行对照 iOS `IMPrivacySecurityViewController.buildGroups`（Web `PrivacySecurityPanel` 同）。
 * 期望值是从 iOS 源码抄来的——改这张表要三端一起改，测试红了先去看 iOS 变没变。
 */
class PrivacySecurityTest {

    private val groups = PrivacySecurity.groups

    @Test
    fun `五组的组头与组尾`() {
        assertEquals(listOf("", "账号保护", "会话隐私", "谁能看到", "数据"), groups.map { it.header })
        assertEquals(
            listOf(
                "已屏蔽的用户不能给你发消息，也看不到你的资料。",
                "绑定第二因子后，即使密码泄露也无法登录你的账号。",
                "为你开始的每个新会话默认开启阅后自删。",
                "这些设置决定他人在你的资料页看到多少。",
                "",
            ),
            groups.map { it.footer },
        )
    }

    @Test
    fun `各行标题与顺序`() {
        assertEquals(
            listOf(
                listOf("已屏蔽的用户", "修改密码"),
                listOf("两步验证", "通行密钥", "邮箱登录"),
                listOf("自动删除消息"),
                listOf("手机号码", "上次上线", "头像", "个人简介", "生日"),
                listOf("清除所有对话", "导出我的数据"),
            ),
            groups.map { g -> g.rows.map { it.title } },
        )
    }

    @Test
    fun `占位行的右值`() {
        val values = groups.flatMap { it.rows }.associate { it.title to it.value }
        assertEquals("关闭", values["两步验证"])
        assertEquals("关闭", values["通行密钥"])
        assertEquals("", values["邮箱登录"])
        assertEquals("关闭", values["自动删除消息"])
        assertEquals(listOf("我的联系人", "我的联系人", "所有人", "所有人", "我的联系人"), groups[3].rows.map { it.value })
        assertEquals("", values["清除所有对话"])
    }

    /** 只有第一组两行是真功能；其余全灰置。把某个占位行误接成活行（或反过来）都会在这里红。 */
    @Test
    fun `只有已屏蔽与修改密码是活行`() {
        val first = groups.first().rows
        assertEquals(listOf(PrivacyAction.Blocked, PrivacyAction.ChangePassword), first.map { it.action })
        assertTrue(first.none { it.isPlaceholder })
        assertTrue(groups.drop(1).flatMap { it.rows }.all { it.isPlaceholder && it.action == PrivacyAction.ComingSoon })
    }

    @Test
    fun `图标底色逐行对齐 iOS system 色`() {
        assertEquals(
            listOf(
                PrivacyTint.Red, PrivacyTint.Blue,
                PrivacyTint.Gray, PrivacyTint.Purple, PrivacyTint.Teal,
                PrivacyTint.Orange,
                PrivacyTint.Green, PrivacyTint.Blue, PrivacyTint.Purple, PrivacyTint.Yellow, PrivacyTint.Pink,
                PrivacyTint.Gray, PrivacyTint.Blue,
            ),
            groups.flatMap { g -> g.rows.map { it.tint } },
        )
    }

    @Test
    fun `黑名单计数：有才显数字`() {
        assertEquals("", PrivacySecurity.blockedCountLabel(null))
        assertEquals("", PrivacySecurity.blockedCountLabel(0))
        assertEquals("1", PrivacySecurity.blockedCountLabel(1))
        assertEquals("36", PrivacySecurity.blockedCountLabel(36))
    }
}
