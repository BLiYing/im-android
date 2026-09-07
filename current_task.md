# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（尚未创建，第一次裁剪本文件前先建）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **群成员列表点进个人资料页 ✅ 2026-09-08（真机验过）** —— 第 ③ 项的第二块。
> 此前 `GroupInfoHost` 的 `onOpenMember` 是空 TODO，**点成员完全没反应**，
> 是那四块里唯一用户能直接撞见的死路。
>
> **没新写页**：本端早有 `UserProfileHost`/`UserProfileScreen`（单聊详情走的就是它），
> 缺的只是接线。两步推导抽成纯函数 `data/MemberProfile.kt`，因为这两步各踩着一个具体的错：
> - **`GroupMember.displayName` 优先取群昵称**。直接拿它当资料卡的 `nickname`，就是把
>   「他在这个群里叫什么」显示成「他的昵称」——别人改一下自己的群昵称，你看到的他的资料页跟着变。
>   种子必须用 `m.nickname`（全局昵称）。
> - **关系与备注不给种子就会闪**：进页先渲染成「陌生人 + 昵称」，拉到名片后跳成「好友 + 备注」。
>   为此把 `MainScreen` 的 `knownRelations`（uid→status）换成 `knownFriends`（uid→整行），
>   备注才有得取。
>
> **点到自己头上单独一档**（`MemberProfile.RELATION_SELF`）：不判的话会给自己显示一个
> 「加好友」按钮。这一档同时把「备注名」行也隐掉（本就只对好友显示）。
>
> **整页替换而不是叠一层**：`GroupInfoHost` 的内容不在自己的 `Box` 里，父布局是谁由调用方决定，
> 叠出来可能是竖排而不是覆盖。替换还顺带让群资料页的滚动位置与成员分页游标原样留着
> （那些 `remember` 都在早退点之上，没被跳过）。返回键用 `enabled = memberProfile == null` 让位。
>
> **真机验过**（OPPO PKD130）四种情况：2000 人大群点成员 → 资料页（服务端确认 `user4836`
> 确实是 `accepted`，压测建了 2006 个好友）；点**自己** → 无任何关系按钮、无备注名行；
> 点好友 → 备注名行 + 发消息 + 删除好友；「发消息」→ 直接进与该成员的单聊。
>
> `MemberProfileTest` 8 例，四条变异（种子用 displayName / 备注不做种子 / 去掉自己那一档 /
> 不判 uid 非空）**各自精确变红**。全量 **293 例 / 47 类**绿。

> **存相册 + 转发入口 + 上传进度 ✅ 2026-09-08（真机验过）** —— 媒体收尾里挑了最便宜、
> 每天碰得到的两条先做（缓存与转码按投入产出比往后放，理由见「已知坑」）。
>
> **① 保存到相册**：查看器右下角一排（转发 + 下载，位置同 iOS `setupCommonControls` 的
> `_downloadButton`——用户靠位置形成肌肉记忆，两端摆得不一样就是两套）。
> **流式拷进 MediaStore**，不先读进 `ByteArray`：一段几百 MB 的视频整包进内存就是 OOM
> （发送侧为此走了分片，存的时候当然也不能倒回去）。图片落 `Pictures/IM`、视频落 `Movies/IM`。
> 与 `ImageExport` **不是复用而是另一条路**：那边吃已在内存里的 `Bitmap`（二维码），
> 这边吃一个地址、要先把字节弄到手；共用的只有 MediaStore 那套 Q 前后分支和相册子目录名。
> 失败时**必须把占位行删掉**——不删就在相册里留一条 0 字节、点开黑屏的条目，
> 用户不会认为「保存失败」，只会认为「这个 App 存出来的东西是坏的」。
>
> **② 上传进度**（`UploadProgress`，clientMsgId → 百分比）：**只活在内存里，不落库**——
> 上传本来就不跨进程续传，把百分比写进 Room 只会在冷启动时显示一条永远停在 43% 的幽灵进度。
> 只覆盖**分片**那条路（视频/文件）；图片走整包上传、压缩后几百 KB，接了只会闪。
> 百分比**向下取整绝不四舍五入**：99.9% 显示成 100% 而气泡还压着暗底，看着就是卡死。
>
> **③ 顺手补掉两个同族缺口**：
> - **待发文件气泡**（`PendingFileBubble`）：文件待发行此前走文本分支，屏幕上是一条绿气泡写着
>   `content://com.android.providers…`。与 2026-09-07 修过的图片/视频那条是**同一个坑**，
>   当时只补了媒体两种。两个待发气泡一起搬进新文件 `ui/screens/PendingBubbles.kt`。
> - **`createPending` 不落 `fileName`/`fileSize`/`caption`**：`resend()` 是从待发行里读这些字段的，
>   行里没有就等于「重发一次文件名和大小就没了」；文件待发气泡也正因此显示不出名字。
>   与 forwardFrom → groupId → 媒体元数据**同一个坑，这是第四次**。
> - `AlbumBubble` 里残留的 `Text("▶")` 文字字形改 Lucide 图标（OPPO ColorOS 用彩色 emoji 字体
>   渲染，会变成橙色方块；VideoPlayer 里刚修过同一条）。
>
> **真机逐项验过**（OPPO PKD130 / Android 15）：
> 存视频 → `Movies/IM/PXL_…LS.mp4` 34.7MB，**md5 `b75da1d5…` 与服务端原件逐字节一致**，
> 服务端的 `req-<id>__` 前缀正确剥掉；转发 → 查看器先关、选择页干净打开无残留黑底；
> 上传进度 → 一段 **404MB** 视频分 ~51 片，进度环 0→35%→100% 全程在，传完干净消失、
> 气泡变成带 5:30 角标的正式视频；文件进度条同样走通（两次发送均 `upload_complete`）。
>
> **补了一处「第一片传完前的空窗」**：`prepare` 之后到第一次 `onProgress` 回调之间没有进度，
> 气泡显示的是播放钮、看着像已经发好了。改成**开传即置 0%**。
>
> **一处没查清、如实记**：第一次发文件时，气泡在上传中途显示过红❗（服务端日志显示那次上传
> 是完整成功的，消息最终也带 ✓ 落地）。第二次发同一文件、14 次逐秒抓图**没能复现**，
> 未找到根因。注意这不是本轮引入的：`failed` 判据没动，我只是让这个状态**被看见了**
> （此前它挂在一条 `content://` 文本气泡旁边）。下次撞见先抓 Room 的 pending 行。
>
> 全量 **285 例 / 46 类**绿（新增 18 例）。变异验证 7 处，**6 处精确变红**，
> 1 处没红并查明原因：`report` 里手写的「同值短路」是死代码，`StateFlow` 本身按 `equals`
> 合并——已删守卫、留测试（钉的是行为不是实现）。
>
> 另修 `../IMServer/docs/CLIENT_PARITY.md` 一处**列错位**：上一轮把 Android 的查看器说明
> 写进了 Desktop 列。

> **媒体查看器：视频可播、图片可缩放 ✅ 2026-09-08（真机验过）** —— 你批准顺序里的第 3 步，
> 也是我上一轮自己捅的缺口（刚让视频能发，点开却没反应）。
>
> Media3/ExoPlayer。**控件自绘**（`PlayerView` 只借 Surface 与 `resizeMode`，`useController=false`）：
> 自带控制条有自己一套 Material 配色，与 `IMTheme` 令牌对不上、也不跟随聊天主题色；
> iOS 侧同样是手搓 `_playButton`/`_scrubber`/`_timeLabel`。
>
> 对齐 iOS 的两条：**封面先显、点了才播**（`IMMediaViewerViewController` 的 `_started` 门控，
> CLIENT_PARITY 任务3 记作「封面待点不自动播」）；**离开就停**（`DisposableEffect` 释放 +
> 跟随生命周期 onStop 暂停，对应 iOS `viewDidDisappear` 里的 pause）。
>
> **图片那半复用 `:media-picker` 的 `ZoomableImage`**，没另写一份。为此把该模块的
> `LocalPickerImageLoader` 默认值从 `error(...)` 改成回落 Coil 单例——**导出一个只能在自家内部跑的
> 组件等于没导出**，这是上一轮埋的坑。
>
> **顺手修掉两个既有问题**：
> ① **返回键会直接退出整个聊天页**：`ChatHost` 原本只有一个无条件的 `BackHandler(onBack)`，
>    覆盖层（转发/选图/选联系人/查看器）是逐个加上去的，每加一个都没人想起返回键。
>    改成按 `ChatOverlays.Layer` 逐层关，层序 = 渲染顺序，有测试钉着。
> ② **控件用 "⏸"/"▶" 文字字形**：OPPO ColorOS 用彩色 emoji 字体渲染，暂停键成了橙色方块。
>    改用 Lucide 图标。
>
> **真机抓到一个只在冷缓存下出现的 bug**：`prepare()` 在组合期（`remember` 里）就调了，
> IDLE→BUFFERING→READY 可能在监听器挂上**之前**就走完——那些回调收不到，于是 `buffering`
> 恒为 false，缓冲期间显示的是大播放钮，看着像刚才那下没点上，**再点一次反而暂停**。
> 改法：挂监听前先读一次当前状态。实测点播放后 6 秒仍是播放钮 + `0:00/0:00`，修后正常转圈。
>
> 真机逐项验过：点视频进查看器、封面不自动播、播放/暂停/进度/时长、播放中重新缓冲显示转圈、
> 返回只关查看器不退会话、宫格逐格点开。全量 **267 例 / 44 类**绿。

## 下一步

**0. 第 ③ 项剩余是下一件事**（用户 2026-09-08 指示：媒体收尾后回到这条线）：
   ~~成员资料页~~ ✅ 2026-09-08。**还剩三块**：单聊详情页、群治理开关组 UI
   （`GroupApi.updateSettings` 已接，缺界面）、入群申请审批列表（判据 `canReviewJoin`
   已就位、UI 未做，所以管理卡里刻意不列这一项）。

**0. 群成员头像图 URL 缺失**：`showsSenderAvatar` 挂的是首字母色块（取色三端同源，颜色对），
   但没有头像图——群成员头像无本地缓存。要接得先做 `POST /users/batch` 解析器（CLIENT_PARITY 有这行）。
**0. 第 ③ 项剩余**：入群申请审批列表（判据 `canReviewJoin` 已就位、UI 未做，
   故管理卡里**刻意不列这一项**）、群治理开关组 UI（`updateSettings` 接口已接）、
   成员资料页（`onOpenMember` 仍是 TODO）、单聊详情页。
**0-. 多选（M4-3 的另一半）**：长按菜单的「多选」本轮**刻意没加**——不带着只会弹
   「还没做」的死菜单项交付。`Forward.toggleCapped` 与 100 条上限已就位并测过，
   缺的是多选态 UI + 底部批量栏。
**0a. 合并转发（chat_record 一张卡片）本端没做**：现在只有逐条转发。
   合并转发要一整套卡片渲染 + 匿名 sender key（im-web `useForward.ts` 那套），是独立一块。
**0b. ~~系统消息渲染~~** ✅ 实体机实测通过（见当前焦点）。原文：**系统消息渲染已改成居中灰字**（`SystemNote`）——但**只过了编译与单测，没实测**：
   模拟器在做这一步时整体 ANR（连 Pixel Launcher 都卡死，机器负载过高），
   下次起模拟器第一件事就是看一眼群聊里的「XX 被设为管理员」是不是居中灰字。

按 `../IMServer/docs/CLIENT_PARITY.md` 追 iOS，优先级从高到低：

1. **转发 / 多选 / 收藏**（M4-3、M4-4）：长按菜单现在缺这三项，是 iOS 用得最多的一批。
2. **消息编辑**（M4-5）：`msg_op op=edit` 协议侧已接，缺 UI。
3. **会话详情页**（媒体/文件/链接归档，M4.5-3）。
4. **设置页逐项**（外观/字号/主题偏好等 9 项）：「我」页入口列表已经把位置占好了，
   点进去是「还没做」；`IMAppearance` 令牌层已就位，缺持久化与界面。
   接外观时一并解掉 `values-night` 只跟系统深色导致的启动闪烁（见已知坑）。
5. **群管理写操作**（改群名/公告/禁言/踢人/转让）：`GroupApi` 有骨架，缺写接口与 UI。
6. **@提及**（`mention_spans`，M4-8）：协议字段已在 DTO 里，缺高亮与 @面板。
7. **扫一扫 / 群码 / 扫码登录**（QR P0 的其余部分 + P1）：出码那半边已经做了，
   **读码**这半边需要相机权限与 ML Kit/CameraX，工作量单独一档。
   另欠**头像裁切页**——现在是自动居中方裁，竖幅全身照会裁掉头（iOS 有 `IMAvatarCropViewController`）。
8. **推送**（M5）：依赖 FCM，iOS 侧也还没做。

## 已知坑 / 限制

- **视频不转码**：iOS 会转 720p H.264 再发（`IMMediaPicker`），本端原样直传。
  好处是快且无损，坏处是 iPhone 拍的 HEVC 在部分收端只能看封面点不开——封面有了，播放没解决。
  真要转码得上 MediaCodec，是独立一块。
- **分片上传不跨进程续传**：`upload_id` 只活在这次调用里，杀进程要重传。
  服务端 `/upload/{id}/status` 是支持续传的，缺的是把 `upload_id` 落库。
- **上传进度只到「整条」粒度**：相册宫格里**每格**的环形进度（iOS 有）没做；
  图片走整包上传，没有进度也不打算加（压缩后几百 KB，接了只会闪）。
- **视频没有本地缓存**：每次打开查看器都重新下载整段（本端未配 `SimpleCache`）。
  iOS 有 `IMOriginalVideoCache`。**刻意往后放**：收益只是重复打开同一段省流量，
  而代价是 `SimpleCache` 同一目录只能被一个实例持有（多进程直接抛）+ 要自己定配额与清理策略——
  四条媒体欠账里投入产出比最差的一条。
- **查看器只能看单条**：iOS/Web 还能在会话媒体时间线上左右翻页（CLIENT_PARITY 任务3），
  那要先有「会话媒体列表」这套数据，是独立一块。保存/转发已接，**删除**入口仍没有。
- **存相册的 Q 以前分支没实测**：本机是 Android 15，走的是免权限的分区存储那条；
  `WRITE_EXTERNAL_STORAGE` 那条（Android 9 及更早）只有代码没跑过。
- **媒体失效没有本地登记**：iOS 有 `IMMediaExpiryRegistry`，存之前就能拦下「已被服务端清理」的
  地址并说「该文件已失效」。本端没有，只会走到 HTTP 失败、报一句「保存失败」。
- **选图器的预览页仍不播视频**：只显首帧 + 时长 + 「预览不播放」。
  查看器已经能播了，这里要不要接同一个播放器是个产品选择（微信的选图预览也不播）。
- **相册分页只在页大小调成 4 时验过**：真实 PAGE=300、本机只有 12 张素材，
  日常走不到续页路径。逻辑有 `PageMergeTest` 覆盖，查询层（Bundle 参数 vs 老写法）只验了 Android 11。

- **`CLIENT_PARITY.md` 的 Android 列已补齐**（2026-09-07），「我」页相关四行同日更新。
  填法保守——不确定的一律往低了填，所以可能低估、不会高估。
- **布局验证靠像素测量，不是靠肉眼**：`adb exec-out screencap` 出图后用 PIL 扫，
  比对 `UI_SPEC.md` 的规格值。本轮就靠它纠正了一次目测误判。**但这是手工的**，
  没有自动化——真正的回归还得靠 Robolectric/Compose 或截图基线，两者都还没做。
- **没有 instrumented 测试**：`androidTest` 依赖配好了但一个用例没写。
  Compose 的布局/滚动/输入法这类只能靠模拟器手测，目前全靠人肉截图核对。
- **媒体只做了图片**：视频/语音/文件只能**看**不能**发**，也不能播放/下载。
  语音气泡的波形是等高条纹占位（`waveform` 字段还没解析）。
- **图片不压缩**：只在端上挡 20MB，超了直接不发。iOS/Web 都会先压。
- **消息编辑、转发、多选、收藏、@提及、扫码全未做**。
- **群管理只读**：能看群资料与成员、能退群，不能改群名/公告/禁言/踢人/转让。
- **「我」页 11 个入口里只接通了 3 个**（设备 / 资料 / 二维码），其余 8 个点了是「还没做」提示。
  主题/字号/壁纸等外观偏好的令牌层在（`IMAppearance`），但没有持久化也没有界面。
- **头像没有裁切页**：选图后自动居中方裁 512×512 再传，竖幅全身照可能裁掉头。
- **`values-night`** 见下条。
- **`values-night` 只管启动窗口且跟随系统深色**：用户在应用内选浅色时，
  启动那一瞬间仍是深色底 → 闪一下。接主题偏好时一并解决。
- **明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行**。
  真机连开发机要把内网 IP 加进 `res/xml/network_security_config.xml`，**且别留到生产**。
- **本机 `JAVA_HOME` 是坏的**（Homebrew 未替换占位符 `@@HOMEBREW_JAVA@@`）。
  `scripts/test.sh` 已自愈；手拼 `./gradlew` 要自己
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
- **启动图标是占位**（绿底白气泡矢量图）。
- **`SessionStore` 用普通 SharedPreferences**：应用私有目录，未 root 读不到，
  但**没有额外加密**（`androidx.security:security-crypto` 已废弃）。要再加一层得自己包 Keystore。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。
- **参考实现**：判据类的纯函数可直接照搬 Web（`entryWindow.ts`/`unreadBelow.ts`/`wake.ts`），
  但要守 `../IMServer/docs/SYMMETRY.md` 那条：**对称的是不变式，不是代码形状**。
- 本仓：
  ```bash
  ./scripts/test.sh                # 唯一测试入口（门禁 + 编译 + 单测）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ONLY=EnvelopeTest ./scripts/test.sh
  ./scripts/install-hooks.sh       # 每个 clone 一次
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 模拟器：AVD 名 `im_test`（API 36 / pixel_5）。启动：
  ```bash
  ~/Library/Android/sdk/emulator/emulator -avd im_test -no-snapshot-load -gpu swiftshader_indirect
  ```
- 模拟器连宿主机后端：`10.0.2.2:8080`（`127.0.0.1` 在模拟器里指模拟器自己）。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`。
