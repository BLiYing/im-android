package com.libeyond.imandroid.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.libeyond.imandroid.sdk.logging.IMLog
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 界面语言偏好（存 SharedPreferences `pref`）。三个值与 iOS `IMLanguagePref*` / Web `LangPref` 同名同义——
 * 不是为了跨端同步（本设置纯本地，不上服务端），是为了三端文档/代码对得上号。 */
enum class LanguagePref(val wire: String) {
    SYSTEM("system"),
    ZH_HANS("zh-Hans"),
    EN("en"),
    ;

    companion object {
        fun fromWire(wire: String?): LanguagePref = entries.firstOrNull { it.wire == wire } ?: SYSTEM
    }
}

/** 解析后的具体语言（`LanguagePref.SYSTEM` 不是一个可用的界面语言，必须先解析）。 */
enum class ResolvedLanguage(val wire: String) { ZH_HANS("zh-Hans"), EN("en") }

/**
 * App 内界面语言偏好的**唯一持有者**（对齐 iOS `IMLocalization` / Web `src/i18n/index.ts`）。
 *
 * 文案来自 `IMServer/docs/i18n/strings.json` 生成的 `values(-en)/i18n_strings.xml`；Compose 用
 * `stringResource`，非 Compose 代码用 `i18n/Str`。资源切换两条腿：API 33+ 交给平台 `LocaleManager`
 * （[applyToResources]）；所有版本的 `MainActivity` 都用 [wrap] 包 base context，API 33 以下切换时
 * 由 MainActivity 自己 `recreate()`。
 *
 * 用普通 SharedPreferences（与 `SessionStore` 同一手法，见其注释：本设置不涉密，不需要加密层），
 * 但额外包一层 [MutableStateFlow]——「我」页的当前值展示与 RtcCall 的实时生效要读同一份、
 * 改了立刻两处都看得见，不能各拉各的（仿 `DownloadSettingsStore` 的响应式手法，去掉那套服务端版本号，
 * 因为这是纯本地设置，没有「乱序应答」这回事）。
 */
object LanguageStore {

    private const val PREFS = "im_language"
    private const val KEY_PREF = "pref"

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var appContext: Context
    private val _pref = MutableStateFlow(LanguagePref.SYSTEM)

    /** 「我」页的语言行订阅这个当展示值；语言设置页也用它初始化选中项。 */
    val pref: StateFlow<LanguagePref> = _pref.asStateFlow()

    /** RtcCall 读这个喂给 Kit：已经解析过「跟随系统」，永远是一个具体语言。 */
    val resolved: ResolvedLanguage get() = resolve(_pref.value, systemLanguage())

    /**
     * **设备系统**语言主码。不能读 `Locale.getDefault()`：API 33+ 一旦 [applyToResources] 把 App 级语言设成 en，
     * 进程的默认 Locale 就跟着变成 en——「跟随系统」再读它，永远解析成上次选的语言（2026-10-06 OPPO 复现：
     * 选 English → 改回跟随系统，界面仍是英文）。`Resources.getSystem()` 的配置不受 App 级语言覆盖。
     */
    internal fun systemLanguage(): String {
        // API 33+：`LocaleManager.systemLocales` 才是权威（不受 App 级覆盖影响）；
        // 实测 `Resources.getSystem().configuration` 在 OPPO 上仍会带出 App 级语言。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ::appContext.isInitialized) {
            runCatching {
                val l = appContext.getSystemService(LocaleManager::class.java).systemLocales
                if (!l.isEmpty) return l[0].language
            }
        }
        val l = android.content.res.Resources.getSystem().configuration.locales
        return if (l.isEmpty) Locale.getDefault().language else l[0].language
    }

    /** [IMApp.onCreate] 里调一次即可；不必等登录——「我」页与 RtcCall 都可能在登录前后读它。 */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _pref.value = LanguagePref.fromWire(prefs.getString(KEY_PREF, null))
        applyToResources()
    }

    fun setPref(next: LanguagePref) {
        if (_pref.value == next) return
        prefs.edit().putString(KEY_PREF, next.wire).apply()
        _pref.value = next
        applyToResources()
    }

    /**
     * 让 `values/` ↔ `values-en/` 资源真的切换：喂**已解析出的具体语言**给平台的
     * `LocaleManager`，不直接把原始 `system` 偏好扔给系统——与上面「不假设中文」同一条不变式：
     * 设备是日语/法语时，系统自己的资源回退链会落回默认 `values/`（即中文），那就悄悄违反了这条不变式。
     *
     * **直接调平台 `LocaleManager`，不用 `AppCompatDelegate.setApplicationLocales`**（2026-09-27
     * 真机实测踩坑记录，Android 15/API 35 OPPO 机型）：`AppCompatDelegate` 那条路调用不抛异常、
     * 但完全不生效——`adb shell cmd locale get-app-locales` 恒为空，
     * `AppCompatDelegate.getApplicationLocales()` 调用后立刻读回也是空。反倒是 `adb shell cmd locale
     * set-app-locales` 这个直接调平台 API 的 shell 命令能立即生效（Activity 随之重建、文案立刻切换）。
     * 猜测原因：本端 `MainActivity` 是 `ComponentActivity`，全仓没有任何 `AppCompatActivity`，而
     * `AppCompatDelegate` 的 API 33+ 路径疑似仍依赖它内部的 `AppCompatActivity`/生命周期回调才能拿到
     * 有效引用——没有验证 androidx 源码，只确认了现象；改用平台 `LocaleManager` 直连后同一台真机上
     * 验证有效。API 33 以下没有 `LocaleManager`，由 MainActivity 用 [wrap] + `recreate()` 兜住。
     */
    /** 当前界面语言的 BCP-47 标签（资源限定符 `values-en` / 默认 `values` 即简体中文）。 */
    fun localeTag(): String = if (resolved == ResolvedLanguage.EN) "en" else "zh-Hans"

    /** 返回按当前界面语言配置的 Context（MainActivity.attachBaseContext 与 `Str` 共用，两边永远一致）。 */
    fun wrap(base: Context): Context {
        if (!::prefs.isInitialized) init(base)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(localeTag()))
        return base.createConfigurationContext(config)
    }

    private fun applyToResources() {
        val tag = localeTag()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val localeManager = appContext.getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = LocaleList.forLanguageTags(tag)
            IMLog.tag("IM.Lang").i("apply_locales", "tag" to tag)
        } catch (e: Throwable) {
            IMLog.tag("IM.Lang").e("apply_locales_failed", e, "tag" to tag)
        }
    }

    /**
     * 「跟随系统」解析（纯函数，单测钉住）：按系统语言主码，`zh` 前缀归简体，
     * 其余一律回落英文——**不假设中文**（照抄 iOS `IMLocalization.resolveLanguageForPreference:` 的判据，
     * 与 Web `resolveLanguage` 同一口径），系统是日语/法语等既不归错也不悄悄变中文。
     */
    fun resolve(pref: LanguagePref, systemLanguage: String): ResolvedLanguage = when (pref) {
        LanguagePref.ZH_HANS -> ResolvedLanguage.ZH_HANS
        LanguagePref.EN -> ResolvedLanguage.EN
        LanguagePref.SYSTEM -> if (systemLanguage.lowercase() == "zh") ResolvedLanguage.ZH_HANS else ResolvedLanguage.EN
    }

    // 展示文案（"跟随系统"/"简体中文"/"English" 等）不再手写在这里——现在接了
    // IMServer/docs/i18n/strings.json 生成的 R.string.settings_language_*，需要 @Composable 的
    // stringResource()，属于 UI 关注点，见 ui/screens/LanguageScreen.kt 的 displayName()/currentLabel()。
}
