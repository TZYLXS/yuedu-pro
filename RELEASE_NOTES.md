## 阅读Pro 2.6.8.1（versionCode 2060801）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.6.8.1** 同步发布、同一套版本号；版号基线取自服务端 `legado-web` **v2.6.8**，本版为其上的 `0.0.0.1` 小更新。

### 本版做了什么

**仅跟进文档，无代码改动**。服务端 v2.6.8 只把监听方式改成 IPv4/IPv6 双栈、设置页多展示一个公网 IPv6 地址，
没有任何一条改到 App 契约；本版把版本号对齐到服务端 2.6.8，Kotlin / Java 代码与 2.6.7.1（即 2.4.4.1 同一份）
逐字一致（`FnReaderRemote.kt`、`FnReaderBridgeService.kt`、`FnReaderConfigDialog.kt` 零 diff）。

- `docs/APP同步接口.md` **零改动**：33802 B，SHA256 `e04f34664bcd5e5bd02abb5342698d2e1219d0b849d76003211c589e223f9ea1`
- `docs/更新日志.txt` 新增 2.6.8 一段；仓库另新增 `docs/fnos-fpk-build.md`（fnOS FPK 打包与上架指南，纯文档）
- `server.js` 改 `tcp.listen(PORT, '::')`：IPv6 与 IPv4 双栈监听，局域网 IPv4 连接照常
- `/api/m/info` 多返回一个 `ipv6Lans` 数组（只含 `2xxx` / `3xxx` 开头的公网 IPv6，跳过 ULA / link-local），
  网页「其它地址」与复制按钮把它一起列出；App 侧 `info()` 只取版本号做探活，新增字段忽略即可

### 契约复核（逐项核对，全部未变）

| 维度 | 结论 |
| --- | --- |
| 回传字段 | `search` / `toc` / `content` / `rank` / `bookinfo` 上行字段与 2.6.7.1 一致；2.6.8 未动任何 `/api/m/*` 响应 |
| 接口路径 | `/api/m/*` 未增删改；新增的只有 `/api/m/info` 响应里的 `ipv6Lans` 字段（网页消费） |
| 鉴权 | 回传仍是 `X-Api-Token`；传书仍是 `GET /api/ebook/upload` 现取令牌 + `POST /api/m/ebook/upload?name=…` 带 `X-Upload-Token`，401 `code=upload-token` 重取一次 |
| 任务类型与超时 | `search` 18 s / `toc` 18 s / `content` 23 s / `rank` 23 s / `bookinfo` 9 s，均未变 |
| 错误码 | 未变（`upload-token` 401、超限 413、multipart 400 语义照旧） |
| 封面 / 书源通道 | `/api/m/cover`、`/api/m/sources`、`/api/m/sources/sync`、`/api/m/cache` 未变 |

### 实测结果（服务端 `TZYLXS/FnReader`，MuMu Android 12）

| 环节 | 结果 |
| --- | --- |
| 文档比对 | `git fetch` 远端 `01beafd → 1b0bb3f`（`release(v2.6.8)`，2026-11-16 23:36:30）；`APP同步接口.md` 与 `更新日志.md` 两份 sha 未变，只有 `更新日志.txt` 新增 2.6.8 段 |
| 源码 diff | 6 文件：`server.js` +18/−2（`allLanIpv6()` + `ipv6Lans` + 监听改 `::`）、`web/app.js` +6/−2、`web/index.html` 6、`manifest` 2、`更新日志.txt` +6、新增 `docs/fnos-fpk-build.md` +323 |
| 编译 | arm64 / armv7 两次 `assembleAppRelease`、A4 一次，均 `BUILD SUCCESSFUL`（exit 0） |
| badging | 三包均 `versionCode 2060801` / `versionName 2.6.8.1`；arm64 `native-code 'arm64-v8a'`、armv7 与 A4 `'armeabi-v7a'`；均无 `application-debuggable` |
| 应用内日志 | python zipfile 读三包 `assets/updateLog.md`，首条均 `2026/11/16（2.6.8.1）`，旧段落保留（Pro 54 条 / A4 52 条） |
| MuMu 冒烟 | 覆盖安装启动正常，首启更新日志弹窗首条即本版；面板 ①–⑧ 与 `buildMethods()` 一致（本版 Kotlin 零 diff，沿用 2.4.4.1 实测结论） |

### 产物

| 文件 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.6.8.1-arm64.apk` | 18 773 460 B（17.90 MB） | `f1f7ae3d390e69e91b08aed61fb650cc2484368fa0182070249e28fc624bbe87` |
| `yuedu_pro_app_2.6.8.1-armv7.apk` | 18 728 488 B（17.86 MB） | `7ebae8bbdd584ba9d28b60d8e6dccad8c44303f4a58148cbb9d4ed0c4fcad6d4` |

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」、安卓 4.0 版共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 2.6.7.1 及更早版本一致，可直接覆盖升级，数据不丢

### 已知限制

- 传书只做「本机文件 → 飞牛」，不做管理端的上传历史与重试队列；失败后重新点 ⑧ 再选一次即可。
- 分片上传（大文件断点续传）未做，当前是单次裸 body 直传，超 `maxMB`（默认 1024 MB）由服务端 413 拒绝。
- 服务端 `ebook-parse` 派发 payload 仍不带 `tocOnly` / `mode:toc`，toc-only 分支继续休眠。
- PDF 原页模式（`k:"img"`）客户端尚无渲染能力。
- 服务端「离线缓存」页与「更新提醒」只在网页端有，App 内没有对应入口；App 更新仍走 GitHub Release。
- 面板地址栏填 IPv6 字面量（`http://[240e:…]:1122`）会弹一次「这不是内网地址，请用 https」——
  这是既有 `isLanHost()` 的判断，只提醒不拦截；连的是公网地址时按文档本来就该走 https。
