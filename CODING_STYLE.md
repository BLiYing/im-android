# im-android 代码规范（Kotlin 2.0 + Jetpack Compose）

> 主栈：**Kotlin + Compose**。总原则：可读性 > 取巧；与现有代码风格一致；一个文件/函数只做一件事。
> 本文只讲**编码风格 + 防巨文件**；工作流程、日志、配色、协议等契约以 `CLAUDE.md` 及其指向的文档为准。

---

## 一、命名
- **@Composable**：大驼峰，一个文件一个主 Composable，文件名 = 函数名。`MessageList.kt` → `fun MessageList(...)`。
- **状态持有者 / ViewModel**：大驼峰 + 后缀。`ChatViewModel` / `ChatWindowState`。
- **纯函数模块**：小驼峰文件名或按主题命名，导出顶层函数。`messageContent.kt` / `convPreview.kt`。
- **数据类 / 接口**：大驼峰。**协议 DTO 一律放 `sdk/protocol/`**，字段用 `@SerialName` 映射 snake_case。
- **变量 / 函数**：小驼峰，语义完整不缩写。事件回调参数 `onXxx`（Composable 形参）/ `handleXxx`（内部）。
- **布尔**：`is/has/should/can` 开头，如 `isGroupChat`、`canManage`。
- **常量**：`const val` 全大写下划线，放 `companion object` 或顶层 `object`。禁止散落魔法数/字符串。

## 二、文件组织
- 一个主 Composable 一个文件；纯展示放 `ui/components/`，成页的放 `ui/screens/`。
- **SDK 与 UI 分层**（CLAUDE.md 已定）：协议能力沉淀在 `sdk/`，Composable 只调它，不拼协议帧。
- 纯逻辑（不 import `androidx.compose.*`、不碰 `android.*`）放普通 `.kt`，**必配单测**。
- import 不用通配符（`import x.*`），除非是 Compose 惯例的 `androidx.compose.foundation.layout.*`。

## 三、类型与空值安全
- **禁止 `!!`**。用 `?.` / `?:` / `requireNotNull(x) { "为什么这里一定非空" }`（带理由的 require 才有价值）。
- 平台类型（Java 互操作来的）进本项目边界时立刻收窄成明确可空性。
- 新协议字段：先在 `sdk/protocol/` 加类型 + `@SerialName`，再在 SDK/UI 用。
- `sealed interface` 表达互斥状态（加载中/成功/失败），别用一堆 `Boolean` 拼——
  **两个布尔能拼出 4 种状态而实际只有 3 种**，第 4 种就是 bug 的藏身处（Web 的 `GroupTextModal` 踩过）。

## 四、Compose 风格
- 缩进 4 空格（Kotlin 官方风格，与 Web 的 2 空格不同，别互抄）。
- Composable 函数体顺序：`remember`/状态 → 派生值 → 回调 → 副作用（`LaunchedEffect`）→ 布局。
- **`Modifier` 恒为第一个可选参数且默认 `Modifier`**，且**不要在函数内部改传入的 modifier 语义**
  （`modifier.fillMaxSize()` 由调用方决定，被调方硬加 = 调用方失去控制权）。
- **状态上提**：可复用的 Composable 不持有业务状态，`value` + `onValueChange` 由调用方给。
- **`remember` 的 key 要写全**：漏 key = 数据变了 UI 不变，且**编译不报错、测试常测不到**
  ——这是本端最容易悄悄错的一类。
- **`LaunchedEffect(Unit)` 慎用**：它只在进入组合时跑一次，参数变了不会重跑。
  要跟随参数就把参数写进 key。
- 列表 `key` 用**稳定身份**：消息一律用 `convSeq`，**绝不用下标**。
  理由与三端同源——入站消息没有 `clientMsgId`，向上翻页 prepend 后下标整体平移，
  用下标会让 Compose 错绑已有节点（播放中的视频、展开的长文跳到别的行）。
  iOS 与 Web 都各踩过一次，见 `../IMServer/docs/SYMMETRY.md`。

## 五、错误处理与日志
- 网络 / IO / DataStore 调用必须有明确失败分支，不吞错（`catch` 里至少上报状态或落日志）。
- **禁止业务代码直接 `android.util.Log` / `println` / `System.out`**：统一走 `sdk/logging/IMLog.kt`。
  自查：`./scripts/check-logging.sh` 应为 0 条。
- 协程里捕获异常别用裸 `catch (e: Exception)` 吞掉 `CancellationException`
  ——那会让协程取消失效。要么 `catch (e: CancellationException) { throw e }`，要么用 `runCatching` 后显式重抛。
- 按业务码分支一律读 `ErrCode` 常量，**禁止 parse 错误文案字符串**（文案会改、会多语言）。

## 六、UI / 样式
- 改 UI 前先读 `docs/UI_COLOR.md` + `../IMServer/docs/UI_COLOR.md`。
- **只用 `IMTheme.colors.*` / `IMTheme.dimens.*`**，禁止散落 `Color(0xFF…)` 与魔法 `dp`。
  缺语义先去 `ui/theme/Tokens.kt` 扩展令牌，不得就地写颜色。
- 浅色 / 深色 / 跟随系统三种模式都要过一遍（`@Preview` 各配一个）。
- 聊天列表交互（定位/分页/未读分割线/已读/自动滚动）以 `../IMServer/docs/CHAT_UX.md` 为单一事实来源。

## 七、防巨文件 —— 重点
iOS 的 `IMChatViewController` 与 Web 的 `App.tsx` 都长到过四五千行才开始拆，
**代价是「改一个小格子要动整个巨文件」**。本仓从第一天就设红线，别重走那条路。

新代码往哪放，按此决策树：
- **① 有自己状态 + 一组操作的功能 → 状态持有者**（`ChatWindowState` 这类普通类，或 ViewModel）。
  判据：需要 **≥2 个新状态**、或有自己的定时器/订阅/缓存。依赖**注入进去**，别在里面摸全局单例。
- **② 一整块 UI（面板 / 弹窗 / 查看器 / 卡片）→ 独立 @Composable 文件**。纯展示：
  数据与动作全经参数注入，不持业务状态。
- **③ 纯逻辑（无 Compose、无 Android）→ 普通 `.kt` 顶层函数 + 单测**。
  **优先抽这一档**——它是唯一「可测 + 真解耦」的，收益最实。

**红线（机械护栏，别靠自觉）**：`./scripts/check-file-size.sh`——`app/src/main` 下非测试 `.kt`
> **600 行**即非零退出。超标的正确处理是**拆分**，**不是放宽阈值**；
真属生成/内聚大文件才登记 grandfather，且**只准降不准升**。

**别为「砍行数」硬拆**（三仓共同复盘）：目标是「把易错逻辑隔离成可测」，不是凑数字。
一个只把 UI「换个地方放」的拆分解耦有限；而把「一堆 setter 注入」的胶水层抽出去，
是搬走 tangle 还测不动，属负 ROI。

## 八、测试
- **每加一个功能配 `*Test.kt`**，`./scripts/test.sh` 自动纳入回归。
- 纯逻辑直接测函数（JVM 单测，快）；Compose UI 测试用 `createComposeRule`（instrumented，慢，按需）。
- **单测测不到 Compose 的真实布局与滚动时序**——滚动定位/分页类改动，
  **编译过 ≠ 对，必须跑模拟器手测**。这条 iOS 与 Web 都反复吃过亏。
- **新增的测试必须先看它红一次**：临时把实现改坏，确认测试变红，再改回来。
  没红过的测试不算数——断言写错对象、根本没跑到那个分支，照样全绿。

## 九、交付前自审清单（编译≠正确，逐条过再交付）
`./scripts/test.sh` 绿只证明「编译 + JVM 单测过」，本端多数坑是**运行时悄悄错**的状态类。
声明完成前对照下表扫一遍，命中即停下来核：

- **[消息身份] 列表 `key` 用 `convSeq`，永不用下标。** 入站消息无 `clientMsgId`；
  向上翻页 prepend 后下标平移会让 Compose 错绑节点。
- **[remember key] 每个 `remember` / `LaunchedEffect` 的 key 是否写全**？
  漏 key = 数据变了 UI 不动，编译不报错、单测也测不到。
- **[令牌] 新增 UI 有没有散落 `Color(0xFF…)` / 魔法 dp**？只用 `IMTheme.*`；
  浅色深色各看一遍（两个 `@Preview`）。
- **[日志] 有没有绕过 `IMLog`**？`./scripts/check-logging.sh` 应为 0 条。
- **[错误码] 按业务码分支读 `ErrCode` 常量**，禁止 parse 文案；
  也别把 HTTP 401/403 当业务码用（语义在 code 里，后端把几十种业务错误都映射成 400）。
- **[协程] `catch` 有没有把 `CancellationException` 一起吞了**？那会让取消失效。
- **[对称] 改动落在 `../IMServer/docs/SYMMETRY.md` 登记的路径上 → 先完整读兄弟那一侧再动手。**
  「一条路改对了、对称兄弟没跟」是三仓最大的一类 bug；本端是第三个实现，
  iOS/Web 已踩过的坑**别再演第三遍**。
- **[测试有效性] 新增的测试先看它红一次。**
- **[手测] 滚动 / 分页 / 动画 / 输入法遮挡——跑模拟器看，别只看编译绿。**

## 十、通用约定
- 提交信息：`类型(模块): 描述`（`feat(android):` / `fix(android):` / `refactor(android):` …）。
- 每个非平凡改动后更新 `current_task.md`（活快照，就地覆盖）。
- 新增业务/技术 Markdown 统一放 `docs/`；根目录仅留 README / CLAUDE / AGENTS / `current_task.md` / 本规范。
- 声明「完成」前跑 `./scripts/test.sh` 全绿，条件具备时模拟器实测——详见 `CLAUDE.md`「完成的定义」。
