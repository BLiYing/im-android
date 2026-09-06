# im-android — 项目说明（供 Claude 读取）

## 项目简介
IM 即时通讯的 **Android 客户端**。与 iOS（IMProgram）、Web（im-web）**功能对齐**，
协议以 `../IMServer/docs/PROTOCOL.md` 为准，端能力矩阵见 `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列。

**当前状态：骨架期**。已有工程结构、设计令牌、协议地基与门禁；业务功能从 M0 开始逐项接，
进度看 `current_task.md` 与 CLIENT_PARITY 的 Android 列。

## 技术栈
- 语言：**Kotlin 2.0**（不用 Java）
- UI：**Jetpack Compose**（Material3 组件 + 本项目自己的语义令牌，见下）
- 通信：**OkHttp WebSocket**（能设请求头，故走 PROTOCOL §1 首选的 `Authorization: Bearer`，
  而不是浏览器被迫用的 `?token=` 查询串）
- 序列化：**kotlinx.serialization**（协议是 snake_case，靠 `@SerialName` 映射）
- 构建：Gradle 8.13 + AGP 8.12 + Version Catalog（`gradle/libs.versions.toml`）
- `minSdk 26 / targetSdk 36 / compileSdk 36`，`applicationId = com.libeyond.imandroid`

## 工程结构
```
app/src/main/kotlin/com/libeyond/imandroid/
├── sdk/                    协议 SDK（**UI 无关**，聊天能力沉淀于此）
│   ├── protocol/
│   │   ├── Envelope.kt     信封 + 帧类型常量 + ProtocolJson（对齐 PROTOCOL.md §2）
│   │   └── ErrCode.kt      业务错误码（对齐 internal/errcode/errcode.go）
│   └── logging/IMLog.kt    **唯一**日志入口
├── ui/theme/               设计令牌 → Compose（Tokens/Theme/Type）
├── MainActivity.kt
└── IMApp.kt
```
**SDK 与 UI 分层**（与 iOS `IMKit`、Web `src/sdk/` 同构）：协议能力放 `sdk/`，
界面只调用它，**不在 @Composable 里直接拼协议帧**。

## 工作约定
- **每次开始主要回复前，先读 `current_task.md` 恢复上下文**，改动后更新它。
- **`current_task.md` 是"活快照"，不是流水账**：固定四节（当前焦点 / 下一步 / 已知坑·限制 /
  关联工程·常用命令），**就地覆盖，禁止追加 `Status ②③④…` 新块**。历史交给 `git log` 与
  `current_task.archive.md`（只读归档）。逐功能×端状态只写 `../IMServer/docs/CLIENT_PARITY.md`（唯一来源）。
- **聊天消息列表的交互行为**（进会话定位/分页/未读分割线/红点/已读/跳转/自动滚动）
  **以 `../IMServer/docs/CHAT_UX.md` 为单一事实来源**，照它实现，别另起一套。
- 协议字段以 `../IMServer/docs/PROTOCOL.md` 为准；**帧类型枚举的权威是后端
  `internal/protocol/envelope.go` 的常量**（PROTOCOL 正文那行枚举漏了 `conv_bump` 与
  `voice_transcript`，照文档抄会缺两个——已在 `Envelope.kt` 的注释里记下）。
- **改 UI 前必须读 `docs/UI_COLOR.md` + `../IMServer/docs/UI_COLOR.md`**；只用
  `IMTheme.colors.*` / `IMTheme.dimens.*` 语义令牌，**禁止散落 `Color(0xFF…)`**。
- **写任何新日志前先读 `../IMServer/docs/LOGGING.md` §7.1**。本端统一入口是
  `sdk/logging/IMLog.kt`，**禁止业务代码直接用 `android.util.Log` / `println` / `System.out`**。
  自查已机械化：`./scripts/check-logging.sh`（`test.sh` 第 2 步 + pre-commit）。
- **单文件体量红线**：`app/src/main` 下非测试 `.kt` **> 600 行**要按职责拆分
  （与 im-web 同阈值——Compose 函数与 React 组件同构，膨胀方式也一样）。
  机械门禁 `./scripts/check-file-size.sh`（`test.sh` 第 1 步 + pre-commit）。
  新 clone 跑一次 `./scripts/install-hooks.sh` 装钩子。
- **今后新增的业务/技术 Markdown 文档一律放入 `docs/`**。根目录仅保留 README、
  AGENTS/CLAUDE、CODING_STYLE、`current_task.md` 及既有工程入口文件。
- 提交信息格式：`类型(模块): 描述`（如 `feat(android): …` / `fix(android): …`）。
- **编码风格 + 防巨文件规范见 [CODING_STYLE.md](CODING_STYLE.md)**。

## 工作流程与「完成的定义」（每次自动遵循，无需用户重复提醒）
动手前（Read，不靠记忆）：
- **先看改动有没有「对称兄弟」**：查 `../IMServer/docs/SYMMETRY.md` 登记表，命中就**完整读兄弟那一侧**
  （尤其读它注释里记的坑）再动手。按 commit 史粗筛，「一条路改对了、对称兄弟没跟」是三仓**最大**的
  一类 bug（13.9%）。`pre-commit` 里的 `check-symmetry.sh` 会再念一遍，但那时代码已经写完了。
  **本端尤其要小心**：iOS 与 Web 已各自踩过一遍的坑，Android 是第三次机会，别再重演。
- 改聊天交互前先 Read `../IMServer/docs/CHAT_UX.md`；涉及协议字段再 Read `../IMServer/docs/PROTOCOL.md`。

声明「完成」前必须全部满足，并在回复中**贴出 `./scripts/test.sh` 的输出**：
1. 新功能配套 `*Test.kt`，由 `test.sh` 自动纳入回归。
2. `./scripts/test.sh` 全绿（体量门禁 + 日志红线 + 编译 + 单测）。
3. 更新 `current_task.md`；**若完成的是路线图里的里程碑/子项，同步更新
   `../IMServer/docs/ROADMAP.md` 与 `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列与完成时间（YYYY-MM-DD）**。
4. 明确说清楚「没做什么 / 已知限制 / TODO」，不假装完成。
5. **编译过 ≠ 对**：滚动定位、分页、动画这类必须**跑模拟器/真机看**。
   单测测不到 Compose 布局与滚动时序——这条 iOS 与 Web 都反复吃过亏。

主动建议（不必用户开口）：
- **新增测试要先看它红一次**（临时改坏实现，确认变红再改回来）。没红过的测试不算数。
- **命中 `SYMMETRY.md` 登记表的改动，交付前默认跑 `/code-review`**。
- 触及鉴权 / 加密 / 敏感数据时，建议跑 `/security-review`。

## 后端重启提醒（重要）
- **纯 Android 改动**：不影响后端，重新 run 即可。
- **改了后端代码**（IMServer）：必须重启，否则端上连到旧逻辑——这种情况要**明确提醒用户重启后端**再测。
  提醒里一律给这一条（**不要写 `go run ./cmd/imserver`**）：
  ```bash
  cd ../IMServer && ./scripts/dev.sh --no-tail
  ```

## 构建 / 运行 / 测试
```bash
./scripts/test.sh                # 唯一测试入口：体量门禁 + 日志红线 + 编译 + 单测
BUILD_ONLY=1 ./scripts/test.sh   # 只编译
ONLY=EnvelopeTest ./scripts/test.sh
./scripts/install-hooks.sh       # 每个 clone 跑一次，装 pre-commit
```
- **连后端**：debug 构建的 `BuildConfig.DEFAULT_HOST` 是 `10.0.2.2:8080`
  ——那是 Android 模拟器指向**宿主机**的固定回环地址（`127.0.0.1` 在模拟器里指模拟器自己）。
  真机连开发机要改成开发机内网 IP，并把该 IP 加进 `res/xml/network_security_config.xml`。
- **明文 HTTP 只对开发主机放行**，没有用 `usesCleartextTraffic="true"` 全局开口子
  ——那个开关会让 release 包也能明文出网，等于把 PROTOCOL §0.1「一律 wss」废掉。
- **JAVA_HOME**：本机的 `JAVA_HOME` 被设成了 Homebrew 未替换的占位符 `@@HOMEBREW_JAVA@@`，
  裸跑 `gradle` 会报 "invalid directory"。`scripts/test.sh` 已自愈（自动挑 JDK 17）；
  手拼 `./gradlew` 命令行才要自己 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。

## 关联工程
- 后端：`../IMServer`（协议 `docs/PROTOCOL.md`、交互蓝图 `docs/CHAT_UX.md`、令牌 `docs/UI_COLOR.md`）
- iOS：`../IMProgram`（功能对齐参照，架构见 `ARCHITECTURE.md`）
- Web：`../im-web`（功能对齐参照；**本端很多判据可直接照它的纯函数模块搬**）
