# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（尚未创建，第一次裁剪本文件前先建）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **工程初始化 ✅ 2026-09-07**。空仓 → 可构建、可测试、带门禁的 Kotlin + Compose 骨架。
>
> **已落地**：
> - **构建**：Gradle 8.13 + AGP 8.12.1 + Kotlin 2.0.21 + Compose BOM 2024.10.01，
>   Version Catalog 收口版本号（`gradle/libs.versions.toml`，模块里不写死版本）。
>   `minSdk 26 / targetSdk 36 / compileSdk 36`，`applicationId = com.libeyond.imandroid`。
> - **设计令牌**（`ui/theme/Tokens.kt`）：颜色/尺寸/可调外观三组，**逐值抄自**
>   `../IMServer/docs/UI_COLOR.md` §2 总表与 `../im-web/src/styles.css`，深浅两套。
>   刻意**不开 Material You 动态取色**——那会让壁纸决定主色，与 iOS/Web 当场分叉。
>   令牌同时喂进 `MaterialTheme.colorScheme`，否则直接用的 M3 组件会落在默认紫上。
> - **协议地基**（`sdk/protocol/`）：`Envelope`（`{type,seq,data}`，data 延迟解析）
>   + 24 个帧类型常量 + `ErrCode`。**两处都按后端源码核对，没照文档抄**：
>   PROTOCOL §2 正文那行枚举漏了 `conv_bump` 与 `voice_transcript`（文档自己写明"以
>   `envelope.go` 为准"）；错误码按 `internal/errcode/errcode.go` 逐条核。
> - **日志入口**（`sdk/logging/IMLog.kt`）：tag + 稳定事件名 + 脱敏 + 16KB 截断。
>   **刻意不做 `android.util.Log` 兼容桥接**——Go 端就是因为有桥接兜底、"看起来没坏"
>   才攒到 54 处违规无人察觉。
> - **门禁**：`check-file-size.sh`（600 行，同 im-web 阈值）、`check-logging.sh`、
>   `install-hooks.sh` + `pre-commit`（体量 + 日志 + 调 IMServer 的对称路径提醒）、
>   `test.sh`（唯一测试入口，**自愈本机坏掉的 JAVA_HOME**）。
> - **测试 12 例**：`EnvelopeTest` 7（含 PROTOCOL §2「未知 type/字段不得崩」这条红线）
>   + `IMLogRedactTest` 5（脱敏 + 大小写 + 超长边界）。
>   **已做变异验证**：关掉 `ignoreUnknownKeys` → 未知字段用例变红；`redact` 改成原样返回
>   → 3 条脱敏用例变红；恢复后 12/12 绿。
>
> **一件事故（已按用户决定处理）**：clone 后另有一个并行会话在 01:02 往同一个仓
> 搭了套 `com.bliyingapps.im` 骨架，我 01:11 写构建文件时用 `cat >` **覆盖掉了它的 6 个
> 构建文件**（未入 git，不可恢复）。经用户拍板保留本套、删除对方那套（源码已备份到
> scratchpad）。对方那份 `IMProtocol.kt` 的信封是 `{msg_id,msg_type,data}`、与后端不符，
> 错误码表也把 `500001` 当成"内部错误"（实为"文件过大"）并混入了 HTTP 状态码，均未沿用。
> **教训**：多会话并行同一个仓时，写文件前要重新确认目录状态，别拿 clone 那一刻的印象当准。

## 下一步

按 `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列**从 M0 起**，顺序与 iOS/Web 当年一致：

1. **网络层地基**：`sdk/http/`（OkHttp + Request ID + 统一错误码解析）
   + `sdk/IMSocketManager.kt`（WS 连接/心跳 25s/指数退避重连）。
   握手走 `Authorization: Bearer`（PROTOCOL §1 首选，OkHttp 能设头，别学 Web 用 `?token=`）。
2. **M0 登录**：`POST /api/v1/login` → JWT → 连 `/ws`。
   **必带稳定 `device_id`**（生产缺它直接 400），按 `(uid,device_id)` 顶替去重。
3. **M0 收发**：`send_msg → ack → new_msg`，`client_msg_id` 幂等 + 发送态三档 + 超时重发。
4. **M0 本地落库**：Room（对应 iOS 的 SQLite、Web 的 IndexedDB），
   **按账号隔离**、持久化连续同步游标；**禁止用 `MAX(conv_seq)` 当游标**（三端同一条纪律）。
5. **M0 离线同步**：`sync_req/sync_resp` + 空洞回补。
6. 之后才轮到会话列表 / 聊天 UI。

**接 UI 前先补的两件**：
- 图标库接入 **lucide**（ISC，与 Web 同一套），别先用 Material Icons 铺开再换。
- 应用内主题偏好落地（持久化 + 实时生效 + 重启恢复 + 深浅两套取值，`UI_COLOR.md` §1.3 五件事）。

## 已知坑 / 限制

- **骨架期，没有任何业务功能**：当前 `MainActivity` 只是令牌验证页，不能登录、不能收发。
- **没跑过模拟器**：本轮只到 `assembleDebug` + JVM 单测绿，**APK 没在设备上装起来看过**。
  Compose 的布局/滚动/输入法遮挡这类**单测测不到**，接 UI 时必须跑模拟器。
- **本机 `JAVA_HOME` 是坏的**：被设成 Homebrew 未替换的占位符 `@@HOMEBREW_JAVA@@`，
  裸跑 `gradle` 报 "invalid directory"。`scripts/test.sh` 已自愈；
  手拼 `./gradlew` 要自己 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
- **`values-night` 只管启动窗口**，跟随的是**系统**深色。用户在应用内选浅色时，
  启动那一瞬间仍是深色底 → 闪一下。接了应用内主题偏好后要一并解决（`docs/UI_COLOR.md` §4.1）。
- **明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行**（`res/xml/network_security_config.xml`）。
  真机连开发机要把内网 IP 加进去，**且别留到生产**。没有用 `usesCleartextTraffic="true"`
  全局开口子——那会让 release 包也能明文出网。
- **启动图标是占位**（绿底白气泡矢量图），待 UI 定稿替换。
- **`configuration-cache` 已开**：改 Gradle 脚本后若报缓存相关的怪错，
  `./gradlew --no-configuration-cache <task>` 排一下。
- **没有 instrumented 测试**：`androidTest` 依赖已配好但一个用例都没写。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。
- **参考实现**：功能对齐看 iOS 与 Web 的现成实现——**判据类的纯函数尤其可以直接照搬**
  （Web 的 `entryWindow.ts` / `unreadBelow.ts` / `listSearch.ts` 这类），
  但要守 `../IMServer/docs/SYMMETRY.md` 那条：**对称的是不变式，不是代码形状**。
- 本仓：
  ```bash
  ./scripts/test.sh                # 唯一测试入口（门禁 + 编译 + 单测）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ONLY=EnvelopeTest ./scripts/test.sh
  ./scripts/install-hooks.sh       # 每个 clone 一次
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 模拟器连宿主机后端：`10.0.2.2:8080`（`127.0.0.1` 在模拟器里指模拟器自己）。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`。
