## 阅读Pro 2.6.16.1（versionCode 2061601）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.6.16.1** 同步发布、同一套版本号；版号基线取自服务端 `legado-web` **v2.6.16**，本版为其上的 `0.0.0.1` 小更新。

### 本版变更：仅跟进文档，无代码改动

服务端在 2.6.13 之后连续推进三版（`737cd6e..b639ec2`），全部是服务端内部改动，**客户端契约零变化**：

- **2.6.14**：开启「优先手机解析」后，章节正文也优先走软总线（手机在线时先让手机解析，失败再回退本机）；
  `/api/bus` 新增 `usage` 统计（`phoneSearch` / `phoneToc` / `phoneContent` / `localToc` / `localContent`）
- **2.6.15**：修掉「开了手机代理仍优先本地」——搜索先问手机再跑本机 Python / Node，正文提前到「本机无书源」之前尝试手机
- **2.6.16**：规则引擎加递归保护，书源规则自我引用最多 200 层，超过返回 502「书源规则递归过深，已中止解析」，不再 500 / 爆栈

核对结论：

- 对比 `737cd6e...b639ec2` 仅 5 个文件：`docs/更新日志.txt`、`legado-web/app/server/src/rules.js`、
  `server.js`、`web/index.html`、`manifest`；**`remote.js` 未改**
- `docs/APP同步接口.md`（33 802 B）与 `docs/更新日志.md`（25 298 B）本轮零改动
- `/api/m/*`（含 `/api/m/remote/pending|result`）路径、字段、令牌、任务类型、超时（18 / 18 / 23 / 23 / 9 秒）全部照旧
- 客户端代码零改动，只同步版本号、updateLog 与文档；包体仍是 2.6.13.1 的多线程实现
  （`parse` 自适应 6 / 4 / 3 / 2 条、`info` 封面与简介独占 2 / 1 条、`ebook` 1 条）

### 文档同步

- `docs/阅读Pro.md` 新增本版章节、`README.md` 版本号与下载表更新
- `docs/app回传接受规范.md` 新增 §17（含 17.1 影响评估、17.2 自测清单），本地正本与两仓 docs 三份副本 SHA 一致
- 应用内 `assets/updateLog.md` 顶部新增 `**2026/11/17（2.6.16.1）**`，老段落保留

### 包信息

| 产物 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.6.16.1-arm64.apk` | 18 778 886 B（17.91 MB） | `5231CDB4D5DDBA09F45966E6A961BEEACD2A46EC17ECBDECF94F94767DF5927C` |
| `yuedu_pro_app_2.6.16.1-armv7.apk` | 18 733 905 B（17.87 MB） | `DE038905945627FAD1B64B05EAA6B37893312401985DE71679EDECCE3332E35E` |

- `aapt2 dump badging`：`package: name='io.legado.pro' versionCode='2061601' versionName='2.6.16.1'`，两个包均无 `application-debuggable`
- 包内 `assets/updateLog.md` 首条已核对为 `**2026/11/17（2.6.16.1）**`（`python zipfile` 解包读取）
- 签名沿用 `keystore/yuedu.jks`（v1 + v2），可直接覆盖安装旧版

### 安装方式

1. 下载对应 ABI 的 apk（2017 年后的手机 / 平板 / 电视盒子选 arm64，老 32 位设备选 armv7）
2. 覆盖安装即可升级，书源与书架数据保留
3. 进入「我的 → 接入 FnReader 服务器」，填服务端地址与 `X-Api-Token`

### 自测与限制

- 编译：Gradle `-Xmx2048m` + Kotlin 守护进程 `-Xmx1536m` + `--max-workers=2`，三包顺序构建，exit 全 0
- 模拟器冒烟：MuMu 装本包后「接入 FnReader 服务器」连上 `http://10.5.4.70:1122`，显示「已连接 FnReader 2.6.15（保活中）」，菜单与同步项正常
- 未跑项：真机观察 `usage.phoneContent` 增长、递归保护 502 的客户端表现，步骤见 `docs/app回传接受规范.md` §17.2
