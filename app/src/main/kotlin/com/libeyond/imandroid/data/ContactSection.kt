package com.libeyond.imandroid.data

import java.text.Collator
import java.util.Locale

/**
 * 通讯录好友的**拼音 A–Z 分组**（对端 iOS `Modules/Contacts/IMContactSectionIndex.m`）。
 *
 * 判据逐条抄自那一侧（详见 `docs/UI_PARITY_IOS.md` §4.5.1）：
 * 多音姓氏覆盖表 → `#` 的三种情况 → 组间 A–Z 升序且 `#` 恒最后 → 组内按全串拼音升序 →
 * **空组不显示**。
 *
 * ### 首字母这一步刻意不与 iOS 同实现
 * iOS 用系统的 `CFStringTransform(kCFStringTransformMandarinLatin)`；Android 上没有等价物
 * （`android.icu.text.Transliterator` 要 API 29，本仓 `minSdk 26`，而且它在跑单测的桌面 JVM 上
 * 根本不存在——写了等于**判据没法测**）。这里用 [Collator] 与 26 个边界字比较取首字母：
 * JVM 与 Android 上它都走拼音序排序，得到的是**同一条不变式**（按拼音首字母分组），
 * 不是同一段实现（`SYMMETRY.md`：要一致的是不变式，不是代码形状）。
 *
 * **所以单测钉的是规则**（覆盖表 / `#` 三种情况 / 排序 / 空组不显示）；
 * "某个汉字归到哪个字母"这一步由真机核对。
 *
 * ### ⚠️ 非线程安全：只在**单线程**调用
 * [Collator]（Android 上转发到 `android.icu.text.RuleBasedCollator`）内部复用可变的
 * 迭代器/缓冲成员，本对象又持有它的**单例**。当前唯一调用点是 `ContactsScreen` 的组合期（主线程）。
 * 谁要把 [group] 挪到 `Dispatchers.Default`、或让选好友页也调它，**必须先解决这件事**
 * （加锁 / 每次 `collator.clone()` / 改成可实例化的类），否则会拿到脏排序键或概率性抛数组越界
 * ——而且单测抓不到。
 */
object ContactSection {

    /** 落在 A–Z 之外的一切（数字、emoji、俄日文、空名）。**恒排最后**。 */
    const val OTHER = "#"

    /**
     * 多音姓氏覆盖表——**逐条照抄 iOS 的 `polyphonicSurnames`**。
     *
     * 为什么需要它：拼音转换按**常用读音**转，而这些字作姓氏时读的是另一个音：
     * 曾（zēng，非 céng）· 仇（qiú，非 chóu）· 单（shàn，非 dān）· 解（xiè，非 jiě）……
     * 不覆盖的话「曾国藩」会掉进 C 组，用户在 Z 下面找不到他。
     *
     * **非穷举，覆盖高频姓**（iOS 那侧的注释原话）。要加就两端一起加。
     *
     * `internal` 是给单测用的：**必须直接断言这张表本身**。按"某个姓分到哪个字母"去断言是**假绿**
     * ——`Collator` 恰好也把「曾」排进 Z，删掉表里那一条测试照样通过（2026-09-09 变异验证当场撞见）。
     */
    internal val POLYPHONIC_SURNAMES = mapOf(
        '曾' to "Z", '仇' to "Q", '单' to "S", '解' to "X", '查' to "Z",
        '区' to "O", '乐' to "Y", '翟' to "Z", '覃' to "Q", '秘' to "B",
    )

    /**
     * 26 个字母的**边界字**：拼音序上每个字母段的第一个常用字。
     * 取首字母 = 找最后一个不大于该字的边界（二分同理，26 条直接线性扫即可）。
     */
    private val BOUNDARY_CHARS = listOf(
        '啊' to "A", '芭' to "B", '擦' to "C", '搭' to "D", '蛾' to "E", '发' to "F",
        '噶' to "G", '哈' to "H", '击' to "J", '喀' to "K", '垃' to "L", '妈' to "M",
        '拿' to "N", '哦' to "O", '啪' to "P", '期' to "Q", '然' to "R", '撒' to "S",
        '塌' to "T", '挖' to "W", '昔' to "X", '压' to "Y", '匝' to "Z",
    )

    private val collator: Collator by lazy { Collator.getInstance(Locale.CHINA) }

    /**
     * 一个显示名归到哪一组。**与 iOS `+sectionKeyForName:` 同判据、同顺序**：
     * 先查多音姓氏表（命中即覆盖），再取拼音首字母，最后落 A–Z 之外一律 [OTHER]。
     */
    fun sectionKeyOf(name: String): String {
        val first = name.trim().firstOrNull() ?: return OTHER
        POLYPHONIC_SURNAMES[first]?.let { return it }
        // 拉丁字母原样透传（大写）。**先剥重音**：iOS 的 `pinyinForName:` 起手就跑
        // `kCFStringTransformStripDiacritics`，所以 "Émile" 归 E；不剥的话本端会把它丢进 `#`
        val latin = stripDiacritic(first)
        if (latin in 'a'..'z' || latin in 'A'..'Z') return latin.uppercaseChar().toString()
        if (!isHan(first)) return OTHER
        return sectionCache.getOrPut(first) { latinInitialOf(first) }
    }

    /**
     * 按边界字扫出首字母：取**最后一个不大于目标字**的边界。
     *
     * ### 为什么把比较抽成参数
     * 「比第一个边界字还小」那一档在跑单测的**桌面 JVM 上根本走不到**
     * （实测扫遍 U+4E00–U+9FFF，没有一个汉字排在「啊」之前），可它正是真机上出事的那一档：
     * Android 的 ICU 把「阿」排在「啊」**之前**，于是「阿强」比到头一个边界都没命中，
     * 既不在 A 组也不在任何组，直接掉进 `#`（2026-09-09 实测）。
     * 用真 collator 写这条断言等于写一条**永远绿**的断言——所以比较由调用方注入。
     *
     * @param compareTo 目标字与某个边界字的比较结果，语义同 [Collator.compare]
     */
    internal fun initialByBoundaries(compareTo: (boundary: Char) -> Int): String {
        var key: String? = null
        for ((boundary, letter) in BOUNDARY_CHARS) {
            if (compareTo(boundary) >= 0) key = letter else break
        }
        // 排在第一个边界字之前 → 仍归 A：拼音序里 a 是最小的声母，能排到「啊」前的只能是 a 音字。
        // collator 不认识的生僻字实测排在**最后**（不是最前），故这一档不会误收生僻字。
        return key ?: "A"
    }

    private fun latinInitialOf(han: Char): String =
        initialByBoundaries { b -> collator.compare(han.toString(), b.toString()) }

    /**
     * 是不是汉字。
     *
     * 除基本区外还认**扩展 A** 与**兼容汉字**——它们在 collator 里同样按拼音排，
     * 不认的话这些字会一律掉进 `#`（iOS 那侧走 `CFStringTransform` 是认的）。
     * 扩展 B 及以后是代理对，[sectionKeyOf] 拿到的 `firstOrNull()` 只是高代理项，
     * 本端仍归 `#`——**这一条是已知差异**，记在 `docs/UI_PARITY_IOS.md` §4.5.1。
     */
    private fun isHan(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF

    /** 剥掉一个字符上的重音符（É→E）。非重音字符原样返回。 */
    private fun stripDiacritic(c: Char): Char {
        if (c.code < 0x80) return c
        val nfd = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
        return nfd.firstOrNull { !Character.isDefined(it) || Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            ?: c
    }

    /**
     * 组内排序键：**全串**的拼音序。iOS 那侧用的是带空格的全串拼音，
     * 注释点明"空格 ASCII 低所以天然姓在前"（li < lin < liu）。
     * 本端把这件事交给 [Collator]——它对中文就是拼音序，效果一致。
     *
     * ⚠️ 这个键**每人只能算一次**，见 [group] 里那段注释。
     */
    private fun sortKeyOf(name: String): java.text.CollationKey = collator.getCollationKey(name)

    /**
     * 首字母缓存：键是**首字**，不是整个名字。
     *
     * 通讯录里大量同姓（本仓压测号全是「用户NNNN」，2000 个人同一个「用」字），
     * 缓存命中率极高；表也长不大——最多就是用过的汉字个数。
     *
     * 用 `ConcurrentHashMap` **只是图它 `getOrPut` 不用自己加锁**，不代表本对象线程安全
     * ——真正的约束见类注释那条「只在单线程调用」。
     */
    private val sectionCache = java.util.concurrent.ConcurrentHashMap<Char, String>()

    /**
     * 把好友分成 A–Z（+ `#`）若干组。
     *
     * **只建出现过的桶**（空组不显示，同 iOS：没有预置 26 个字母）。
     * 组间 A–Z 升序、`#` 恒最后；组内按拼音升序，同拼音再按显示名不区分大小写兜底。
     */
    fun <T> group(items: List<T>, nameOf: (T) -> String): List<ContactGroup<T>> {
        // **先把键算完再排序**（decorate-sort-undecorate）。
        // 把 `sortKeyOf` 直接写进 comparator 是这段最贵的写法：Collator 会在**每次比较**里
        // 重建 CollationKey，2000 个好友就是 4 万多次——真机上聊到 ANR
        // （2026-09-09 实测，通讯录页 5 秒没画出来，系统弹 "IM isn't responding"；
        // 桌面 JVM 上同一份数据也要 100ms，那已经是掉帧的量级）。
        val decorated = items.map { Keyed(sectionKeyOf(nameOf(it)), sortKeyOf(nameOf(it)), nameOf(it), it) }
        return decorated.groupBy { it.section }
            .map { (key, rows) ->
                ContactGroup(
                    key,
                    // 只按排序键排，**没有大小写兜底那一档**——iOS 那侧有，是因为它的排序键是
                    // 一串小写拼音，"bob"/"Bob" 会撞成同一个键；本端的 CollationKey 是
                    // TERTIARY 强度，本来就区分大小写（实测 key(bob) != key(Bob)），
                    // 那一档永远走不到。而且真撞上时 CASE_INSENSITIVE_ORDER 恰好也回 0，
                    // 加了等于加一段**测不出来也起不了作用**的代码（2026-09-09 变异验证当场发现：
                    // 删掉它没有任何测试变红）。`sortedWith` 稳定排序保证同键项保持入参次序。
                    rows.sortedWith(compareBy { it.sortKey }).map { it.item },
                )
            }
            .sortedWith(compareBy({ it.key == OTHER }, { it.key }))
    }

    /** [group] 内部用：一个人 + 他那两个**只算一次**的键。 */
    private class Keyed<T>(
        val section: String,
        val sortKey: java.text.CollationKey,
        val name: String,
        val item: T,
    )

    /**
     * 每个字母组的**组头在列表里的下标**。
     *
     * 抽出来是因为它是 §4.5.1 判据里唯一一条纯算术、又最会漂的：
     * 只要以后在字母组之前多插一个 item（搜索框、横幅、把「好友」组标题加回来），
     * 索引尺就整体错位一格，而且**编译绿、单测绿，只有真机点 B 跳到 A 的最后一行**。
     * iOS 那侧把它抽成了 `friendsBaseSection` / `friendLocalSection:` 两个具名谓词，同一个理由。
     *
     * @param sizes 各组的人数（顺序即显示顺序）
     * @param leadingItems 字母组之前占了几个 item（本端顶部四个入口共占 1 个）
     */
    fun groupStartIndices(sizes: List<Int>, leadingItems: Int): List<Int> {
        var idx = leadingItems
        return sizes.map { n ->
            val start = idx
            idx += 1 + n // 组头 + 组内各行
            start
        }
    }

    /** 索引尺上要显示的字母。**没有好友时是空表**——那时整条索引尺不画（同 iOS 返回 nil）。 */
    fun <T> titlesOf(groups: List<ContactGroup<T>>): List<String> = groups.map { it.key }
}

/** 一个字母组。 */
data class ContactGroup<T>(val key: String, val items: List<T>)

/**
 * 好友行左滑出来的动作（对端 iOS `trailingSwipeActionsConfigurationForRowAtIndexPath:`）。
 *
 * 顺序即显示顺序。iOS 那侧数组第一个显示在最靠近屏幕边缘的一侧，本端从右往左排，语义一致。
 */
enum class FriendAction(val label: String, val destructive: Boolean = false) {
    /** 不可撤销 —— 本端**加了二次确认**，理由见 `docs/UI_PARITY_IOS.md` §4.5.1。 */
    Delete("删除", destructive = true),
    Block("拉黑"),
    Unblock("解除拉黑"),
}

object FriendActions {
    /**
     * 一行好友能滑出哪几个动作。
     *
     * 「拉黑 / 解除拉黑」是**同一格的两态**（iOS 那侧是同一个 action 换标题与背景色），
     * 所以这里恒是两项、第二项二选一——不是"有时两项有时三项"。
     */
    fun availableFor(blocked: Boolean): List<FriendAction> =
        listOf(FriendAction.Delete, if (blocked) FriendAction.Unblock else FriendAction.Block)
}
