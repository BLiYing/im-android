# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **第二批用户报告（Android 部分）✅ 2026-09-15（未提交；662 条单测全绿、变异验红、代码复查无正确性问题；模拟器实测未完成**——
> im_test 冷启动后 systemui / system / IM 连续 ANR、输入丢字，放弃）。逐条见 IMServer `docs/CLIENT_PARITY.md` 顶部：
> ① 「新的朋友」角标 `c.danger` → `c.unreadBadge`，并补高/最小宽 `d.unreadBadgeHeight` + 居中（同 `UnreadBadge`）；
> ② **改昵称后老消息仍显旧名**（iOS/Web 的 bug，本端普通群本不中招）：发送者名链统一为「备注 > 成员表 > 本窗最新快照 >
>    本条快照 > 好友名」（`data/SenderNames.kt` + `SenderNamesTest`，调用点 `ChatRowView.senderNameOf`）——原先好友昵称压过群昵称、
>    超级群非好友落老快照；会话开着时末条昵称对不上成员表 → 重拉群资料（`ui/MemberNameRefresh.kt`，5s 节流）。
> 顺带：`ChatScreen.kt` 598 行、`ChatHost.kt` 576 行，贴近 600 门禁，再加东西先拆。`ONLY=<类> ./scripts/test.sh` 在多模块下会被
> `:media-picker` 报 "No tests found" 中止（过滤没限定 `:app`），跑单类用 `./gradlew :app:testDebugUnitTest --tests`。

> **用户报的三条 ✅ 已改、未提交、未真机手测**（2026-09-15，差异登记 `docs/UI_PARITY_IOS.md` §3.5 / §4）：
> ① **文件文不显示文字**——图说判据挂在「贴边媒体」上，文件气泡不贴边，字整段不画。判据抽到
> `data/BubbleCaption.kt`（`Bubbles.kt` 用它），聊天记录详情页文件项同漏、一并补；
> ② **二级页底部 Tab 栏一直显示**——「通讯录」「我」的二级页在底栏上方的内容区原地切换。改成各 Host 根页经
> `ui/TabRoot.kt` 插槽自己画底栏（判据 `data/PushNav.kt` 的 depth；页面枚举 `ContactsPage`/`MePage` 移到 data 带深度）；
> ③ **没有 push 转场**——新增 `ui/components/PushTransition.kt`（AnimatedContent；退场页换一个脱离 Activity 的返回键分发器
> + Initial 阶段吞触摸，防连按返回被吞、防点到滑走中的页）与 `PushBase`（聊天页被详情盖住时只让开、不出组合）。
> 单测 `BubbleCaptionTest`(5) / `PushNavTest`(6)，三处变异验红。
>
> **「我 ▸ 隐私与安全」✅ 已实现，未真机手测**（2026-09-11，对齐 iOS `IMPrivacySecurityViewController` 三页，
> 差异登记 `docs/UI_PARITY_IOS.md` §4.9）：容器页五组（活行只有「已屏蔽的用户」「修改密码」，其余四组灰置占位）/
> 已屏蔽的用户（计数、左滑取消屏蔽、空态三层、刷新失败保留旧内容）/ 修改密码（眼睛切换、本地校验、按业务码红字 + 描红）。
> 判据在 `data/PrivacySecurity.kt` 与 `data/ChangePasswordRules.kt`，持有者 `ui/PrivacySecurityHost.kt`。
>
> **做法上非显然的点**：① **改密应答里的 `refresh_token` 必须接住**——那是续期凭据唯一的轮换点，服务端这一刻已作废旧的；
> 接不住的话本机在 access token 下次过期时被登出。故 `IMClient.changePassword` 整段 `NonCancellable`、Host 挂 `client.scope`；
> ② 参数错是 **100001**，iOS 写成了 100002（限流码），本端不照抄、有单测钉住；
> ③ 按钮「三个框都填了就亮」取 Web 口径——iOS「校验全过才亮」让那几句原因提示永远显示不出来。
>
> **上一轮「数据和存储」✅ 已实现、未真机手测**（三层设置页 + `capabilities_update` 同步；真机首测撞的「保存失败」已修：
> PUT body 多套了一层 `settings`，且 `encodeDefaults=false` 会把 true 默认值省掉被 Go 解成 false）。
>
> 更早的「聊天页第三轮九条」仍待真机手测（清单在 `docs/UI_PARITY_IOS.md` §4.8）。

## 下一步

0. **真机手测 2026-09-15 三条**：① 「libeyond群」倒数三四条文件文出字、时间在最下靠右、长按浮起的那份也有字；
   ② 通讯录/「我」每个二级页底栏消失、页面铺到底，返回后底栏回来；从通讯录资料页「发消息」进聊天再返回，底栏在；
   根页列表底部与底栏之间**不该再有一条空白**（`TabRoot` 吃掉了导航栏 inset，属顺手改，要眼睛确认）；
   ③ 转场方向与层叠（前进新页在上、后退滑走页在上）、转场中连按返回 / 连点列表不出错、
   聊天↔详情来回聊天列表原位不动、详情里点成员「发消息」换会话。确认后提交。
1. **转场没接的几处**（见 UI_PARITY_IOS §4 那行）：群资料 / 聊天信息内部子页等，内容读已置空的状态，要先改成按转场状态渲染。
2. **真机手测隐私与安全**：计数随拉黑/取消变化；左滑取消屏蔽后行消失、清空后出空态；
   改密码三种红字（旧密码错 / 太短 / 不一致）与描红、眼睛切换不吞字、键盘不挡按钮；
   **改密成功后杀进程等 token 过期（或手动清 token）再进，确认不被弹回登录页**；另一台设备确实被下线。
2. **真机手测数据和存储**：滑杆拖动与松手吸附、总开关关时档位置灰、自定义第四档的出现与消失、
   清除缓存后气泡退回「未下载 ↓」、在 Web/iOS 改策略后本机页面跟着变（验 `capabilities_update`）。
3. **提交**：上面两轮一起或分开提交（文件交叠，分开提交要按 hunk 挑）。
4. **等用户真机手测第三轮九条**，重点：键盘收起时点回复条跳原消息；合并转发收端卡片的标题与名字；
   举报灰钮提示；批量收藏回执；逐条转发含失效媒体时的提示。
5. **收藏的另一半**：收藏列表页、删除、长按菜单「收藏」、附件面板「从收藏发送」（`AttachItems.Kind.Favorite` 仍占位）。
6. **长按菜单缺项**：单条举报 / 收藏 / 翻译（`CLIENT_PARITY` 消息长按菜单那行）。
7. **宫格**：按 `IMAlbumRowPattern` 重写布局（每行列数决定格宽、1 列行高固定 150）+ 五道防跳版闸；另欠相册宫格逐格勾选。
8. **转发选择页**：可下滑、标题「转发到」居中、剔除系统通知会话。
9. 按 `docs/UI_PARITY_IOS.md` 剩下的 🔴：会话内搜索的📅日历与👤发件人过滤、水滴头部形变、语音页签内播放、「名片」页签、隐私页无障碍提示。
10. 按 `CLIENT_PARITY` 追 iOS：消息编辑（M4-5）→ 设置页其余 6 项 → 扫码读码半边 + 头像裁切页 → 推送（M5）。
11. **群成员头像图**：首字母色块对，但无头像缓存；要先做 `POST /users/batch` 解析器。

## 已知坑 / 限制

- **改密后的新续期凭据只出现一次**：任何新的「会让服务端轮换凭据」的调用都要照 `IMClient.changePassword` 那样
  `NonCancellable` 接住，**并带上发起时的 uid**——应答可能晚于退出登录/换号回来，照写会在清空的本机里复活凭据
  （判据 `TokenSession.shouldAdoptRotated`，`RotatedRefreshGuardTest`）。落盘本身没有 JVM 单测（`SessionStore` 要 Context），
  字段名靠 `ChangePasswordWireTest`、服务端轮换语义靠 `temp_verify.py --e2e-privacy`。
- **`TokenSession.logout` 的清空拿 `refreshLock`**（2026-09-11 起）：保证清空是最后一次写；代价是撞上在途续期时退出要等那个请求回来。
- **改密提交途中离开整个「隐私与安全」**：凭据照样落盘，但成功/失败吐司看不到（写进了已离开的页面状态；iOS 同）。
- **iOS 改密「参数错」码写错（100002）未修**——要修去 IMProgram `IMChangePasswordViewController.m` 顶部常量。
- **整份替换的 PUT body 不能用 `ProtocolJson` 直接编**：它 `encodeDefaults=false`（为增量帧设计），
  等于 Kotlin 默认值的字段不上线，而 Go 端缺字段 = 零值 = false。照 `DownloadSettingsApi.putBody` 另起一个
  `encodeDefaults=true` 的 Json，并配线上形状单测。「保存失败」吐司写死「请检查网络」，服务端 4xx 也这么说——易误导排查。
- **`ONLY=X ./scripts/test.sh` 跑不了**：会在 `:media-picker` 报 "No tests found" 失败。
  跑单个类改用一次 gradle 带多个 `--tests`：`./gradlew :app:testDebugUnitTest --tests '*A*' --tests '*B*'`。
- **JVM 单测里 `android.util.Log` 是桩、一调就抛**：测带日志的路径先 `IMLog.useSinksForTest()`，
  否则异常会被业务 catch 吞成「失败」。单测编译类路径是 android.jar，**没有 `com.sun.net.httpserver`**，
  要本机 HTTP 服务用 `MediaDownloaderTest` 里的 `TinyHttpServer`。
- **数据和存储里「图片」那一行照抄 iOS 恒写「对所有聊天启用」**，不看单聊/群聊开关——要改三端一起改。
- **策略保存挂在 `client.scope` 上**（不随页面取消）；但乐观值期间若恰好到一个同版本的重拉应答，界面会闪回旧值一下，
  PUT 应答到了再回来（版本判据 `>=` 的代价，换来回滚能用同版本重拉）。
- **批量收藏 / 多目标转发的循环挂在聊天页的协程作用域上**：中途离开聊天页，正在发的那一个请求会发完
  （`HttpClient` 用阻塞的 OkHttp `execute`，取消打不断它），但**后面还没发的不再发**，回执吐司也不弹。
- **合并转发的「我」名是 `@句柄`**（`myPublicName()`），不是昵称；单聊引用块不显名（同 iOS）。
- **失效媒体判据只认本次进程里下载器登记过的 404/410**，没有 iOS `IMMediaExpiryRegistry` 那种持久登记——
  没点过的失效图照样会被转出去。
- **`ChatScreen.kt` 594 行、`GroupInfoHost.kt` 568 行、`ChatHost.kt` 566 行、`MessageRepository.kt` 565 行、
  `MessageService.kt` 556 行**（上限 600）：下一个动的先拆。
- 新消息自动贴底与 ↓ 按钮按**行数**判，键盘跟随按**像素**判，两套口径并存；发送后抑制窗 1s（iOS 0.5s）。
- **视频不转码**、**分片上传不跨进程续传**、**视频没有本地缓存**、**图片不压缩**（只挡 20MB）。
- **查看器只能看单条**（无会话媒体时间线）；归档里语音不能播。
- **没有 instrumented 测试**：布局/滚动/输入法全靠真机截图核对（像素测量用 `adb exec-out screencap` + PIL）。
- **一次性数据订正靠 SharedPreferences 标记**（`SessionStore.msgOpConverged`），别为它加只用一次的索引。
- **「我」页 11 个入口接通 5 个**（设备 / 资料 / 二维码 / 数据和存储 / 隐私与安全）；外观令牌层在（`IMAppearance`），无持久化无界面；
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
  python3 temp_verify.py           # 两轮交付前静态自检（先跑 test.sh）
  python3 temp_verify.py --e2e-privacy --bin <imserver> --workdir <scratch>   # 隔离实例实测改密轮换
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 真机：`GMGY7XF6LBJB6PFU`，adb 在 `~/Library/Android/sdk/platform-tools/adb`。
- 模拟器：AVD `im_test`（API 36 / pixel_5），
  `~/Library/Android/sdk/emulator/emulator -avd im_test -no-snapshot-load -gpu swiftshader_indirect`；
  连宿主机后端用 `10.0.2.2:8080`。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`。
