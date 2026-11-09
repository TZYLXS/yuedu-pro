## 阅读Pro 1.10（versionCode 11000）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）1.10** 同步发布、同一套版本号。

### 本版主线：跟进服务端 1.11.3，**无代码改动**

本轮巡检命中 FnReader `docs/更新日志.md` 新增 **1.11.3（2026-11-10）**：

> 修复 legado-py 缺陷清单：补 applyReplaceRules（正文净化）、formatJs 结果生效、脚本/测试目录名统一、日志写入 docs、README 版本自动同步。

| 文档 | 本轮状态 | 对客户端的影响 |
| --- | --- | --- |
| `docs/更新日志.md` | 新增 1.11.3 一段（16 438 → 16 649 B） | 全部是服务端 Python 侧（legado-py）修复 |
| `docs/APP同步接口.md` | **未变**（27 458 B / SHA256 `7b46d6a625031f31…`） | 接口路径、回传字段、鉴权、软总线任务类型与超时都没有变化 |

因此本版**仅跟进文档与版本号，客户端一行代码都没改**（`FnReaderRemote.kt` / `FnReaderBridgeService.kt` /
`FnReaderConfigDialog.kt` 与 1.9 完全一致）。

正文净化 `applyReplaceRules` 与 `formatJs` 都在服务端执行：本机 `/api/m/content` 拿到的已经是净化后的正文，
客户端再叠一层会二次净化、把分段拆乱，所以**不做客户端侧净化**。

### 实测结果

- 编译：arm64、armv7 均 `BUILD SUCCESSFUL`（arm64 1m 37s，armv7 增量 19s）
- 服务端实测：`GET /api/m/info` → `{"app":"阅读","version":"1.11.3"}`，端点仍是旧十条
- 模拟器（MuMu，Android 12 / API 31）装 1.10 包：
  - 首启更新日志首条为 **2026/11/10（1.10）**（截图为证）
  - 「我的 → 接入 FnReader 服务器」九项菜单齐全，⑤ 手机解析待命显示「已关闭（点此开启）」
  - 面板「测试连接」→ **已连接 FnReader 1.11.3**，书架 10 本 / 进度 10 条 / 书源 403 个（启用 403）
  - 「同步」拉取书架、进度、书源无报错
- `POST /api/remote/job {"type":"rank"}` 在 1.11.3 上仍返回 `400 {"error":"不支持的任务类型"}`：`rank` 只能由
  服务端 `/api/daily` 内部 `remoteRank` 触发，属服务端设计，不是客户端问题（`docs/app回传接受规范.md` §11 已按实测修正措辞）

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 1.0 – 1.9 一致（证书 SHA-256 `81ce1c10…09ef0f`），可直接覆盖升级，数据不丢

### 包信息

| APK | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_1.10-arm64.apk` | 17.56 MB（18 412 973 B） | `11E24FFE1C47D520A7088E911EFC8D39557091448F453011A8DCA4709174F2B3` |
| `yuedu_pro_app_1.10-armv7.apk` | 17.52 MB（18 367 985 B） | `B2ABA995CFCF5E19C7540CB8B9E82A6263B5BF32402CD44D0A8387FA50A65BCE` |

校验：`aapt2 dump badging` → `io.legado.pro` / `versionCode 11000` / `versionName 1.10` / `sdkVersion 24` /
`targetSdkVersion 33`；`apksigner verify` v2 通过，证书 SHA-256
`81ce1c10476fa400ed962c4a18b54ae4497581320b1d940d896d62b6c609ef0f`（与 1.9 三包一致）。

### 升级方式

装了 1.0 – 1.9 的直接覆盖安装即可；服务端地址与令牌、书源、书架、进度都保留。
回传协议未变，不需要重新导入书源或重建缓存。
