# Android UI 配色与外观规范（本端补充）

> **跨端共同规则以 [`../../IMServer/docs/UI_COLOR.md`](../../IMServer/docs/UI_COLOR.md) 为准**
> ——语义令牌总表、文本层级、深色验收清单都在那里，改那张表 = 改三端。
> 本文只补**本端的令牌实现方式与 Android 特有约束**，不重复记取值。
>
> 判断归属：一条规则三端都要照做 → 写那边；依赖 Android 框架能力或平台限制 → 写这里。

## 1. 令牌落在哪

| 概念 | 本端落点 |
|---|---|
| 颜色语义令牌 | `ui/theme/Tokens.kt` 的 `IMColors`（`LightIMColors` / `DarkIMColors` 两组取值） |
| 尺寸（圆角/间距） | 同文件 `IMDimens` |
| 用户可调（字号/气泡圆角） | 同文件 `IMAppearance`，对应 iOS 的 `IMAppearance` / Web 的 `--msg-font`、`--radius-bubble` |
| 取用入口 | `ui/theme/Theme.kt` 的 `IMTheme.colors` / `.dimens` / `.appearance` |
| 文本层级 | `ui/theme/Type.kt` 的 `IMTypography` |

**业务代码只写 `IMTheme.colors.textSecondary` 这种，禁止 `Color(0xFF8A929C)`。**
缺语义先去 `Tokens.kt` 扩展，不得就地写颜色。

## 2. 刻意不启用 Material You 动态取色

Android 12+ 可以让系统壁纸决定主色（`dynamicLightColorScheme`）。**本项目不用。**

理由：三端要的是同一套 Telegram 绿。开动态取色等于让每台手机的壁纸决定这个 App 长什么样，
与 iOS/Web **当场分叉**，而 `UI_COLOR.md` §1.1 的第一条硬规则就是"只用语义令牌"。
用户想换主题走**应用内的聊天主题**（三端共有的那一套），不是走系统取色。

## 3. 令牌同时喂给 Material3

`IMAppTheme` 除了提供 `IMTheme.*`，还把令牌映射进 `MaterialTheme.colorScheme`
（primary/background/surface/error/outline…）。

**为什么要做这一层**：直接用的 M3 组件（`Button`/`TextField`/`Switch`/`Slider`）读的是
`MaterialTheme.colorScheme`，不喂它就会落在 **M3 默认紫**上——于是页面里自定义部分是绿的、
系统组件是紫的。这类"混色"在浅色下还不明显，深色下一眼就穿帮。

推论：**新加 M3 组件后要顺手确认它取的那个 slot 已被映射**，没映射就补进 `Theme.kt`。

## 4. Android 特有约束

### 4.1 深色模式的三个来源
本端"深色"有三个可能来源，**不要各写各的**：
1. 系统深色（`isSystemInDarkTheme()`）——默认，对应"跟随系统"。
2. 应用内偏好（`IMThemeMode.Light/Dark`）——用户在设置里选的，**优先级高于系统**。
3. `res/values-night/`——**只管启动窗口**（`windowBackground`），不参与 Compose 配色。

第 3 条是最容易搞混的：`values-night` 跟随的是**系统**深色，用户在应用内选了浅色时
它仍是深色底 → 启动那一瞬间黑底闪一下再变白。当前骨架期可接受；
**接了应用内主题偏好后，要把启动窗口底色也按偏好写回**（`AppCompatDelegate.setDefaultNightMode`
或自绘 splash），否则就是这个闪烁。

### 4.2 边到边（edge-to-edge）与系统栏
`MainActivity` 调了 `enableEdgeToEdge()`，状态栏/导航栏透明、内容铺满。
**推论**：每个成页的 Composable 必须自己处理 insets（`systemBarsPadding()` /
`imePadding()` / `navigationBarsPadding()`），否则内容会被系统栏压住。
聊天页尤其要 `imePadding()`——输入法弹起时输入框要跟着上移
（`AndroidManifest` 已配 `windowSoftInputMode="adjustResize"`，两者要配合）。

### 4.3 `Color` 的 alpha 写法
Compose 的 `Color(0xAARRGGBB)` 是 **ARGB**，与 Web 的 `#RRGGBBAA` **字节序相反**。
从 `styles.css` 抄 `rgba(0,0,0,0.4)` 时要换算成 `0x66000000`（0.4×255≈0x66 放最前）。
抄反了不会报错，只会颜色离谱——`Tokens.kt` 里所有透明色都已按此换算过。

### 4.4 字号单位用 `sp`，尺寸用 `dp`
文字一律 `sp`（跟随系统字体缩放，无障碍要求），间距/圆角一律 `dp`。
**不许给正文写死高度**（`UI_COLOR.md` §3 末）：用户调大字号后行高要自动重算，
Compose 默认就会重算，但一旦写了 `Modifier.height(48.dp)` 包住文本就破功了。

## 5. 深色验收（照跨端清单跑）

验收清单在 `../../IMServer/docs/UI_COLOR.md` §6，六条三端同一份。本端补两条操作方式：
- 每个 Composable 至少配**两个 `@Preview`**（浅色 + 深色），别只看一个。
- 运行时切换要当场生效：模拟器下拉快捷开关切深色，**不许要求杀进程重启**（§6.6）。

## 6. 待办

- [ ] **图标库接入 lucide**（ISC 许可，与 Web 同一套；iOS 用 SF Symbols，三端语义对齐）。
      接入前 Material Icons 顶着用，但**别把两套混着用**。
- [x] 应用内主题偏好落地（2026-09-29：`data/AppearanceStore.kt`，外观页见 `docs/UI_PARITY_IOS.md` §4.11）。
- [x] 聊天壁纸（2026-09-29：`ui/components/ChatWallpaper.kt`，只画在消息列表底下，不外溢）。
- [ ] 正式启动图标（现为占位绿底气泡矢量图）。
