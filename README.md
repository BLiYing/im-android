# im-android

IM 即时通讯的 **Android 客户端**（Kotlin + Jetpack Compose）。
与 iOS（`../IMProgram`）、Web（`../im-web`）功能对齐，后端为 `../IMServer`。

> **当前处于骨架期**：工程结构、设计令牌、协议地基与门禁已就位，业务功能从 M0 起逐项接。
> 逐功能×端进度见 `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列。

## 快速开始

```bash
./scripts/install-hooks.sh    # 每个 clone 跑一次，装 pre-commit 门禁
./scripts/test.sh             # 体量门禁 + 日志红线 + 编译 + 单测
```

用 Android Studio 打开本目录即可（`local.properties` 里的 SDK 路径按本机生成，不入库）。

后端要一起起：

```bash
cd ../IMServer && ./scripts/dev.sh --no-tail
```

模拟器经 `10.0.2.2:8080` 连宿主机的后端（`127.0.0.1` 在模拟器里指模拟器自己）。

## 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Kotlin 2.0 | Google 官方首选；不用 Java |
| UI | Jetpack Compose + Material3 | 声明式，与 Web 的组件化思路同构 |
| 配色 | **项目自有语义令牌**，非 Material You 动态取色 | 三端要同一套 Telegram 绿；让系统壁纸决定主色会当场与 iOS/Web 分叉 |
| 网络 | OkHttp WebSocket | 能设请求头，走 PROTOCOL §1 首选的 `Authorization: Bearer` |
| 序列化 | kotlinx.serialization | 协议是 snake_case，`@SerialName` 映射干净 |
| 图标 | lucide（ISC 许可，待接入） | 与 Web 同一套；iOS 用 SF Symbols，三端语义对齐 |

## 文档

| 文档 | 管什么 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | 工作约定、完成的定义、构建命令 |
| [CODING_STYLE.md](CODING_STYLE.md) | 命名、文件组织、防巨文件、交付前自审清单 |
| [docs/UI_COLOR.md](docs/UI_COLOR.md) | 本端令牌落地方式与 Android 特有约束 |
| [docs/LOGGING.md](docs/LOGGING.md) | 本端日志入口与字段约定 |
| `current_task.md` | 活快照：当前焦点 / 下一步 / 已知坑 |

跨端契约（协议、聊天交互、令牌总表、对称路径登记表）都在 `../IMServer/docs/`。
