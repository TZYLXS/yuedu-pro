## 阅读Pro 2.6.18.1（versionCode 2061801）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.6.18.1** 同步发布、同一套版本号；版号基线取自服务端 `legado-web` **v2.6.18**，本版为其上的 `0.0.0.1` 小更新。

### 本版变更：软总线加权路由 + 封面/简介拆分（有真实代码改动）

服务端在 2.6.16 之后推进 2.6.17 / 2.6.18（`b639ec2..ecf040`），把软总线从「所有设备抢同一队列」升级为加权路由：

- **任务分三类**：`book`（search/toc/rank）、`content`（content/ebook-parse/ebook-chapter）、`media`（cover/intro/bookinfo）
- **隐藏性能分（0–100，不对外暴露）**：初始 50，成功 +4、失败 −8、>8s 再 −2；`score>=55` 高分可领全部类、在途上限 3，
  `<55` 低分只领 media、上限 2；无高分设备在线时自动放开全部任务
- **领取即锁定 30 秒**：写 `dispatchedTo`，其它设备不重复领；结果幂等——重复回传返回 `duplicate`、非锁定设备回传丢弃返回 `stale`；
  权重计算独立成纯函数模块 `busweights.js`，异常自动降级
- **2.6.18 修复**：在途数按实际锁定任务重算，修掉异常路径 inflight 泄漏把设备卡死；隐藏分从 `/api/bus` 移除，新增 `highScore`/`lowScore` 计数

客户端落实（`FnReaderRemote.kt` + `FnReaderBridgeService.kt`）：

- 新增 `cover` / `intro` 任务，payload 与 bookinfo 相同 `{bookUrl, source, name, author}`：抽出 `fetchMediaBook()`，
  bookinfo/cover/intro 共用同一次 `WebBook.getBookInfoAwait`；`cover()` 只回 `{coverUrl}`，`intro()` 回简介与元数据，
  拿不到的字段省略；不支持时服务端自动回退 bookinfo
- `submit()` 识别幂等字符串态：`ok:"duplicate"`/`"stale"` 正常返回不重试，`ignored` 仍按调试设备失败抛出
- 队列路由：`bookinfo`/`cover`/`intro` 统一进 info 队列；线程池大小与超时（18/18/23/23/9 秒）不变

### 文档同步

- `docs/阅读Pro.md` 新增本版章节、`README.md` 版本号/下载表/能力清单更新
- `docs/app回传接受规范.md` 新增 §18（含 18.1 落实、18.2 自测清单），本地正本与两仓 docs 三份副本 SHA 一致
- 应用内 `assets/updateLog.md` 顶部新增 `**2026/11/17（2.6.18.1）**`，老段落保留

### 包信息

| 产物 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.6.18.1-arm64.apk` | 18 780 060 B（17.91 MB） | `99F4EF854D4B1494B0010E602868744534CC86232B502F6F3891CDEF8FE81601` |
| `yuedu_pro_app_2.6.18.1-armv7.apk` | 18 735 080 B（17.87 MB） | `2C0BBA73ABB333C1D7B6AECE7D2C889E927AC866DE6C16A0A651BF57F8B2B7C8` |

- `aapt2 dump badging`：`package: name='io.legado.pro' versionCode='2061801' versionName='2.6.18.1'`，两个包均无 `application-debuggable`
- 包内 `assets/updateLog.md` 首条已核对为 `**2026/11/17（2.6.18.1）**`（`python zipfile` 解包读取）
- 签名沿用 `keystore/yuedu.jks`（v1 + v2），可直接覆盖安装旧版

### 安装方式

1. 下载对应 ABI 的 apk（2017 年后的手机 / 平板 / 电视盒子选 arm64，老 32 位设备选 armv7）
2. 覆盖安装即可升级，书源与书架数据保留
3. 进入「我的 → 接入 FnReader 服务器」，填服务端地址与 `X-Api-Token`

### 自测与限制

- 编译：Gradle `-Xmx2048m` + Kotlin 守护进程 `-Xmx1536m` + `--max-workers=2`，arm64 2m40s / armv7 23s，exit 全 0
- 未跑项：真机实派 cover/intro、构造 duplicate/stale、观察 highScore/lowScore，步骤见 `docs/app回传接受规范.md` §18.2
