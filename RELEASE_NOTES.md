## 阅读Pro 1.4（versionCode 10400）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。

### 本版新增：书源桥 + 连接保活

FnReader `1.7.30` 在 docs 里给了「给『阅读』APK 用的书源桥」：服务端把书架、书源、缓存打包成**一个**书源（`bookSourceName = 飞牛书库`），搜索 / 详情 / 目录 / 正文全部由飞牛执行。这一版把入口和保活都做进客户端。

**⑨ 接入书源桥**（「接入 FnReader 服务器」弹窗新增项）

- 拉 `GET /api/m/booksource?token=…` 拿服务端实时生成的那份 Legado 书源，走标准 `legado://import/bookSource?src=…` 导入
- 导入后是一条「飞牛书库」，本机不再需要几百条书源规则：
  - 搜索 `GET /api/m/legado/search?token=…&key={{key}}&page={{page}}`，规则 `$.books[*]`
  - 详情 `GET /api/m/legado/book?u=&s=&n=`，不在书架时服务端自动 `upsertBook`（`addedBy:"apk"`）
  - 目录 `GET /api/m/legado/toc?u=`，章节 url 直接指向 `/api/m/content?url=…&index=i`
  - 正文 `GET /api/m/content`，规则 `$.paragraphs[*]`
- 换服务器地址 / 换令牌后，重新点一次 ⑨ 覆盖导入即可

**连接保活**

- 手机解析待命连上后每 **60 秒**心跳一次 `GET /api/m/info`，通知栏不再是「等待飞牛派任务」，而是「**已连接 FnReader 1.7.30　待命中**」，干完活自动回到这个状态
- 心跳同时校验令牌与地址：令牌被重置、服务没起来、换了网段，都会立刻显示「连不上服务器，重试中」，并在弹窗状态区标出「已连接 / 正在连接」
- 掉线按 3 秒重试，长轮询本身 `wait=15000`，20 秒内的派单不会漏
- 新增 `RECEIVE_BOOT_COMPLETED` 与 `FnReaderBridgeBootReceiver`：**开关开着时开机自动拉起前台服务**，不用再进设置页点一次

**顺带修掉**

- 「① 导入书源」之前是把 http 地址直接丢给 `OnLineImportActivity`，该页面只认 `legado://import/bookSource?src=…`，读不到 `src` 会静默退出。现在改成标准导入链接，① 与 ⑨ 都稳了

### 与 1.3 的关系

1.3 的手机解析待命（⑧）原样保留：⑨ 解决的是「让手机不装规则也能读飞牛」，⑧ 解决的是「飞牛跑不动的源交给手机跑」，两者互不冲突。

### 从 1.3 升级

同签名，直接覆盖安装（versionCode `10400` > `10300`）。书架、书源、进度都在。

### 版本规则

安卓 4.0 版（`TZYLXS/yuedu-android4`）与阅读Pro（`TZYLXS/yuedu-pro`）共用版本号，每次迭代 **+0.1**，满 10 进 1。
本版两端同为 **1.4 / 10400**，tag `v1.4`。

### 安装

1. 按设备选包：主流 64 位机装 `-arm64`，老 32 位设备装 `-armv7`
2. 允许「未知来源」→ 安装
3. 接飞牛：我的 → 接入 FnReader 服务器 → 填地址与令牌 → 测试连接/同步 → ⑨ 接入书源桥（或 ① 导入全部书源）→ 需要手机代跑时再开 ⑧

### 产物信息

| 项 | armv7 | arm64 |
| --- | --- | --- |
| 文件 | `yuedu_pro_app_1.4-armv7.apk` | `yuedu_pro_app_1.4-arm64.apk` |
| ABI | armeabi-v7a | arm64-v8a |
| 体积 | 18,341,805 B（17.49 MB） | 18,386,790 B（17.54 MB） |
| SHA256 | `2B7CBA29E378103877512A45A9F00183DB548F6B0B3A45549B75D279EDF11CD6` | `D3E3517519EC0F695851E07A97F50D89F1F3A059A67C1B654C15172003EF5226` |

公共参数：包名 `io.legado.pro`、versionName `1.4`、versionCode `10400`、minSdk `24`、targetSdk `33`、compileSdk `34`、
签名证书 `CN=FnReader YueDu`（SHA-256 `81ce1c10476fa400ed962c4a18b54ae4497581320b1d940d896d62b6c609ef0f`）。

### 验证情况（MuMu，Android 12，对着线上 1.7.30）

- ⑨ 点下去弹「导入书源」列表：「飞牛书库 新增」，确认后书源总数 838 → 839
- `dumpsys notification` 两个包的通知正文均为「已连接 FnReader 1.7.30　待命中」，服务 `isForeground=true foregroundId=108`
- 服务端侧同一份书源三条接口实测（不带任何手机端规则解析）：
  - `/api/m/legado/search?key=H` → 命中「特级咒灵在H×H」，`bookUrl` 指向 `/api/m/legado/book`
  - `/api/m/legado/book` → 返回 `book` 对象，`tocUrl` 指向 `/api/m/legado/toc`，并自动入架
  - `/api/m/legado/toc` → 返回章节数组，章节 url 即 `/api/m/content?url=…&index=0`

### 已知限制

- 书源桥依赖飞牛在线：飞牛关机 / 令牌被重置时，这个源会整条报错（不是客户端解析问题）
- `/api/m/content` 对个别源（如夜伴书屋）服务端自身会卡住，届时本章会一直转圈，重试或换源即可
- 未做混淆 / 资源压缩，单包约 17.5 MB
- 61 秒心跳意味着最坏 1 分钟内才会发现令牌失效；期间任务派发仍会即时失败并提示

### 关于旧版本

Release 列表只保留当前版本，`v1.4` 之前的版本在下次触发 prune 后会清理。
