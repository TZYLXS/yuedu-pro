## 阅读Pro 2.6.12.1（versionCode 2061201）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.6.12.1** 同步发布、同一套版本号；版号基线取自服务端 `legado-web` **v2.6.12**，本版为其上的 `0.0.0.1` 小更新。

### 本版变更（首次不是「仅跟进文档」）

服务端 2.6.12（`b85fb31`，2026-11-17 02:41）在软总线加了「调试设备」判定：设备 ID 含 `bus-debug` / `debug`、或名称含「调试」即判为调试设备，
其回传**一律丢弃**（`[softbus] 忽略调试设备回传`），任务留给真机或超时；调试任务（`payload.debug === true`）只投给调试设备，普通手机不再收到。
接口侧两处新增：`GET /api/m/remote/pending` 响应多 `debug` 字段，`POST /api/m/remote/result` 对调试设备返回 `{ok:true, accepted:false, ignored:true, reason}`。

客户端据此改了 2 个文件（A4 端逐条对齐）：

- `FnReaderRemote.poll()`：返回类型 `List<Job>` → `Pending(jobs, debug)`，先按对象取 `jobs` 再读 `debug`（缺省 false，兼容 2.6.11 及更早服务端）
- `FnReaderRemote.submit()`：响应 `ignored == true` 时抛 `RemoteException`（带服务端 `reason` 与本机命中规则说明），不再静默当成功
- `FnReaderBridgeService`：轮询循环里 `pending.debug` 命中时通知栏**一次性**提示「本机被服务端识别为调试设备，回传会被忽略，请改设备 ID / 名称」
- 默认设备标识不触碰规则：`deviceId = AppConst.androidId`（十六进制串）、`deviceName = Build.MODEL`，正常手机不会被误判

契约其余部分复核无变化：任务类型 `search` / `toc` / `content` / `rank` / `bookinfo`，超时 `JOB_TIMEOUT_SEARCH/TOC = 18000 ms`、`CONTENT = 23000 ms`、
`RANK = 23000 ms`、`BOOKINFO = 9000 ms`；鉴权仍是 `X-Api-Token`（回传）与 `X-Upload-Token`（传书，现取）；
`/api/m/books`、`/api/m/progress/batch`、`/api/m/cover`、`/api/m/sources`、`/api/m/sources/sync`、`/api/m/cache` 路径与字段未变。
字段表与自测清单见 `docs/app回传接受规范.md` §15。

### 文档同步

- `docs/阅读Pro.md`（本版章节改写为真实代码改动）、`README.md`（版本号与能力条目 +1）、`docs/app回传接受规范.md`（新增 §15）
- 应用内 `assets/updateLog.md` 顶部新增 `**2026/11/17（2.6.12.1）**`，老段落保留
- 服务端侧 `docs/更新日志.txt` 新增 2.6.12 一段；`docs/APP同步接口.md`（33802 B / `e04f3466…`）与 `docs/更新日志.md` 本轮仍是零改动

### 包信息

| 产物 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.6.12.1-arm64.apk` | 18 776 027 B（17.90 MB） | `CFD059CA687D20FFC34AB3604A6C84C36E9801B38C29BE67DFE49DA1FC2777DC` |
| `yuedu_pro_app_2.6.12.1-armv7.apk` | 18 731 052 B（17.86 MB） | `93C994850BB0353533F439114D6C7249E8920D235714246283BBA1584D584825` |

- `aapt2 dump badging`：`package: name='io.legado.pro' versionCode='2061201' versionName='2.6.12.1'`，`sdkVersion:'24'`，`targetSdkVersion:'33'`，两个包无 `application-debuggable`
- 包内 `assets/updateLog.md` 首条已核对为 `**2026/11/17（2.6.12.1）**`（`python zipfile` 解包读取）
- 签名沿用 `keystore/yuedu.jks`（v1 + v2），可直接覆盖安装旧版

### 安装方式

1. 下载对应 ABI 的 apk（2017 年后的手机 / 平板 / 电视盒子选 arm64，老 32 位设备选 armv7）
2. 覆盖安装即可升级，书源与书架数据保留
3. 装好后进入「我的 → 接入 FnReader 服务器」，填服务端地址（`http://10.5.4.70:1122` 或 `http://192.168.5.114:1122`）与 `X-Api-Token`

### 自测与限制

- 编译：Gradle 侧 `-Xmx2048m` + Kotlin daemon `-Xmx1024m` + `--max-workers=2`，两包各约 3–4 分钟，构建后可内存正常回落
- 本版**未做模拟器冒烟**：MuMu 当时 `emulator-5554 offline`，且按内存约束未起模拟器；代码改动集中在上传/领任务分支，编译与包内校验已通过
- 调试设备规则的自测步骤（真机 `debug:false` 正常领任务；把设备名改含「调试」后 `debug:true` 且回传得 `ignored:true`）写在 `docs/app回传接受规范.md` §15.2，待接真机时逐条跑
