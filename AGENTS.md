# im-android — 项目说明（供 Codex 读取）

## 必读入口

- 每次开始主要回复前先读 `current_task.md`。
- 工程结构、完成定义和构建命令以 `CLAUDE.md` 为准。
- 协议以 `../IMServer/docs/PROTOCOL.md` 为准（**帧类型枚举的权威是后端
  `internal/protocol/envelope.go` 的常量**）；聊天交互以 `../IMServer/docs/CHAT_UX.md` 为准。
- Android 日志实现见 `docs/LOGGING.md`，三端共同日志契约见 `../IMServer/docs/LOGGING.md`。
- **新增或修改任何 UI 前必须读取 `docs/UI_COLOR.md` 与 `../IMServer/docs/UI_COLOR.md`**；
  颜色、间距、圆角统一使用 `IMTheme.colors.*` / `IMTheme.dimens.*` 语义令牌，
  禁止业务代码散落 `Color(0xFF…)` 字面量。

## 硬性约定

- Kotlin + Jetpack Compose，**不写 Java**、不用 XML 布局（`res/values` 里只留启动窗口主题）。
- SDK 与 UI 分层；协议能力放 `sdk/`，@Composable 不直接拼协议帧。
- 新增日志必须走 `sdk/logging/IMLog.kt`，**禁止业务代码直接调用 `android.util.Log` /
  `println` / `System.out`**；自查 `./scripts/check-logging.sh`。
- 单文件 > 600 行即拆分；自查 `./scripts/check-file-size.sh`。
- 新功能配套 `*Test.kt`；声明完成前运行 `./scripts/test.sh` 并更新 `current_task.md`。
- 提交信息使用 `类型(模块): 描述`。
- 今后新增的业务/技术 Markdown 文档一律放入 `docs/`。根目录只保留 README、
  AGENTS/CLAUDE、CODING_STYLE、`current_task.md` 及既有工程入口文件。
