## 阅读Pro 2.6.11.1（versionCode 2061101）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.6.11.1** 同步发布、同一套版本号；版号基线取自服务端 `legado-web` **v2.6.11**，本版为其上的 `0.0.0.1` 小更新。

### 本版做了什么

**仅跟进文档，无代码改动**。服务端 v2.6.11 只把应用信息里的开发者 / 维护者 / 发布者统一改成 `TZYLXS`，
另外补了一篇给调试机用的软总线调试 APK 说明；没有一条碰到 App 契约，Kotlin / Java 代码与 2.6.10.1 逐字一致。

- `docs/APP同步接口.md` **零改动**：33802 B，SHA256 `e04f34664bcd5e5bd02abb5342698d2e1219d0b849d76003211c589e223f9ea1`
- `docs/更新日志.md` **未变**（自 2.6.0 起服务端只往 `.txt` 写），`docs/更新日志.txt` 新增 2.6.11 一段（`e16ced11…` → `73e265f4…`）：
  开发者 / 维护者改 `TZYLXS`（https://github.com/TZYLXS），发布者改 `TZYLXS`
- `docs/fnos-fpk-build.md` 里的 `maintainer` / `maintainer_url` / `distributor` 示例同步换成 `TZYLXS`（6 行）
- 新增 `docs/软总线调试APK.md`（207 行）与 `tools/bus-debug-app` 源码、`.github/workflows/build-bus-debug.yml`：
  给调试机单独装的小工具（包名 `com.tzylxs.busdebug`），只调 `GET /api/bus` 等软总线接口，与两端客户端契约无关
- `legado-web/manifest`、`legado-web/app/web/index.html` 只动了版本串与元信息几行；`server.js` 无 diff，`/api/m/*` 无 diff

### 契约复核（逐项核对，全部未变）

| 维度 | 结论 |
| --- | --- |
| 回传字段 | `search` / `toc` / `content` / `rank` / `bookinfo` 上行字段与 2.6.10.1 一致 |
| 接口路径 | `/api/m/*` 未增删改；`/api/m/info` 未变 |
| 鉴权 | 回传仍是 `X-Api-Token`；传书仍是 `GET /api/ebook/upload` 现取 + `X-Upload-Token`，401 `code=upload-token` 重取一次 |
| 任务类型与超时 | `search` 18 s / `toc` 18 s / `content` 23 s / `rank` 23 s / `bookinfo` 9 s，均未变 |
| 错误码 | 未变 |
| 封面 / 书源通道 | `/api/m/cover`、`/api/m/sources`、`/api/m/sources/sync`、`/api/m/cache` 未变 |

### 实测结果

| 环节 | 结果 |
| --- | --- |
| 文档比对 | `git fetch` 远端停在 `23abc399`（2.6.11 之后只有 3 个 CI 提交）；`APP同步接口.md` / `更新日志.md` / `更新日志.txt` 三份 SHA256 与上一轮完全一致，无新变更 |
| 源码 diff（相对 2.6.10 基线 `4d9d373`） | `更新日志.txt` +5、`fnos-fpk-build.md` 6 行、新增 `软总线调试APK.md` 207 行、新增 `tools/bus-debug-app` 与 `build-bus-debug.yml`、`web/index.html` 与 `manifest` 各几行；`server.js` 与 `/api/m/*` 无 diff |
| 编译 | arm64 / armv7 两次 `assembleAppRelease`，均 `BUILD SUCCESSFUL`（exit 0） |
| badging | 两包均 `versionCode 2061101` / `versionName 2.6.11.1`；arm64 `native-code 'arm64-v8a'`、armv7 `'armeabi-v7a'`；均无 `application-debuggable` |
| 应用内日志 | python zipfile 读两包 `assets/updateLog.md`，首条均 `2026/11/17（2.6.11.1）`，旧段落保留（共 56 条） |

### 产物

| 文件 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.6.11.1-arm64.apk` | 18 773 996 B（17.90 MB） | `4f57bb966318ab45f1b29cccc3aa79eb61c506cc4a918c0a075822aaf22020f4` |
| `yuedu_pro_app_2.6.11.1-armv7.apk` | 18 729 013 B（17.86 MB） | `ade6dfae885cd6012cdc9b9352fae6f74ee64d6cff511ab0ca69966238be212a` |

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」、安卓 4.0 版共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 2.6.10.1 及更早版本一致，可直接覆盖升级，数据不丢

### 已知限制

- 传书只做「本机文件 → 飞牛」，不做管理端的上传历史与重试队列；失败后重新点 ⑧ 再选一次即可。
- 分片上传（大文件断点续传）未做，当前是单次裸 body 直传，超 `maxMB`（默认 1024 MB）由服务端 413 拒绝。
- 服务端 `ebook-parse` 派发 payload 仍不带 `tocOnly` / `mode:toc`，toc-only 分支继续休眠。
- 软总线调试 APK（`com.tzylxs.busdebug`）是独立的调试工具，不随本版发布，也不能替代客户端联调。
- PDF 原页模式（`k:"img"`）客户端尚无渲染能力。
- 服务端「离线缓存」页与「更新提醒」只在网页端有，App 内没有对应入口；App 更新仍走 GitHub Release。
- 面板地址栏填 IPv6 字面量（`http://[240e:…]:1122`）会弹一次「这不是内网地址，请用 https」——
  这是既有 `isLanHost()` 的判断，只提醒不拦截；连公网地址时按文档本来就该走 https。
