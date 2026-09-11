# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **聊天页第三轮手测九条 ✅ 已实现，待用户真机手测**（2026-09-10，清单与做法见 `docs/UI_PARITY_IOS.md` §4.8「第三轮手测九条」）：
> ① 回复条轻点跳原消息（根因是居中补偿假设目标已在视口顶端）/ ② 接收端宫格左对齐 + 头像 /
> ③ **多选底栏 转发·举报·收藏·删除**（逐条/合并转发、批量举报判据、批量收藏，§4.7）/
> ④ 发送端引用块显人名 / ⑤–⑦ 未下载图片·视频·文件各状态照 iOS / ⑧ 未下载视频点空白不打开、文件不露类型图标 /
> ⑨ 群资料页删掉底部退出按钮。
> 纯逻辑：`data/SelectionActions.kt`（合并转发编码与隐私、举报判据、相册重新分组、收藏筛选）、
> `data/DownloadLabels.kt`、`ChatScroll.centerDeltaPx`，均先红过；`./scripts/test.sh` 590 例全绿。
> 单条转发三个入口（长按菜单 / 查看器 / 详情归档）撞上失效媒体一律拦下（code-reviewer 查出的漏拦，已补）。
>
> **做法上非显然的点**：① 合并转发卡片是**发出去的字节**——条目名只用公开名、`u` 只能是 s1/s2，
> 单聊对方名不能取 `conv.title`（备注优先）；举报确认框的名字只给自己看，反而用备注。
> ② 从多选发起的转发，**选好目标才退出多选**，在选择页取消要回到原勾选。
> ③ 逐条转发的相册要**每个目标会话**给新 `alb-` id，不能沿用原 id、也不能多目标共用。
>
> **真机首测即撞：拨任一开关都「保存失败，请检查网络后重试」→ 已修（2026-09-11）**。
> 服务端日志实证 Android 的 PUT 全是 **400**（不是网络）：`DownloadSettingsApi.put` 把 body 套了一层
> `{"settings":…}`，服务端要的是顶层 `{cellular,wifi}`（iOS/Web 同）。拆掉外层后还藏着第二个坑——
> `ProtocolJson` 的 `encodeDefaults=false` 会省掉 `enabled/single/group=true`，Go 解码成 false，
> **一次保存静默清成全关**。改为整份替换专用 `Json(from=ProtocolJson){encodeDefaults=true}`。
> `DownloadSettingsWireTest` 4 例（先验红 4/4）；`temp_verify.py --e2e` 起隔离 imserver（:8091 + scratch 库）三种 body 实测通过。

## 下一步

1. **等用户真机手测第三轮九条**，重点：键盘收起时点回复条跳原消息；合并转发收端卡片的标题与名字；
   举报灰钮提示；批量收藏回执；逐条转发含失效媒体时的提示。
2. **收藏的另一半**：收藏列表页、删除、长按菜单「收藏」、附件面板「从收藏发送」（`AttachItems.Kind.Favorite` 仍占位）。
3. **长按菜单缺项**：单条举报 / 收藏 / 翻译（`CLIENT_PARITY` 消息长按菜单那行）。
4. **宫格**（上一轮 #7）：按 `IMAlbumRowPattern` 重写布局（每行列数决定格宽、1 列行高固定 150）+ 五道防跳版闸；
   另欠相册宫格逐格勾选。
5. **转发选择页**（上一轮 #8）：可下滑、标题「转发到」居中、剔除系统通知会话。
6. 按 `docs/UI_PARITY_IOS.md` 剩下的 🔴：会话内搜索的📅日历与👤发件人过滤、水滴头部形变、语音页签内播放、「名片」页签。
7. 按 `CLIENT_PARITY` 追 iOS：消息编辑（M4-5）→ 设置页逐项 → 扫码读码半边 + 头像裁切页 → 推送（M5）。
8. **群成员头像图**：首字母色块对，但无头像缓存；要先做 `POST /users/batch` 解析器。

## 已知坑 / 限制

- **整份替换的 PUT body 不能用 `ProtocolJson` 直接编**：它 `encodeDefaults=false`（为增量帧设计），
  等于 Kotlin 默认值的字段不上线，而 Go 端缺字段 = 零值 = false。照 `DownloadSettingsApi.putBody` 另起一个
  `encodeDefaults=true` 的 Json，并配线上形状单测。「保存失败」吐司写死「请检查网络」，服务端 4xx 也这么说——易误导排查。
- **`ONLY=X ./scripts/test.sh` 跑不了**：会在 `:media-picker` 报 "No tests found" 失败。
  跑单个类改用一次 gradle 带多个 `--tests`：`./gradlew :app:testDebugUnitTest --tests '*A*' --tests '*B*'`。
- **批量收藏 / 多目标转发的循环挂在聊天页的协程作用域上**：中途离开聊天页，正在发的那一个请求会发完
  （`HttpClient` 用阻塞的 OkHttp `execute`，取消打不断它），但**后面还没发的不再发**，回执吐司也不弹。
- **合并转发的「我」名是 `@句柄`**（`myPublicName()`），不是昵称；单聊引用块不显名（同 iOS）。
- **失效媒体判据只认本次进程里下载器登记过的 404/410**，没有 iOS `IMMediaExpiryRegistry` 那种持久登记——
  没点过的失效图照样会被转出去。
- **`ChatScreen.kt` 594 行、`GroupInfoHost.kt` 568 行、`ChatHost.kt` 566 行**（上限 600）：下一个动的先拆。
- 新消息自动贴底与 ↓ 按钮按**行数**判，键盘跟随按**像素**判，两套口径并存；发送后抑制窗 1s（iOS 0.5s）。
- **视频不转码**、**分片上传不跨进程续传**、**视频没有本地缓存**、**图片不压缩**（只挡 20MB）。
- **查看器只能看单条**（无会话媒体时间线）；归档里语音不能播。
- **没有 instrumented 测试**：布局/滚动/输入法全靠真机截图核对（像素测量用 `adb exec-out screencap` + PIL）。
- **一次性数据订正靠 SharedPreferences 标记**（`SessionStore.msgOpConverged`），别为它加只用一次的索引。
- **「我」页 11 个入口只接通 3 个**；外观令牌层在（`IMAppearance`），无持久化无界面；
  `values-night` 只跟系统深色 → 应用内选浅色时启动闪一下。
- **明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行**；真机连开发机要加内网 IP，且别留到生产。
- **本机 `JAVA_HOME` 是坏的**：`scripts/test.sh` 已自愈；手拼 `./gradlew` 要先
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
- **`SessionStore` 用普通 SharedPreferences**，没有额外加密。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。
- **参考实现**：判据类纯函数可照搬 Web（`entryWindow.ts`/`unreadBelow.ts`/`wake.ts`），
  但守 `../IMServer/docs/SYMMETRY.md`：**对称的是不变式，不是代码形状**。
- 本仓：
  ```bash
  ./scripts/test.sh                # 唯一测试入口（门禁 + 编译 + 单测）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ./scripts/install-hooks.sh       # 每个 clone 一次
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 真机：`GMGY7XF6LBJB6PFU`，adb 在 `~/Library/Android/sdk/platform-tools/adb`。
- 模拟器：AVD `im_test`（API 36 / pixel_5），
  `~/Library/Android/sdk/emulator/emulator -avd im_test -no-snapshot-load -gpu swiftshader_indirect`；
  连宿主机后端用 `10.0.2.2:8080`。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`。
