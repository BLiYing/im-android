# Android 日志规范（本端补充）

> **跨端共同契约以 [`../../IMServer/docs/LOGGING.md`](../../IMServer/docs/LOGGING.md) 为准**
> ——级别、脱敏清单、Request ID 对账、事件命名都在那里。本文只记本端入口与实现约定。

## 1. 统一入口

`app/src/main/kotlin/com/libeyond/imandroid/sdk/logging/IMLog.kt`。

```kotlin
private val log = IMLog.tag("IM.WS")

log.i("ws_connected", "host" to host, "attempt" to attempt)
log.w("ws_reconnect_scheduled", "delayMs" to delay)
log.e("ws_handshake_failed", err, "code" to 401)
```

- **第一个参数是稳定事件名**（可 grep），别把变量拼进去——变量放后面的 `fields`。
- Tag 用 `IM.<子系统>`：`IM.WS` / `IM.HTTP` / `IM.DB` / `IM.Chat` / `IM.App`。
  与 iOS 的 tag 保持同名，便于两端对着同一个词捞日志。

## 2. 红线：禁止直接打印

**业务代码禁止 `android.util.Log` / `println` / `System.out`**（`IMLog.kt` 自身除外）。

自查（应为 0 条）：
```bash
./scripts/check-logging.sh
```
已接进 `scripts/test.sh` 第 2 步与 `pre-commit`。

### 为什么这条要机械化
Go 后端曾累计 **54 处**违规无人察觉——因为 `observability.Configure` 里有段兼容桥接，
违规日志照样输出成 JSON、只多一个 `component:legacy` 标记，**"看起来没坏"**。
iOS/Web 没有这层兜底，写错立刻显眼，反而一直干净。

**所以本端刻意不做任何桥接**：写错就是编译期/自查期直接暴露。
教训（跨端共识）：兼容层要么带明确迁移期限，要么就别加，它会把技术债伪装成正常输出。

## 3. 脱敏与截断

`IMLog.redact` 是**纯函数**，`fields` 在进落点前统一过一遍：
- 命中敏感 key（`password`/`token`/`authorization`/`cookie`/`secret`/`phone`…，不区分大小写）
  → 替换为 `***`。
- 字符串超 **16KB** → 截断并标注 `…<truncated N>`（`LOGGING.md` §5 的客户端单条上限）。

**新增敏感字段先加进 `SENSITIVE_KEYS`、再补测试**，顺序别反。
测试在 `IMLogRedactTest.kt`（5 例，含大小写与边界）。

## 4. 级别

`minLevel` 默认：Debug 构建 `DEBUG` 全开，Release 构建 `INFO` 及以上。

按 `LOGGING.md` §5：**Release 不得记录消息正文等业务内容**；
Debug 可记脱敏后的业务正文。当前骨架期还没有正文日志，接消息收发时要守住这条。

## 5. 待办

- [ ] **dev 汇聚 sink**（`LOGGING.md` §7.2）：攒批 POST 到后端 `-dev-logsink` 挂的 `/__devlog`，
      让 Android 日志也落到 `IMServer/dev-logs/` 里能 grep。
      要点（照 iOS 的坑抄）：
      - **走独立裸 HTTP 客户端**，不复用业务的 OkHttp——否则每次上报又生成一条 HTTP 日志，自我循环。
      - 攒批（2s / 200 行），失败即丢，缓冲有界（封顶丢最旧）。
      - **仅 Debug 构建注册**。
      - 顶层带 `dev`（设备短 id）与 `uid`（当前账号）两个标签，便于多设备/多账号分离。
- [ ] Request ID 贯通：HTTP 层带上并记录，与后端 `imserver.log` 对账。
- [ ] 崩溃日志落盘 + 触发式上传（正式功能，非开发期通道）。
