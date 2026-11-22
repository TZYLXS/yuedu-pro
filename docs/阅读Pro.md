# 阅读Pro · 改动说明

底座：[LegadoTeam/legado](https://github.com/LegadoTeam/legado) tag `3.23.110211`
（commit `56c3301dc441c23aaa75bac973943f7469c202be`），不是当前 HEAD。

选这个 tag 的原因：它的依赖组合（AGP 8.1.2 / Gradle 8.3 / Kotlin 1.9.20 / media3 1.1.1 /
room 2.6.0 / okhttp 4.11.0 / compileSdk 34）在 JDK 17 上可稳定构建，且源码已按 targetSdk 33 适配。

## 相对 API19 版（yuedu-android4）的改动

| 位置 | 改动 |
| --- | --- |
| `app/build.gradle` | `applicationId` → `io.legado.pro`（与官方版、API19 版共存） |
| `app/build.gradle` | `minSdk 19 → 24`，`targetSdk 30 → 33` |
| `app/build.gradle` | 新增 `-PtargetAbi=armv7/arm64` 开关，决定 `abiFilters` 与产物名后缀 |
| `app/build.gradle` | `versionCode 19001 → 3200001`（当前 3.2.0.1），输出名 `${appName}_${flavor}_${version}${abiSuffix}.apk` |
| `modules/book`、`modules/rhino1.7.3` | `minSdk 24`、`targetSdk 33`，与主模块一致 |
| `res/values*/strings.xml` | `app_name` → 阅读Pro（含 zh / zh-HK / zh-TW 三个语言目录） |
| `AndroidManifest.xml` | 删除 `<uses-sdk tools:overrideLibrary=...>`（三方库 minSdk 21 < 24，合并无需 override） |
| `README.md`、`docs/*` | 改为 Pro 版说明 |

## 从 API19 版继承、在 API24+ 上不再触发的补丁

这些代码保留但走不到，删了反而增加回归风险，因此原样留着：

- `help/http/Tls12SocketFactory.kt`：只在 `SDK_INT < 21` 时补 `TLSv1.2`
- `help/TTS.kt` 的 `speakCompat`：`SDK_INT >= 21` 走四参 `speak`
- `ui/file/HandleFileActivity.kt`：仅 `SDK_INT < LOLLIPOP` 回退 `FilePickerDialog`
- `modules/book/.../PfdHelper.java`：`@RequiresApi(21)` 分支优先，19 的 `FileInputStream` 回退不会进
- `App.kt` 的 `MultiDexApplication` + `multiDexEnabled true`：minSdk 24 本身原生支持 multidex，留着无害

同样继承下来的移除项：Cronet（整包删除，全部走 OkHttp）、Firebase（analytics/perf 依赖注释掉）。
如需恢复 Cronet，需要重新引入 `app/cronetlib/*.jar` 与 `download.gradle`，并接受 `Cronet_Version` 构建字段的取值来源。

## 签名

`keystore/yuedu.jks`（alias `yuedu`，storepass/keypass `yuedu2026`，JKS，36500 天），**不入库**。

APK 里同时有 `META-INF/CERT.SF` / `CERT.RSA`（v1）与 v2 签章：

- `jarsigner -verify` → 「jar 已验证」（自签证书会提示 PKIX 路径，属正常）
- `apksigner verify --verbose` → `v1 false / v2 true`。这是 apksigner 在 minSdk ≥ 24 时不再校验 v1 的表现，不是签名缺失

Android 7.0 起系统按 v2 校验，v1 只是给仍做 JAR 校验的老安装器留的兜底。

## 构建与验证结果

环境：JDK 17（`jdk-17.0.21+9`）、Android SDK platform 34 + build-tools 34.0.0、Gradle 8.3（wrapper）。

```bash
./gradlew :app:assembleAppRelease -PtargetAbi=armv7
./gradlew :app:assembleAppRelease -PtargetAbi=arm64
```

注意：同一 variant 连续构建会清空 `app/build/outputs/apk/app/release/`，
所以每打完一个 ABI 要立刻把 APK 拷到 `dist/`，否则第二个 ABI 会覆盖掉第一个。

产物：

| APK | 体积 | ABI | 校验 |
| --- | --- | --- | --- |
| `yuedu_pro_app_2.0.2-armv7.apk` | 17.83 MB | armeabi-v7a | `sdkVersion 24` / `targetSdkVersion 33` / `io.legado.pro` / v2 签名 |
| `yuedu_pro_app_2.0.2-arm64.apk` | 17.88 MB | arm64-v8a | 同上 |

原生库（每个 APK 只带自己那一套）：

- `lib/armeabi-v7a/` 或 `lib/arm64-v8a/`：`libarchive-jni`、`libimage_processing_util_jni`、`librenderscript-toolkit`

DEX：6 个（`classes.dex` ~8.6MB、`classes2.dex` 仅 2.9KB、`classes3.dex` ~0.46MB、`classes4.dex` ~8.3MB、
`classes5.dex` ~9.8MB、`classes6.dex` ~2.6MB），minSdk 24 由系统原生处理 multidex，不需要安装 multidex 补丁。

## 还没做的

- **真机运行验证**：测试机 adb 掉线，未实测启动、搜索、阅读。接上 USB 后跑 `scripts/verify_device.bat`。
- 应用内「检查更新」仍指向 Legado 官方仓库，换成本仓库的 Release 需要改 `AppUpdateService` 的地址。
- 未做混淆/资源压缩（`minifyEnabled false`），体积偏大，后续可按需开启。

## 3.2.0.1（versionCode 3200001）：同步服务端 3.2.0，菜单重设计

服务端 `3.1.4 → v3.2.0`（main `4392041`），客户端 `/api/m/*` 契约未变：

- `docs/APP同步接口.md` 仅新增「多客户端 / 多书源并发合并（0.1.25+）」说明：同书按 workId 归并、章节取并集、同章不同正文保留 variants、`raw/<srcHash>/` 分开存放、响应字段 `sources/added/merged/duplicates/splitMismatch` 沿用现有契约；无新增客户端必填字段
- `docs/软总线路由与权重.md`、`docs/AI构建指南.md` 在 v3.2.0 未变化
- 服务端 3.2.0 主要变更：多源缓存合并、新增「书籍」页与本地 `.nb` 缓存清单、设置页重排、IPv6 独立成行、书源导入链接修复、增量哈希/流式导出/Python 池扩核、修复阅读接入详情 404
- 客户端本版：FnReader 同步通知分道、同步记录页、空书源过滤；配置菜单改分组列表（IP/TOKEN 弹框、TOC/TRE 滑动开关、单行动作与进度框）
- 版本按「服务端版号 + 0.0.0.1」：`3.2.0` → `3.2.0.1` / `3200001`

## 3.1.4.1（versionCode 3010401）：同步服务端 3.1.4，修复书源 404 永久跳过

服务端从 `3.0.10` 连发到 `v3.1.4`（tag/main `b230d8e`），客户端 `/api/m/*` 契约未变：

- 3.1.0–3.1.4：TOC 规范化转 Python，兼容 Python 3.9 / `vendor39`；电子书扫描跳过 cache/covers/tmp；清理失效书架条目；`linkLocalEbooksToShelf` 在索引为空时先清幽灵书再补索引
- `docs/APP同步接口.md` blob 仍为 `f4b8c9f957a1d609eb0a231123fadaa196ec5644`；`docs/软总线路由与权重.md` 仅新增服务端回传解析容错说明，无新增客户端必填字段
- 客户端修复：`FnReaderAutoSync.runSources` 遇 404 改为 6 小时时间戳退避，成功同步后清除标记；旧值 `1` 自动按过期处理
- 新增 `docs/客户端书源反向同步.md`：`POST /api/m/sources`、`GET/POST /api/m/sources/sync`、5 分钟自动同步与验收步骤
- 版本按「服务端版号 + 0.0.0.1」：`3.1.4` → `3.1.4.1` / `3010401`

## 3.0.10.1（versionCode 3001001）：同步服务端 3.0.10，无客户端代码改动

服务端从 `3.0.8` 连发到 `v3.0.10`（tag `255c51c`）。本版仅同步服务端版号，客户端代码零改动：

- `3.0.9`：TXT 导出不再占用规则解析 Python 进程池，改用一次性独立进程；60s 硬超时，超过 4000 章直接 JS 拼装；stdin EPIPE 不再触发 uncaughtException
- `3.0.10`：启动脚本依次探测 `nodejs_v22/v24/v20/v26` 与系统 node，逐个 `node -v` 校验，跳过缺 libatomic 等损坏运行时，日志记录实际 Node，修复全新安装启动失败
- 以上均为服务端内部实现；`docs/APP同步接口.md` blob 仍为 `f4b8c9f957a1d609eb0a231123fadaa196ec5644`，`docs/软总线路由与权重.md`、`docs/AI构建指南.md` 也未变，`/api/m/*` 契约不变
- 客户端回传、软总线、封面/书源/缓存通道沿用 3.0.2.1
- 版本按「服务端版号 + 0.0.0.1」：`3.0.10` → `3.0.10.1` / `3001001`

## 3.0.8.1（versionCode 3000801）：同步服务端 3.0.8，无客户端代码改动

服务端从 `3.0.2` 连发到 `v3.0.8`（tag `eb15a61`）。本版仅同步服务端版号，客户端代码零改动：

- 书城兜底：缺封面/简介/评分时自动补全（Web 端新增 `/api/books/enrich`，只填空字段）
- TXT 导出：单本与书架批量改由 Python `export_txt` 处理；导出全部改为异步任务，新增 `/api/export/books-txt/status`，网页轮询章节进度
- TOC 客户端代理解析保持最高优先级；`/api/engine` 探测最多等 1.5 秒，Java/Python 后台启动，不再卡设置页
- `docs/APP同步接口.md` 与 `/api/m/*` 契约未变；6 类回传、软总线、封面/书源/缓存通道沿用 3.0.2.1
- 版本按「服务端版号 + 0.0.0.1」：`3.0.8` → `3.0.8.1` / `3000801`

## 3.0.2.1（versionCode 3000201）：按 3.0.x 客户端适配清单补齐自动回传

服务端已到 `3.0.2`，本版按 `3.0.x更新与客户端适配清单.md` 补齐 6 类回传里缺失的自动化能力：

- **加书/改书自动回传**：`Book.save()` 落库后调用 `FnReaderAutoSync.scheduleBookPush()`，2 秒防抖后 `POST /api/m/books`；
  未配置服务器或软总线未开启时内部直接跳过。
- **阅读进度自动回传**：`ReadBook.saveRead()` 保存后调用 `scheduleProgressPush()`，3 秒防抖 `POST /api/m/progress`；
  服务层每轮再按 `fnReaderProgressSyncedMs` 水位批量 `POST /api/m/progress/batch`，只推水位之后的变更。
- **独立封面上传**：新增 `POST /api/m/cover`，JSON 为 `{bookUrl,mime,data:"data:image/...;base64,..."}`，
  单张上限 8 MB；服务端返回 404 时写入 `fnReaderNoCoverApi=1` 并停止后续尝试。随 books 的 `coverData/coverUrl` 保留为兼容路径。
- **书源双向同步补齐删除**：自动同步改为先读本机书源快照，本机删除的书源写入 `deleted` 数组；
  `POST /api/m/sources/sync` 同时带 `updated` 与 `deleted`，成功后刷新快照；`lastUpdateTime<=0` 的旧书源自动补当前时间戳。
- **自动补书**：服务端有、本机没有的继续拉取；本机有、服务端没有的书按书名归一化去重后回传，
  同名不同源只计数跳过，避免把重复书推上去。
- **版本号**：按「服务端版号 + 0.0.0.1」对齐为 `3.0.2.1` / versionCode `3000201`（仍高于旧版 `2063001`，可覆盖升级）。

## 2.6.30.1（versionCode 2063001）：软总线 2.6.19 / 上游 3.0.0 协议跟进

上游 `78231d1`（3.0.0）在 `remote.js` / `busweights.js` 引入按类额度与超时建模，本版有真实代码改动：

- `FnReaderRemote.Job` 增加 `timeoutMs` 并解析；`poll()` 支持上报 `cores` / `mem` / `inflight` / `supports`，
  参数名与服务端 `server.js` 的 `cores/mem/inflight/supports` 完全一致；
- `FnReaderBridgeService` 新增 `budgetOf(job)`：服务端下发的预算优先，留 500 ms 回传余量、不低于 3 秒；
  新增 `JOB_TIMEOUT_MEDIA = 7000`，`cover` / `intro` 不再落到默认 20 秒；
- 能力上报值：`cpuCores`、`Runtime.maxMemory()` MB、`searchConcurrency + tocConcurrency + infoConcurrency`、
  支持的任务类型列表；
- 版本号：上游进入 3.x 后按旧规则会得到 `2.6.3.1`（2060301），低于 2.6.18.2 会被判降级，故重定为
  `2.6.30.1`（2063001），后续按 `2.6.3x.y` 递增。

## 2.6.18.2（versionCode 2061802）：仅跟进文档，无代码改动

上游 `ecf0408..c3922a3` 只有两份文档提交（`6bfdbdc6`、`c3922a35`），修改 `AGENTS.md` 与 `docs/软总线调试APK.md`：

- `AGENTS.md` 新增上游环境专用的工具调用约定，不影响本客户端代码或接口。
- `docs/软总线调试APK.md` 新增 §6.3，明确 `GET /api/bus` 的设备字段 `id`、`name`、`model`、`version`、`lastSeen`、`handled`、`enabled`、`debug`、`online`、`idle`，以及顶层 `pending`、`recent`、`usage`；这些是面板⑦的只读信息，现有 `GET /api/bus` 调用无需调整。
- 三份重点文档的 blob SHA 与 2.6.18 基线一致；`/api/m/*` 路径、字段、鉴权、任务类型、超时全部未变。
- 文档口径同步校正：`cover` / `intro` 没有独立 `timeoutOf` 分支，当前实际落 default **20 秒**；`bookinfo` 仍为 9 秒。本版只修正文档，不改代码。

## 2.6.18.1（versionCode 2061801）：跟进加权路由，新增 cover/intro 与幂等识别

服务端 2.6.17 / 2.6.18（`b639ec2..ecf040`）把软总线从「所有设备抢同一队列」升级为加权路由，本版有真实代码改动，改了 2 个源文件：

- **2.6.17 机制**：任务分 `book`（search/toc/rank）、`content`（content/ebook）、`media`（cover/intro/bookinfo）三类；
  每台设备维护 0–100 隐藏性能分（初始 50，成功 +4、失败 −8、>8s 再 −2，不对外暴露），`score>=55` 高分可领全部类、
  在途上限 3，`<55` 低分只领 media、上限 2；无高分设备在线时自动放开全部任务。领取即写 `dispatchedTo` 锁定 30 秒；
  权重计算独立成纯函数模块 `busweights.js`，异常自动降级。
- **2.6.18 修复**：在途数按实际锁定任务重算，修掉异常路径 inflight 泄漏把设备卡死；隐藏分与内部统计从 `/api/bus` 移除，
  新增 `highScore`/`lowScore` 计数。

客户端落实（`FnReaderRemote.kt` + `FnReaderBridgeService.kt`）：

- 新增 `cover` / `intro` 任务，payload 与 bookinfo 相同 `{bookUrl, source, name, author}`：抽出 `fetchMediaBook()`，
  bookinfo/cover/intro 共用同一次 `WebBook.getBookInfoAwait`；`cover()` 只回 `{coverUrl}`，`intro()` 回简介与元数据，
  拿不到的字段省略；不支持时服务端自动回退 bookinfo。
- `submit()` 识别幂等字符串态：`ok:"duplicate"`/`"stale"` 正常返回不重试，`ignored` 仍按调试设备失败抛出。
- 队列路由：`bookinfo`/`cover`/`intro` 统一进 info 队列；线程池大小与超时（18/18/23/23/9 秒）不变。

## 2.6.16.1（versionCode 2061601）：仅跟进文档，无代码改动

服务端 2.6.14 / 2.6.15 / 2.6.16 三版（`737cd6e..b639ec2`）全是服务端侧改动，客户端契约零变化：

- **2.6.14**：开启「优先手机解析」后，章节正文也优先走软总线（手机在线先让手机解析，失败再回退本机）；
  `/api/bus` 新增 `usage` 统计（`phoneSearch` / `phoneToc` / `phoneContent` / `localToc` / `localContent`）
- **2.6.15**：修「开了手机代理仍优先本地」——搜索先问手机再跑本机 Python / Node，正文提前到「本机无书源」之前尝试手机
- **2.6.16**：规则引擎加递归保护，书源规则自我引用最多 200 层，超过返回 502「书源规则递归过深，已中止解析」

核对结果：`docs/APP同步接口.md`（33 802 B）与 `docs/更新日志.md`（25 298 B）本轮零改动；对比 `737cd6e...b639ec2`
只有 `docs/更新日志.txt`、`legado-web/app/server/src/rules.js`、`server.js`、`web/index.html`、`manifest` 五个文件，
**`remote.js` 未改** → `/api/m/remote/*` 与 `/api/m/*` 的路径、字段、令牌、任务类型、超时（18 / 18 / 23 / 23 / 9 秒）全部照旧。

本版客户端代码零改动，只同步版本号、updateLog 与文档；包体仍是 2.6.13.1 的多线程实现
（`parse` 自适应 6 / 4 / 3 / 2 条、`info` 2 / 1 条、`ebook` 1 条）。
本机实测：MuMu 装包后「我的 → 接入 FnReader 服务器」显示「已连接 FnReader 2.6.15（保活中）」，菜单与同步项正常。

## 2.6.13.1（versionCode 2061301）：跟进 2.6.13 调试设备识别扩展

服务端 2.6.13（`737cd6e`，2026-11-17 04:22）把 2.6.12 的调试设备判定放宽：ID 追加 `busmon` / `monitor` / `mock` / `fake` / `e2e`，
名称追加「监控」「测试机」；同时修掉调试工具把真实书籍目录写成 `http://debug.local/...` 的问题（调试设备回传不再参与真实解析）。
接口形状、任务类型、超时、令牌全部未变，所以本版客户端**不动逻辑**，只把提示文案对齐新规则。

- **`FnReaderRemote.submit()`**：回传被忽略时抛出的失败原因改为「ID 含 bus-debug/busmon/monitor/mock/fake/e2e/debug，
  或名称含「调试」「监控」「测试机」」，用户对着改设备 ID / 名称时不用再猜规则
- **`Pending` 的 KDoc**：同步写成 2.6.12 → 2.6.13 的判定演进，避免下次再改时对不上服务端
- `FnReaderBridgeService` 的通知文案保持不变（它只说「改设备 ID / 名称」，不列关键字，避免通知栏过长）
- **手机解析改多线程（本版新增，与文档无关的客户端优化）**：原来的「检索 2 并发 + 简介单并发」协程改成三条真线程池——
  `parseDispatcher` 按 CPU 核数自适应（≥8 核 6 条、6 核 4 条、4 核 3 条、更低 2 条），
  `infoDispatcher` 独占封面与简介（≥8 核 2 条、其余 1 条），`ebookDispatcher` 独占整本解析；
  服务 `onDestroy` 时三者一起 `close()`，不会漏线程
- 字段表与自测清单见 `docs/app回传接受规范.md` §16

## 2.6.12.1（versionCode 2061201）：跟进 2.6.12 调试设备契约，改了 2 个文件

服务端 2.6.12（`b85fb31`）给软总线加了「调试设备」判定：设备 ID 含 `bus-debug` / `debug`、或名称含「调试」即判为调试设备，
其回传一律丢弃，任务留给真机或超时；`pending` 响应新增 `debug` 字段，`result` 对调试设备返回
`{ok:true, accepted:false, ignored:true, reason}`。这是本轮唯一的客户端契约变更，故本版不再是「仅跟进文档」。

- **`FnReaderRemote.poll()`**：返回类型 `List<Job>` → `Pending(jobs, debug)`，先按对象取 `jobs` 再读 `debug`（缺省 false，兼容 2.6.11 及更早服务端）
- **`FnReaderRemote.submit()`**：响应 `ignored == true` 时抛 `RemoteException`（带服务端 `reason` 与本机命中规则说明），不再静默当成功
- **`FnReaderBridgeService`**：轮询循环里 `pending.debug` 命中时通知栏一次性提示「本机被服务端识别为调试设备，回传会被忽略，请改设备 ID / 名称」
- 默认设备标识不触碰规则：`deviceId = AppConst.androidId`、`deviceName = Build.MODEL`
- 其余契约未变：任务类型 `search` / `toc` / `content` / `rank` / `bookinfo`、超时 18 / 18 / 23 / 23 / 9 秒、
  `X-Api-Token` 与传书 `X-Upload-Token`（现取）
- 字段表与自测清单见 `docs/app回传接受规范.md` §15

## 2.6.10.1（versionCode 2061001）：仅跟进文档，无代码改动

服务端 2.6.9 / 2.6.10 只改网页设置页的地址展示方式：IPv4 与 IPv6 分行、IPv6 再拆独立一行并可单独复制。
`docs/APP同步接口.md` 与 `docs/更新日志.md` 两份一份都没动，`server.js` 也一个字没改；本版代码与 2.6.8.1 逐字一致。

- **2.6.9（本次跟进）**：`web/app.js` 把「其它地址」拆成 IPv4 一行、IPv6 一行，「复制」按钮同时复制两类地址
- **2.6.10（本次跟进）**：IPv6 单独成行 + 独立复制按钮；IPv4 的复制只复制 IPv4，并过滤掉 `::1` 回环地址
- `index.html` / `manifest` 的版本串 2.6.8 → 2.6.10；`/api/m/info` 的 `ipv6Lans` 字段保持原样，客户端 `info()` 仍只取版本号
- 契约复核：任务类型 `search` / `toc` / `content` / `rank` / `bookinfo`、超时 18 / 18 / 23 / 23 / 9 秒、
  `X-Api-Token` 与传书 `X-Upload-Token`（现取）全部未变

## 2.6.8.1（versionCode 2060801）：仅跟进文档，无代码改动

服务端 2.6.8 把监听从 `0.0.0.0` 换成 `::`（IPv4/IPv6 双栈）、设置页多展示一个公网 IPv6 地址；`docs/APP同步接口.md`
零改动。本版只改包元数据与文档，Java 代码与 2.6.7.1 完全一致。

- **2.6.8（本次跟进）**：`server.js` 改 `tcp.listen(PORT, '::')`，公网 IPv6 下手机可直接访问；`/api/m/info` 新增
  `ipv6Lans`（只含 `2xxx` / `3xxx` 开头的公网 IPv6，跳过 ULA / link-local）；网页「其它地址」把 IPv6 一起列出并可复制
- 新增 `docs/fnos-fpk-build.md`（fnOS FPK 打包与上架指南，纯文档，与客户端无关）
- 契约复核：任务类型 `search` / `toc` / `content` / `rank` / `bookinfo`、超时 18 / 18 / 23 / 23 / 9 秒、
  `X-Api-Token` 与传书 `X-Upload-Token`（现取）全部未变；`ipv6Lans` 是给网页读的，客户端 `info()` 只取版本号
- 已知提示：面板里填 IPv6 字面量地址（`http://[240e:…]:1122`）会弹一次「这不是内网地址，请用 https」，
  属既有 `isLanHost()` 行为，只提醒不拦截；公网访问本来按文档就该走 https

## 2.6.7.1（versionCode 2060701）：仅跟进文档，无代码改动

版本号按「服务端版号 + 0.0.0.1」对齐，一次覆盖服务端 2.0.6 – 2.6.7 的全部改动。本版只改包元数据与文档，
Java 代码与 2.4.4.1 完全一致。

- **2.6.6 / 2.6.7（本次跟进）**：`docs/APP同步接口.md` 零改动（33802 B / `e04f3466…`）；`docs/更新日志.txt`
  新增这两段（`docs/更新日志.md` 自 2.6.0 起服务端只写 `.txt`，巡检两处都比对）
  - 2.6.6：净化规则（替换·屏蔽）对 `/api/m/content` 同样生效，App 正文与网页一致；`title` / `paragraphs` 结构不变
  - 2.6.7：网页端新增「离线缓存」页、`GET /api/ebook/export`（整本 TXT 导出）、`GET /api/update/check`
    （启动检查 GitHub 最新 Release）；App 侧无对应界面，也不需要
- **2.4.4.1（上一版）**：面板 ⑧ 传书到飞牛，pdf / mobi / azw3 / epub / txt 直传，令牌每次现取
- **2.4.3**：目录先行 + 按章回传
- **2.0.6**：`ebook-parse` 软总线任务，EPUB / MOBI 交手机解析并回传 `fnreader.ebook.v1` JSON
- 契约复核：软总线任务类型仍为 `search` / `toc` / `content` / `rank` / `bookinfo`，超时 18 / 18 / 23 / 23 / 9 秒；
  `X-Api-Token` 与传书 `X-Upload-Token`（现取）鉴权未变；上行回传字段未变

## 2.0.5（versionCode 20005）：仅跟进文档，无代码改动

服务端 `docs/更新日志.md` 再新增 2.0.7 / 2.0.8 / 2.0.9 三段（都讲缓存目录去重），
`docs/APP同步接口.md` 无变化（`68e3f7954681`，29343 B）。

- **2.0.7**：`<下载目录>/cache` 下同一本书不再生成「书名 (2)(3)…」，重复同步复用原目录；启动时自动合并历史重复目录，新增 `/api/cache/dedupe`
- **2.0.8**：去重改为异步、只由 `/api/cache/dedupe` 触发，避免扫描大量目录卡住服务；重复同步复用原目录
- **2.0.9**：按「书名+作者」归并跨书源同名目录，合并改用 rename（同盘瞬时）；NAS 上 1641 个重复目录已清到 158 个

这三段全在服务端缓存落盘与目录去重，`/api/cache/dedupe` 由服务端自己调用，客户端不参与；
`shelf` / `search` / `toc` / `content` / `progress` / `sources/sync` / `cover` 与封面、书源上传的路径、
字段、鉴权、超时都没变，本版只动版本号、客户端 md 与应用内日志。

## 2.0.4（versionCode 20004）：仅跟进文档，无代码改动

服务端 `docs/更新日志.md` 再新增 2.0.5 / 2.0.6 两段（都在讲「加入书架后自动抓完整目录」），
`docs/APP同步接口.md` 无变化（`68e3f7954681`，29343 B）。

- **2.0.5**：加入书架后立即抓完整目录（强制 `refresh`、含分页），服务端后台补全并广播，按钮显示章数
- **2.0.6**：加入书架立即抓完整目录，有手机在线优先走软总线（能过反爬），无设备或失败自动回落本机解析；
  按钮与提示显示章数和来源

客户端侧无需改动：`toc` 任务走 `WebBook.getChapterListAwait`，按 `nextTocUrl` / 页面「下一页」翻页
（上限 40 页）整本抓完才回，没有缓存，天然等同服务端要求的 `refresh`；软总线 `toc` 收到即回
`chapters` 全量，服务端拿去补全即可。超时照旧：`JOB_TIMEOUT_TOC = 18000L`（服务端等 20 s）、
`JOB_TIMEOUT_CONTENT = 23000L`、`JOB_TIMEOUT_BOOKINFO = 9000L`。本版只动版本号与应用内日志。

## 2.0.3（versionCode 20003）：仅跟进文档，无代码改动

服务端 `docs/更新日志.md` 新增 2.0.3 / 2.0.4（目录引擎分页上限 5 → 40 页、带循环检测、
没写 `nextTocUrl` 时按页面「下一页」兜底；Python 引擎目录同样分页），`docs/APP同步接口.md` 无变化。
分页与去重都在服务端做，客户端 `toc` 任务与超时（`JOB_TIMEOUT_TOC = 18000L`，服务端等 20 秒）不变，
本版只动版本号与应用内日志。实测（服务端 2.0.4）：单书源《群星之子逃婚录》从「只回 50 章」
变成整本目录一次回完（末条「第3139章 白骨王座（三更）」，服务端按标题去重 1610 条）。

## 2.0.x（versionCode 20200 / 2.0.2）：书架去重、删除墓碑、软总线拆两条队列

### 主线（2.0.0 – 2.0.1）

- **书架同名去重**：同名（不同书源）只留一本，优先保留本地已有 `.nb` 缓存的那本，其余副本进度先并过去再删；
  推送与自动同步都按这条规则取书
- **删除墓碑**：手动删掉的书不再被自动同步推回来（记墓碑，服务端再推同一本跳过，通知栏「跳过已删 N 本」）
- **⑧⑨ 转后台**：缓存上传与书源双向同步连上服务端即自动，每 5 分钟一轮；缓存只传整本下完的
- 启动页换成「飞牛阅读」竖版毛笔字

### 2.0.2：跟 `docs/APP同步接口.md` §18

- 文档新增 **§18 简介补全：让手机把书籍详情带回来**（`bookinfo` 任务），`docs/更新日志.md` 新增
  2.0.2 / 2.0.1 / 2.0.0 / 1.11.4 四段
- `service/FnReaderBridgeService.kt` 拆两条队列：`bookinfo` 进独立 `infoQueue`（单并发），
  `search` / `toc` / `content` / `rank` 进 `searchQueue`（`SEARCH_CONCURRENCY = 2`），互不挤占
- `bookinfo` 超时由 23 s 收到 **9 s**（`JOB_TIMEOUT_BOOKINFO = 9000L`），对齐 §18.2「超时 10 秒」：
  手机先放手，服务端按时按原样返回，不再白等啃不动的详情页
- `model/FnReaderRemote.kt` 未改：`bookInfo()` 回传 `intro` / `author` / `coverUrl` / `kind` / `wordCount` /
  `lastChapter` / `totalChapterNum` 与 §18.1 字段表一致，`matchSource()`（source 为空按 host 找源）、
  `plainText()`（富文本简介转纯文本）在 2.0.1 已落地
- 两仓 `FnReaderBridgeService.kt` 同构（SHA256 一致），版本 2.0.2 / 20200

## 1.11：lean LEDE 更新日志订阅 + 给 lean 许愿（GitHub）

### 1. lean 的「更新日志」在哪里

`coolsnowwolf/lede` 没有独立 CHANGELOG 文件（`CHANGELOG.md` / `CHANGELOG` / `CHANGELOG.txt` / `docs/README.md` 全部 404，
`README.md` 200），它唯一的「更新日志」就是 **master 分支的提交历史**。GitHub 给每个仓库都开了 Atom 源，
所以订阅地址是：

```
https://github.com/coolsnowwolf/lede/commits/master.atom
```

### 2. Atom 解析（本版才有的能力）

`RssParserDefault.kt` 原先只认 RSS 2.0 的 `<item>`，Atom 的 `<entry>` 一条都进不来，订阅加进去是空白。改动：

| 位置 | 改动 |
| --- | --- |
| 条目态 | `<item>` 与 `<entry>` 同等进入 `insideItem`；`END_TAG` 两个名字都收尾 |
| 链接 | `<link>` 有 `href` 属性就是 Atom 写法，取 `rel="alternate"` 那条；没有属性才 `nextText()`（RSS 写法） |
| 摘要/正文 | `<summary>` / `<content>` 映射到 `description` / `content`，并参与封面提取 |
| 时间 | `<updated>` / `<published>` 走 `atomDate()`：ISO 8601 → 本地 `yyyy-MM-dd HH:mm` |
| 标题 | `oneLine()` 把缩进与换行压成一行（Atom 的 `<title>` 常带换行缩进） |

### 3. 内置订阅的写入方式

新增 `DefaultData.ensureBuiltinRssSources()`：**只补缺失、不动用户增删**。
原来的 `importDefaultRssSources()` 是「先 `deleteDefault()` 再插入」，会把用户删掉的默认源每次升级都塞回来，
所以内置 lean 源走新函数，只在 `upVersion()` 的版本升级分支里调用一次。

内置字段：`sourceUrl=LEDE_RSS_URL`、`sourceName=lean LEDE 更新日志`、`sourceGroup=固件`、
`singleUrl=false`、`enableJs=false`、`customOrder=6`。

### 4. 给 lean 许愿（GitHub）

GitHub 只允许「从 fork 的分支向上游发 PR」，所以「一键许愿」实际是四步，全部实现在 `model/GithubWish.kt`：

1. `GET /user` 认人（同时探测 `GET /repos/<login>/lede` 是否已有 fork）
2. 没 fork → `POST /repos/coolsnowwolf/lede/forks`，然后每 3 秒轮询一次，最多 2 分钟
3. 在 fork 的 master 上建 `wish-<yyyyMMdd-HHmmss>` 分支 → `PUT /contents/wishlist/<时间>-<账号>.md`
4. `POST /repos/coolsnowwolf/lede/pulls`，`head=<login>:<branch>`、`base=master`

兜底：`POST /repos/coolsnowwolf/lede/issues`（不想等 fork 时用），返回 Issue 地址。

UI 用 App 自带控件（`BaseDialogFragment` + `dialog_github_wish.xml`，与「接入 FnReader 服务器」同一套观感）：
token 输入 / 登录·退出登录 / 账号状态行 / 多行许愿框 / 「提交 PR」·「改发 Issue」/ 结果行（成功后可点开）。

token 存 `SharedPreferences`（`githubWishToken` / `githubWishLogin`），不进备份、不写日志。
权限：classic token 勾 `public_repo`；细粒度 token 要 `Contents` 读写 + `Pull requests` 读写，
自动 fork 还需 `Repository creation`（`403` 时界面回显 GitHub 原文并给出这条建议）。

## 1.10：跟进 `docs/更新日志.md` 1.11.3，无代码改动

`docs/更新日志.md` 新增 **1.11.3（2026-11-10）**：服务端补 `applyReplaceRules`（正文净化）、`formatJs` 结果生效、脚本/测试目录名统一、日志写入 `docs`、README 版本自动同步——全是服务端 legado-py 侧修复。

`docs/APP同步接口.md` 本轮**未变**（27 458 B / SHA256 `7b46d6a625031f31…`），第 4 / 9 / 11 / 13 / 14 / 15 / 16 / 17 章的字段表继续有效，`FnReaderRemote.kt` / `FnReaderBridgeService.kt` / `FnReaderConfigDialog.kt` 与 1.9 完全一致，本版只动版本号（1.10 / 11000）与文档。

正文净化与 `formatJs` 在服务端就做完了，`/api/m/content` 返回的已经是净化后的正文；客户端不再叠一层，否则二次净化会把分段拆乱。

实测（MuMu + FnReader 1.11.3）：首启日志首条 `2026/11/10（1.10）`，「测试连接」回显 1.11.3，书架 10 本 / 进度 10 条 / 书源 403 个。

## 1.9：跟进文档 §15，软总线支持 rank 代抓榜单

`docs/APP同步接口.md` 新增 **§15 每日推荐（`GET /api/daily`）**：服务端按「起点 → 纵横 → 17K」
依次抓榜单，起点有反爬时先让软总线上的在线手机抓，任务类型 `rank`、`payload={url}`。
`docs/更新日志.md` 补了 1.10.0–1.11.2 的版本记录（§16 缓存同步、§17 书源双向同步本客户端 1.8 已实现，复核无差异）。

代码只动一处（`model/FnReaderRemote.kt`、`service/FnReaderBridgeService.kt`，与 A4 同构）：

| 改动 | 说明 |
| --- | --- |
| `FnReaderRemote.execute` 增加 `"rank"` 分支 | 以前收到 rank 直接回 `ok:false`（1.8 及更早写死只认 search/toc/content） |
| `rank(payload)` | 浏览器 UA 抓 `payload.url`；不足 10 KB 视为反爬页直接报错；Jsoup 按三套选择器解析（起点 `.book-img-text li` / `.rank-list li`、纵横 `.zh-modules-rank-book`、17K `/book/<id>.html`），去重后补 `rank` 序号 |
| 回传结构 | `{source,url,books:[{rank,name,url,author,cover,score}]}`，服务端直接当 `/api/daily` 的 books 用 |
| `timeoutOf("rank")` | 23 秒（与正文同档，抓页面本身就慢） |

反爬页与「解析出 0 本」都会回 `ok:false` + 原因，服务端照旧降到纵横 / 17K，网页端不会白屏。

## 1.8：origin 必须是书源真实地址（修「没有书源」）

1.7 有个真实事故：客户端加书时 `origin` 传空、`originName` 传 `手机端`，服务端靠 `book.origin` 取目录与正文，
结果网页端打开提示「没有书源」、目录空、正文读不出来。1.8 在 `ui/config/FnReaderConfigDialog.kt` 里补齐：

| 位置 | 改动 |
| --- | --- |
| 新增 `SourceIndex` / `buildSourceIndex()` | 由 `bookSourceDao.flowAll()` 建「`bookSourceUrl` → (地址, 源名)」和「host → 同宿主书源」两张表 |
| `SourceIndex.resolve(book)` | 三级匹配：书的 origin 已是真实源地址 → 用规范的地址+源名；按 `bookUrl` 的 host 命中（多源优先同名）；按源名唯一命中；全不中才留空 |
| `looksLikeSearchUrl()` | `/search`、`?q=`、`&q=`、`?wd=`、`?keyword=`、`/so/`、`s?ie=` 一律不是详情页，跳过不推 |
| `pushJson(book, index, cover)` | 推书的 `origin` = `bookSourceUrl`、`originName` = 源名；结果区报「非详情页跳过 N 本」「无匹配书源 N 本」 |
| `cacheJson(book, folder, files, index, cover)` | ⑧ 缓存补 `layout:"legado"`、`chapters[].file`、`chapters[].raw`（`.nb` 原文）、`origin`/`originName` |
| `fetchCoverData()` / `downloadCover()` / `fetchCoverBytes()` / `gunzip()` | 本机没有封面文件、或服务端封面仍是源站外链时现下（8s 超时、≤3 MB），`Referer` 先空、再图片域名、再书源地址，图片被 gzip 预压过就解一层，转 base64 随书推 |
| `syncSourcesBoth()` | `currentUrls` / `goneUrls`（靠 `fnReaderSourcesSnapshot` 快照）+ `deleted[{bookSourceUrl,lastUpdateTime}]`；`sourceJson()` 给没时间戳的源补当前毫秒；`pull` 覆盖本地 |
| 文件级工具 | `ORIGIN_PLACEHOLDERS`（`""` / `手机端` / `本地` / `未知` / `导入` / `默认` / `loc_book` / `local` / `null` / `none`）、`isPlaceholderOrigin`、`normSourceUrl`、`hostOfUrl`、`looksLikeSearchUrl` |

实测（MuMu 模拟器 + FnReader 1.11.1/1.11.2）：9 本书 origin 全部等于书源 `bookSourceUrl`；
6 本目录/正文可读；⑧ 落盘 `layout: legado` + `raw` 原文；5 本封面落 NAS `covers/`；⑨ 双向改名生效。

## 1.7 起：FnReader 1.11.0 的两个新接入项


服务端 `docs/APP同步接口.md` 新增 §16 缓存同步、§17 书源双向同步，客户端在
`ui/config/FnReaderConfigDialog.kt` 里加了两项（菜单 ①–⑦ 不动，新增 ⑧⑨）：

| 方法 | 作用 | 要点 |
| --- | --- | --- |
| `uploadCache()` | ⑧ 把本机 `book_cache/<书名>/` 的 `.nb` 整本推给 `POST /api/m/cache` | `raw` 传原文；单本 > 30 MB 跳过；章节图片 1 B–2 MB 转 base64；读超时 60s |
| `cacheJson()` | 组 §16 载荷 | `index` 取文件名前 5 位，`title` 取本地章节表，`file` 原名 |
| `syncSourcesBoth()` | ⑨ 书源双向同步 | 首次 `POST /api/m/sources` 合并上传；之后按 `lastUpdateTime > since` 增量 POST/GET；`pull` 覆盖、`pullDeleted` 删除 |
| `mergePull()` / `applyPullDeleted()` | 落服务端更进 / 删除的书源 | 都按 `bookSourceUrl` |

首次同步为什么不用 `POST /api/m/sources/sync` 推全量：实测「本机全量 + `since: 0`」会被服务端当成整库快照，
删掉本机没有的源（1253 → 995，丢 258 条），只能靠 `backup/sources-*.json` + `POST /api/m/sources` 补回。
这条坑写进了 `docs/app回传接受规范.md` §9。
