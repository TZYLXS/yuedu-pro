## 阅读Pro 2.6.13.1（versionCode 2061301）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.6.13.1** 同步发布、同一套版本号；版号基线取自服务端 `legado-web` **v2.6.13**，本版为其上的 `0.0.0.1` 小更新。

### 一、跟进服务端 2.6.13（调试设备识别扩展）

服务端 2.6.13（`737cd6e`，2026-11-17 04:22）把 2.6.12 的调试设备判定放宽：

```js
/(bus[-_]?debug|busmon|\bmonitor\b|\bmock\b|\bfake\b|\be2e\b|\bdebug\b)/i.test(text) ||
  /(调试|监控|测试机)/.test(text);
```

ID 追加 `busmon` / `monitor` / `mock` / `fake` / `e2e`，名称追加「监控」「测试机」；同时修掉调试工具把**真实书籍目录**
写成 `http://debug.local/...` 的问题（调试设备回传不再参与真实解析）。

接口形状未变（`pending` 仍 `{jobs, waitMs, debug}`、`result` 仍回 `ignored:true`，任务类型 / 超时 / 令牌全部照旧），
所以契约层面客户端**不动逻辑**，只把提示文案对齐：

- `FnReaderRemote.submit()` 的失败原因改为「ID 含 bus-debug/busmon/monitor/mock/fake/e2e/debug，或名称含「调试」「监控」「测试机」」
- `FnReaderRemote.Pending` 的 KDoc 写成 2.6.12 → 2.6.13 的判定演进
- `FnReaderBridgeService` 通知文案保持不变（只说「改设备 ID / 名称」，不列关键字，避免通知栏过长）

### 二、手机解析改多线程（本版客户端性能改动）

原来 `FnReaderBridgeService` 用「检索 2 并发 + 简介单并发」的协程跑任务，协程默认调度器的线程数受 `Dispatchers.IO`
默认上限约束，慢源一多就互相等。本版改成**三条真线程池**，Pro 面向 8 核 / 2 大核（A72 及以上）的高性能机：

| 线程池 | 装什么任务 | 线程数 |
| --- | --- | --- |
| `parseDispatcher` | search / toc / content / rank | 按 `availableProcessors()` 自适应：≥8 核 **6** 条、6 核 **4** 条、4 核 **3** 条、更低 **2** 条 |
| `infoDispatcher` | bookinfo（**封面与简介**） | ≥8 核 **2** 条、其余 1 条，独占一池，永远不排在检索任务后面 |
| `ebookDispatcher` | ebook-parse / ebook-chapter | **1** 条，整本解析独占，避免吃 CPU 的任务抢走检索与简介的线程 |

- 三条池在服务启动时创建（守护线程，命名 `fnreader-parse` / `fnreader-info` / `fnreader-ebook`），`onDestroy` 时一起 `close()`，不会漏线程
- 任务超时、回传格式、令牌、错误码全部不变；解析失败仍回 `ok:false` + 原因，服务端行为不受影响
- 并发上限留了余量：8 核只开 6 条解析线程，避免把书源站点打到限流

### 三、文档同步

- `docs/阅读Pro.md` 新增本版章节（含线程池三件套说明）、`README.md` 更新「软总线三条线程池」能力条目与任务表
- `docs/飞牛书库接入.md` 的队列段落改写为线程池口径（去掉「单并发 / 2 并发」旧说法）
- `docs/app回传接受规范.md` 新增 §16（2.6.13 判定表 + 16.1 客户端落实 + 16.2 自测清单），三份副本（本地正本与两仓 docs）SHA 一致
- 应用内 `assets/updateLog.md` 顶部新增 `**2026/11/17（2.6.13.1）**`，含 2.6.13 跟进 4 条 + 多线程 4 条，老段落保留

### 包信息

| 产物 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.6.13.1-arm64.apk` | 18 778 730 B（17.91 MB） | `EBA7CAD14775CF7D9A8AB05955B7C4382A2DAF661313409D2EC22C279012FC72` |
| `yuedu_pro_app_2.6.13.1-armv7.apk` | 18 733 749 B（17.87 MB） | `E7746A5F09C75536B2C88E79F9C8ED04A3D6F7384E5DDD90CE74B4E54E4DD232` |

- `aapt2 dump badging`：`package: name='io.legado.pro' versionCode='2061301' versionName='2.6.13.1'`，两个包均无 `application-debuggable`
- 包内 `assets/updateLog.md` 首条已核对为 `**2026/11/17（2.6.13.1）**`（`python zipfile` 解包读取）
- 签名沿用 `keystore/yuedu.jks`（v1 + v2），可直接覆盖安装旧版

### 安装方式

1. 下载对应 ABI 的 apk（2017 年后的手机 / 平板 / 电视盒子选 arm64，老 32 位设备选 armv7）
2. 覆盖安装即可升级，书源与书架数据保留
3. 装好后进入「我的 → 接入 FnReader 服务器」，填服务端地址（`http://10.5.4.70:1122` 或 `http://192.168.5.114:1122`）与 `X-Api-Token`

### 自测与限制

- 编译：Gradle `-Xmx2048m` + Kotlin 守护进程 `-Xmx1536m` + `--max-workers=2`；本轮 arm64 前两次在 1024m Kotlin 堆下 `compileAppReleaseKotlin` OOM，
  提到 1536m 后两 ABI 均一次通过（arm64 约 3 分钟）
- 静态校验：线程池三处 `close()`、任务分流（bookinfo → info 池，ebook → ebook 池，其余 → parse 池）已逐行核对
- **未做真机 / 模拟器冒烟**：MuMu 长期 `offline`，未起模拟器；并发压测（同一书源 4 条 search 是否触发限流）留待真机，
  步骤见 `docs/app回传接受规范.md` §16.2 未勾选项
