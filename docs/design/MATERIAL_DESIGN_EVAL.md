# Android 原生 Material Design 风格评估（暂缓，未决策）

> **这不是路线图条目，是一次讨论的留痕。** 起因是 2026-09-18 用户问「能不能把 Android 端改成原生
> Material Design 风格」，讨论完之后决定**暂不推进**，但结论值得记下来——下次再有人（包括未来的自己）
> 想起这个念头，不用把成本重新算一遍。
>
> 如果哪天真要动手，从这份文档开始，而不是从头讨论。

## 0. 现状：不是「没做 Material」，是刻意选了另一条路

Android 端**已经在用** Compose Material3 组件（`Button`/`TextField`/`DropdownMenu` 等），但：

- **颜色不走 M3 默认色板**：三端（iOS/Web/Android）共用一套语义令牌（Telegram 绿），业务代码
  统一走 `IMTheme.colors.*`，禁止散写颜色值（见 [`../UI_COLOR.md`](../UI_COLOR.md)）。
- **Material You 动态取色刻意关闭**（`UI_COLOR.md` §2）：理由是三端要长一样，让壁纸决定主色
  会当场分叉。
- **少数地方主动选了 Material 语汇**：比如「更多」菜单用 Material `DropdownMenu`（iOS 是自定义
  popover），这类差异登记在 [`UI_PARITY_IOS.md`](../UI_PARITY_IOS.md)，标记 🟢「刻意差异，不要
  来回改」，目前有 11 条。
- **其余大部分照 iOS 的页面结构与交互做**：22 个自绘弹层组件（`ActionSheet`/`CardSheet`/
  `ConfirmDialog` 等）模仿 iOS 交互、47 处用 Lucide 图标（iOS 风格线条图标，非 Material Icons）、
  平面标题栏（非 Material 的 elevation 滚动变色）、`PushTransition` 走 iOS 式侧滑转场。

一句话：**控件是 Material3 的，外观和交互手感大半是 iOS 的。**

## 1. 「改成原生 MD 风格」拆成两条路，代价完全不同

### 路径 A：只换配色体系（开动态取色 / M3 默认色板）

- 改动集中在 `ui/theme/`（`Theme.kt` 110 行 + `Tokens.kt` 375 行 + `Type.kt` 61 行，共 546 行）。
- `Theme.kt` 里现在手动把统一令牌映射进 `MaterialTheme.colorScheme`，改成调 
  `dynamicLightColorScheme()`/`dynamicDarkColorScheme()`（Android 12+ 系统 API）。
- 业务代码不用动——大家都是通过 `IMTheme.colors.xxx` 取色，换底层实现对调用方透明。
- **量级：半天到一天，风险低，可随时回退。**

### 路径 B：控件、图标、弹层、动画全面 Material 化

要动的不是几个文件，是跨越大半个 UI 层的改造：

| 现状 | 原生 MD 会长什么样 | 要动的地方 |
|---|---|---|
| 平面标题栏，无 elevation | Material `TopAppBar` 带滚动变色 | `TopBar.kt` 重写 |
| 47 处 Lucide 图标（iOS 风格线条图标） | Material Icons / Material Symbols | 全仓换图标，量最大 |
| 22 个自绘弹层（`ActionSheet`/`CardSheet`/`ConfirmDialog`，模仿 iOS） | Material `BottomSheet`/`AlertDialog` | 逐个重写，交互要重新对一遍 |
| 气泡圆角/间距是三端共用的 Telegram 风格令牌 | Material 的 shape/elevation/tonal surface 分级体系 | `Tokens.kt` 圆角、间距全部重定义 |
| `PushTransition`（iOS 式侧滑） | Material 的 shared axis / container transform / fade through | 转场逻辑重写 |

**规模参考（2026-09-18 统计）**：137 个 UI 文件里，73 个直接用到 `IMTheme.*`，其中 69 个用到颜色
令牌；凡是用了自绘弹层、Lucide 图标、自定义标题栏的地方都要跟着动。**不是配置开关，是一次跨大半个
UI 层的改造。**

## 2. 我的判断：现在不建议做路径 B

不是技术上做不到，是这笔账现在算下来不划算：

1. **三端一致性已经是工程基础设施，不只是设计选择**。`SYMMETRY.md`、`UI_COLOR.md`、
   `UI_PARITY_IOS.md` 三份文档 + pre-commit 对称性检查钩子，整套流程假设「改一处、三端一起想」。
   走路径 B 等于 Android 退出这套契约——不是改一次的成本，是**以后每个新功度都多一层「Android
   原生风格下该怎么做」的决策**，成本是复利式的。
2. **现在已经不是早期**：收藏、转发、下载门控这些复杂功能都已做完且有单测覆盖，是跟着「照抄 iOS
   结构 + 复用聊天页组件」这条路径积累出来的。这时候推倒重来是纯设计返工，不产生新功能。
3. **越晚做代价越高**是真的，但「现在做也不便宜」同时成立——不代表现在就该做。

## 3. 真正决定要不要做的问题：这个 Android 端给谁用

这是唯一可能反转上面判断的因素，目前没有答案：

- **如果主要是同一账号跨设备自用**（iOS + Android 切换）：「看起来像同一个产品」的价值大概率超过
  「更像原生 Android」的手感提升。
- **如果未来要给不认识 iOS 版的独立 Android 用户用**：Telegram 的路数（Android/iOS 两端刻意做成
  不同观感，只共享品牌色与气泡基本样式）更合适——常年用 Android 的用户对「一股 iOS 味」是有感知的。

Telegram 官方就是活案例：iOS 版是教科书式原生 iOS App（系统导航控制器、iOS 长按预览、边缘右划返回）；
Android 版走自己的设计语言（侧边抽屉导航、涟漪反馈、自定义底部弹层），两端只共享品牌色和气泡样式，
导航结构、手势、菜单、动画节奏完全按各自平台习惯做。

## 4. 如果一定要往这个方向走，先做这两项（低成本、争议最小）

不是一次性做完整路径 B，挑性价比最高、不冲击三端一致性契约的先做：

- **涟漪反馈**：大部分控件本来就是 M3 组件，基本白捡。
- **预测性返回手势**（Android 14+）：系统级能力，不需要重新设计转场动画。

图标、弹层、动画曲线、配色（路径 B 的主体）先不动——这几项才是真正冲击三端一致性、且工作量最大的
部分，等第 3 节的问题有答案了再决定要不要碰。

## 5. 结论

**暂缓，未决策。** 触发条件：想清楚「这个 Android 端主要给谁用」之后，回到第 3 节重新评估。
