package com.libeyond.imandroid.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.libeyond.imandroid.data.LanguageStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.ServiceLoader

/** 按资源 id 取文案。App 里走 Context（[ContextStringResolver]），JVM 单测走 ServiceLoader 注册的实现。 */
interface StringResolver {
    /** 该解析器实际取文案所用的语言（`en` / `zh-Hans`），拼日期等需要与文案同源判断语言时用。 */
    val languageTag: String
    fun get(@StringRes id: Int, args: Array<out Any>): String
    fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any>): String
}

/**
 * 非 Compose 代码（`data/` 纯函数、回调里的 toast、日志外的用户可见文案）取多语言文案的唯一入口。
 * Compose 里直接用 `stringResource`/`pluralStringResource`，不走这里。
 *
 * 不传 Context 是刻意的：`data/` 下大量纯函数有 JVM 单测（未接 Robolectric），逐个改签名吃 Context
 * 会把测试全部打碎。单测里 [resolver] 未安装时经 ServiceLoader 找到测试源集里的实现
 * （读 `values/i18n_strings.xml`，即中文源语言），既有的中文断言原样成立。
 */
object Str {
    @Volatile private var resolver: StringResolver? = null

    fun install(r: StringResolver) { resolver = r }

    private fun r(): StringResolver = resolver
        ?: ServiceLoader.load(StringResolver::class.java).firstOrNull()?.also { resolver = it }
        ?: error("Str 未安装：IMApp.onCreate 里应先调 Str.install")

    fun s(@StringRes id: Int, vararg args: Any): String = r().get(id, args)

    fun p(@PluralsRes id: Int, count: Int, vararg args: Any): String = r().plural(id, count, args)

    /** 当前取文案所用的语言（`en` / `zh-Hans`），与 [s] 同源——拼名单分隔符等按语言分支时用。 */
    val languageTag: String get() = r().languageTag

    /** `time.month_day` / `time.full_date` 的 month 参数：zh 传数字串，en 传英文缩写（见文案表 note）。 */
    fun monthArg(cal: Calendar): String =
        if (languageTag == "en") {
            SimpleDateFormat("MMM", Locale.ENGLISH).also { it.timeZone = cal.timeZone }.format(cal.time)
        } else {
            (cal.get(Calendar.MONTH) + 1).toString()
        }
}

/** 用跟随 [LanguageStore] 的 Context 取文案——API 33 以下没有 per-app locale，不能直接用 Application 的资源。 */
class ContextStringResolver(private val app: Context) : StringResolver {
    override val languageTag: String get() = LanguageStore.localeTag()
    @Volatile private var cached: Pair<String, Context>? = null

    private fun ctx(): Context {
        val tag = LanguageStore.localeTag()
        cached?.let { (t, c) -> if (t == tag) return c }
        return LanguageStore.wrap(app).also { cached = tag to it }
    }

    override fun get(id: Int, args: Array<out Any>): String =
        if (args.isEmpty()) ctx().getString(id) else ctx().getString(id, *args)

    override fun plural(id: Int, count: Int, args: Array<out Any>): String =
        ctx().resources.getQuantityString(id, count, *args)
}
