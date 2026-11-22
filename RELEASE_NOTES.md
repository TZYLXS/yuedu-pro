## 阅读Pro 3.1.4.1（versionCode 3010401）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）3.1.4.1** 同步发布、同一套版本号；版号按服务端 FnReader **3.1.4 + 0.0.0.1** 对齐。

### 客户端修复：书源反向同步 404 不再永久跳过

之前 `FnReaderAutoSync.runSources` 拉取 `/api/m/sources` 遇到 404 会把 `fnReaderNoSourceSync` 永久写成 `"1"`，服务端升级后客户端也不会再试，必须重装或清数据。

- 404 改为 **6 小时时间戳退避**：写入 `now + 6h`，到期自动重试，成功后清为 `"0"`
- 旧值 `"1"` 按时间戳解析为 1970 年，已过期，老用户升级后自动恢复同步
- 全仓仅 4 处读取该键，未发现其他按旧布尔语义使用的代码
- 新增 `docs/客户端书源反向同步.md`，记录接口字段、5 分钟同步预期与验收步骤

### 同步服务端 FnReader v3.1.4

服务端 main/tag `v3.1.4` 为 `b230d8e6609e962481e6077bb01356c9528999c4`，相对 `v3.0.10` 共 12 个提交：

- **3.0.11–3.0.16**：空书架保留亚马逊导航；manifest 补 Node/Java 依赖；设置项改名「亚马逊出版物封面」；修复设置页 `ReferenceError`；`GET /api/books` 不再每次 `dedupeShelf`；Python 持久进程池补 `--worker`
- **3.1.0**：Java 目录引擎移出、TOC 规范化改 Python、进程池扩容、搜索 12 路并发
- **3.1.1**：Python 兼容 3.9；软总线回传目录改单遍解析，兼容直接数组、`{chapters:[...]}`、字符串化 JSON、`title|name`、`url|chapterUrl|fileUrl`，上限 20000 章
- **3.1.2**：发布包补 `vendor39/libatomic`
- **3.1.3**：电子书扫描跳过 `cache/covers/tmp`
- **3.1.4**：修复 `linkLocalEbooksToShelf` 在电子书索引为空时提前 `return`，导致失效 ebook 条目未被移除

客户端契约复核：

- `docs/APP同步接口.md` blob 仍为 `f4b8c9f957a1d609eb0a231123fadaa196ec5644`，未变
- `docs/AI构建指南.md` blob 仍为 `2937e4d4adfec9ae8d2e0c36920afac29ddfc97d`，未变
- `docs/软总线路由与权重.md` 仅新增 8 行「回传数据解析（3.1.1+）」，属于服务端容错，没有新增客户端必填字段、路由或任务类型
- `/api/m/*` 路径集合新旧均为 30 个，无差异

### 包信息

| 产物 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_3.1.4.1-arm64.apk` | 18 796 891 B（17.93 MB） | `073D537AB44B6EF255A53506BF41C19D2EDB4BA11A02459DA112F2AC16CF38A3` |
| `yuedu_pro_app_3.1.4.1-armv7.apk` | 18 751 919 B（17.88 MB） | `FAD349694BA52BEC0DFA6D7CEA4E284151E8E21B9CF74BE76D7AF405C2461B13` |

- `aapt2 dump badging`：两包均 `versionCode='3010401' versionName='3.1.4.1'`，minSdk 24 / target 33，无 `application-debuggable`
- `apksigner verify`：v2 true；minSdk ≥ 24 时 v1 false 属正常；证书 `CN=FnReader YueDu`，SHA256 `81ce1c10476fa400ed962c4a18b54ae4497581320b1d940d896d62b6c609ef0f`
- 包内 `assets/updateLog.md` 首条为 `**2026/11/22（3.1.4.1）**`
- MuMu Android 12（x86_64 + ARM 翻译）覆盖安装 arm64 包，实际显示 3.1.4.1 更新日志并正常启动
- 签名沿用 `keystore/yuedu.jks`，可直接覆盖安装旧版

### 安装方式

1. 下载对应 ABI 的 apk（2017 年后的手机 / 平板 / 电视盒子选 arm64，老 32 位设备选 armv7）
2. 覆盖安装即可升级，书源与书架数据保留
3. 进入「我的 → 接入 FnReader 服务器」，填服务端地址与 `X-Api-Token`

### 说明

- 本版重点是客户端 404 退避修复；服务端 3.1.4 的契约没有新增客户端必填字段
- 应用内「检查更新」指向 GitHub `TZYLXS/yuedu-pro` 的 `releases/latest`；本 Release 发布后即指向 `v3.1.4.1`
