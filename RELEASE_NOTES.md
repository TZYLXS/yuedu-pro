## 阅读Pro 2.6.18.2（versionCode 2061802）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.6.18.2** 同步发布、同一套版本号；版号基线取自服务端 `legado-web` **v2.6.18**。

### 本版仅跟进文档，无代码改动

上游 `ecf0408..c3922a3` 有 2 个提交、2 个文件变化：

- `6bfdbdc6`：`AGENTS.md` 新增上游环境专用的工具调用约定（+11），与安卓客户端接口无关
- `c3922a35`：`docs/软总线调试APK.md` 新增 §6.3（+29），补充 `GET /api/bus` 在线设备字段与历史实测快照

新增说明的 `devices[]` 字段为 `id`、`name`、`model`、`version`、`lastSeen`、`handled`、`enabled`、`debug`、`online`、`idle`，
顶层另有 `pending`、`recent`、`usage`。这些都是面板⑦的只读信息，客户端现有 `GET /api/bus` 调用不需要修改。

三份重点文档 blob 与 2.6.18 基线一致：`APP同步接口.md` = `6b9b701f…`、`更新日志.md` = `84c99abc…`、
`更新日志.txt` = `f1099dcb…`；compare 不含 `remote.js` / `server.js`，所以 `/api/m/*` 路径、字段、鉴权、任务类型与超时均无变化。

**文档口径校正**：`cover` / `intro` 在 `timeoutOf()` 中没有独立分支，当前实际使用 default **20 秒**；
`bookinfo` 独立分支仍为 9 秒。本版只把 README 与接入文档中的 cover/intro 超时改为 20 秒，没有修改客户端代码。

### 文档同步

- `README.md`、`docs/阅读Pro.md`、`docs/飞牛书库接入.md` 更新到本版并补充 `/api/bus` 字段表
- `docs/app回传接受规范.md` 新增 §19，本地正本与两仓 docs 三份副本 SHA256 一致
- 应用内 `assets/updateLog.md` 顶部新增 `**2026/11/17（2.6.18.2）**`，旧段落保留

### 包信息

| 产物 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.6.18.2-arm64.apk` | 18 780 324 B（17.91 MB） | `DB2A1E2A274075F872E199FE9F50C262538873C49E1533A3A4792C4117784042` |
| `yuedu_pro_app_2.6.18.2-armv7.apk` | 18 735 346 B（17.87 MB） | `F46BBC51660343F763E2408D0F341E59D3E5AA3E43415C306AA6FFAEC3EFB772` |

- `aapt2 dump badging`：两包均为 `versionCode='2061802' versionName='2.6.18.2'`，无 `application-debuggable`
- 包内 `assets/updateLog.md` 首条已用 Python zipfile 核对为 `**2026/11/17（2.6.18.2）**`
- 签名沿用 `keystore/yuedu.jks`（v1 + v2），可直接覆盖安装旧版

### 安装方式

1. 下载对应 ABI 的 apk（2017 年后的手机 / 平板 / 电视盒子选 arm64，老 32 位设备选 armv7）
2. 覆盖安装即可升级，书源与书架数据保留
3. 进入「我的 → 接入 FnReader 服务器」，填服务端地址与 `X-Api-Token`

### 自测与限制

- 编译：Gradle `-Xmx2048m` + Kotlin `-Xmx1536m` + `--max-workers=2`；arm64 **1m49s**、armv7 **37s**，exit 全 0
- MuMu 模拟器：arm64 覆盖安装成功，`dumpsys` 显示 2061802 / 2.6.18.2；首启更新日志显示本版，
  「我的 → 接入 FnReader 服务器」可打开，已连接 FnReader 2.6.18，导入书源 / 全部同步 / 打开网页等菜单正常
- 本版未改代码，未重复执行全量书架同步；真机实派 cover/intro、构造 duplicate/stale 仍是既有 §18.2 待观察项
