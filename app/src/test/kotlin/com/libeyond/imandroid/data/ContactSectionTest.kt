package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通讯录 A–Z 分组的判据（对端 iOS `IMContactSectionIndex`，逐条见 `docs/UI_PARITY_IOS.md` §4.5.1）。
 *
 * ### 这里钉的是**规则**，不是"某个汉字属于哪个字母"
 * 首字母那一步两端实现不同（iOS 用 `CFStringTransform`，本端用 `Collator` 比边界字），
 * 而 `Collator` 的中文排序数据在跑单测的桌面 JVM 与真机上未必逐字一致。
 * 所以断言只挑**规则本身**：多音姓氏覆盖、`#` 的三种情况、组间/组内排序、空组不显示。
 * 「张三」到底落 Z 还是别处，由真机核对——把它写进单测只会得到一条在某台机器上假绿的断言。
 */
class ContactSectionTest {

    // ————————————————— 多音姓氏覆盖 —————————————————

    @Test
    fun `多音姓氏按姓氏读音分组，不跟常用读音走`() {
        // 不覆盖的话「曾国藩」会掉进 C（céng），用户在 Z 下面找不到他
        assertEquals("Z", ContactSection.sectionKeyOf("曾国藩"))
        assertEquals("Q", ContactSection.sectionKeyOf("仇老板"))
        assertEquals("S", ContactSection.sectionKeyOf("单雄信"))
        assertEquals("X", ContactSection.sectionKeyOf("解缙"))
    }

    @Test
    fun `覆盖表逐条与 iOS 一致——十条一个不少`() {
        // **直接断言这张表本身**，不按"某个姓分到哪个字母"去断言：后者是假绿——
        // Collator 恰好也把「曾」排进 Z，删掉表里那一条测试照样通过
        // （2026-09-09 变异验证当场撞见，正是本仓那条「没红过的断言不算数」）。
        assertEquals(
            mapOf(
                '曾' to "Z", '仇' to "Q", '单' to "S", '解' to "X", '查' to "Z",
                '区' to "O", '乐' to "Y", '翟' to "Z", '覃' to "Q", '秘' to "B",
            ),
            ContactSection.POLYPHONIC_SURNAMES,
        )
    }

    // ————————————————— `#` 的三种情况 —————————————————

    @Test
    fun `空名 数字 emoji 俄日文一律进井号组`() {
        assertEquals("#", ContactSection.sectionKeyOf(""))
        assertEquals("#", ContactSection.sectionKeyOf("   "))
        assertEquals("#", ContactSection.sectionKeyOf("123"))
        assertEquals("#", ContactSection.sectionKeyOf("🙂笑脸"))
        assertEquals("#", ContactSection.sectionKeyOf("Привет"))
        assertEquals("#", ContactSection.sectionKeyOf("ひらがな"))
    }

    @Test
    fun `排在所有边界字之前的汉字归 A，不是井号组`() {
        // 「阿强」曾经既不在 A、也不在任何组，直接掉进 #（2026-09-09 真机实测）：
        // 桌面 JVM 上「阿」≥ 边界字「啊」，Android 的 ICU 上却相反，比到头一个边界都没命中。
        //
        // 注入比较而不是用真 collator：这一档在桌面 JVM 上**走不到**
        // （扫遍 U+4E00–U+9FFF 没有汉字排在「啊」之前），拿真 collator 写就是一条永远绿的断言。
        assertEquals("A", ContactSection.initialByBoundaries { -1 })
        // 另一头：比所有边界字都大 → 落最后一段
        assertEquals("Z", ContactSection.initialByBoundaries { 1 })
    }

    @Test
    fun `拉丁字母原样透传并大写`() {
        assertEquals("A", ContactSection.sectionKeyOf("alice"))
        assertEquals("B", ContactSection.sectionKeyOf("Bob"))
    }

    // ————————————————— 组间排序 —————————————————

    @Test
    fun `井号组恒排最后，其余 A–Z 升序`() {
        val groups = ContactSection.group(listOf("曾三", "123", "alice", "秘书")) { it }
        // 覆盖表：曾→Z、秘→B；alice→A；123→#
        assertEquals(listOf("A", "B", "Z", "#"), ContactSection.titlesOf(groups))
    }

    @Test
    fun `空组不显示——没有预置 26 个字母`() {
        val groups = ContactSection.group(listOf("alice")) { it }
        assertEquals(listOf("A"), ContactSection.titlesOf(groups))
    }

    @Test
    fun `没有好友时索引尺是空表——那时整条不画`() {
        val groups = ContactSection.group(emptyList<String>()) { it }
        assertTrue(groups.isEmpty())
        assertTrue(ContactSection.titlesOf(groups).isEmpty())
    }

    // ————————————————— 组内排序 —————————————————

    @Test
    fun `组内按拼音升序`() {
        // 同一组里按拼音排：alice < amy < anna
        val g = ContactSection.group(listOf("anna", "alice", "amy")) { it }.single()
        assertEquals(listOf("alice", "amy", "anna"), g.items)
    }

    @Test
    fun `大小写不同的同名按拼音序排，次序确定`() {
        // 这条原本断言的是「大小写兜底那一档」，但那一档**根本走不到**：
        // 本端排序键是 TERTIARY 强度的 CollationKey，本来就区分大小写（实测 key(bob) != key(Bob)），
        // 而真撞键时 CASE_INSENSITIVE_ORDER 恰好回 0。所以那段代码已删，这里改成钉**实际次序**。
        val g = ContactSection.group(listOf("BOB", "bob", "Bob")) { it }.single()
        assertEquals(listOf("bob", "Bob", "BOB"), g.items)
    }

    @Test
    fun `重音拉丁按剥掉重音后的字母分组`() {
        // iOS 的 pinyinForName: 起手就 kCFStringTransformStripDiacritics，所以 Émile 归 E
        assertEquals("E", ContactSection.sectionKeyOf("Émile"))
        assertEquals("A", ContactSection.sectionKeyOf("Ångström"))
    }

    @Test
    fun `组头下标 = 前置项数 + 各组累加，插一个前置项就整体后移`() {
        // 这条判据纯算术、又最会漂：以后在字母组之前多插一个 item（搜索框/横幅），
        // 索引尺就整体错位一格，且编译绿、单测绿，只有真机点 B 跳到 A 的最后一行
        assertEquals(
            listOf(1, 4, 6),
            ContactSection.groupStartIndices(listOf(2, 1, 3), leadingItems = 1),
        )
        // 反例：前置项变成 2，全体后移一格
        assertEquals(
            listOf(2, 5, 7),
            ContactSection.groupStartIndices(listOf(2, 1, 3), leadingItems = 2),
        )
        assertEquals(emptyList<Int>(), ContactSection.groupStartIndices(emptyList(), leadingItems = 1))
    }

    // ————————————————— 左滑动作 —————————————————

    @Test
    fun `左滑恒两项，第二项随拉黑态二选一`() {
        // iOS 那侧是同一个 action 换标题与背景色，所以**不是**"有时两项有时三项"
        assertEquals(
            listOf(FriendAction.Delete, FriendAction.Block),
            FriendActions.availableFor(blocked = false),
        )
        assertEquals(
            listOf(FriendAction.Delete, FriendAction.Unblock),
            FriendActions.availableFor(blocked = true),
        )
    }

    @Test
    fun `左滑松手：过半吸开、否则弹回`() {
        // 写反的表现是"轻轻一碰整排按钮就弹出来"或"用力滑到底松手又缩回去"，
        // 两种都只能靠手感发现——所以判据抽成纯函数钉在这
        val max = 176f // 两格 × 88dp
        assertEquals(0f, com.libeyond.imandroid.ui.screens.settleOffset(-10f, max), 0f)
        assertEquals(0f, com.libeyond.imandroid.ui.screens.settleOffset(-87f, max), 0f)
        assertEquals(-max, com.libeyond.imandroid.ui.screens.settleOffset(-88f, max), 0f)
        assertEquals(-max, com.libeyond.imandroid.ui.screens.settleOffset(-176f, max), 0f)
        // 没滑过就别吸开
        assertEquals(0f, com.libeyond.imandroid.ui.screens.settleOffset(0f, max), 0f)
    }

    @Test
    fun `只有删除是破坏性的`() {
        assertTrue(FriendAction.Delete.destructive)
        assertTrue(!FriendAction.Block.destructive && !FriendAction.Unblock.destructive)
    }
}
