## 阅读Pro 1.3（versionCode 10300）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。

### 本版新增：手机当服务器的外挂解析器

FnReader 从 `1.7.26` 起多了「手机端解析桥」：飞牛（NAS）上跑不动的书源——要 WebView、要浏览器环境、接口加密的那些——不再直接报错，而是把任务派给手机，手机用本地书源规则跑一遍再把结果传回去。

阅读Pro 这一版把这条链路接上了：

- 「接入 FnReader 服务器」弹窗新增第 ⑧ 项 **「手机解析待命」**，点一下即开启，通知栏常驻一条「FnReader 手机解析」
- 开启后前台服务持续长轮询 `/api/m/remote/pending`，领到 `search` / `toc` / `content` 任务后调用本机 WebBook 执行：
  - `search` → `searchBookAwait`，回 `{books:[{name,author,bookUrl,coverUrl,intro,kind,lastChapter}]}`
  - `toc` → `getChapterListAwait`，回 `{chapters:[{index,title,url}]}`
  - `content` → `getContentAwait`，回 `{paragraphs:[...], title}`
- 结果 POST 到 `/api/m/remote/result`，飞牛那边的网页搜索即刻用手机解析出来的书/章/正文，来源标记走 `via:'phone'`
- **单个任务 45 秒上限**，每个任务独立协程：某个书源卡住只会让这一个任务失败并回一句「解析超时，这个书源本机也跑不动」，不会把整条待命堵死，后面的任务照常处理
- 书源地址做了归一化兜底（去掉 `http(s)://`、尾斜杠、大小写差异），飞牛库里存 `http://www.gdbzkz.com/`、本机存不带斜杠的，也能对上
- 关掉再打开、进程被杀后重进设置，只要开关还是开着的就会自动把服务拉起来

用法：手机开「⑧ 手机解析待命」→ 回飞牛网页正常搜书，跑不了的书源自动由手机接手。两台设备都开着也行，谁先领到谁跑。

### 上一版（1.2.1）修的东西还在

同步按钮点击即有「同步中…」反馈、成功弹「新增 N 本　更新 M 条进度（本地书架 K 本）」、书架与进度真正写入本地库、地址自动探测（填的地址 → 同主机 1122 → +`/app/legado-web`）、报错翻成人话。

### 从 1.2.1 升级

同签名，直接覆盖安装（versionCode `10300` > `10201`）。

### 版本规则

安卓 4.0 版（`TZYLXS/yuedu-android4`）与阅读Pro（`TZYLXS/yuedu-pro`）共用版本号，每次迭代 **+0.1**，满 10 进 1。
本版两端同为 **1.3 / 10300**，tag `v1.3`。

### 安装

1. 按设备选包：主流 64 位机装 `-arm64`，老 32 位设备装 `-armv7`
2. 允许「未知来源」→ 安装
3. 接飞牛书库：我的 → 接入 FnReader 服务器 → 填地址与令牌 → 同步 → ① 导入书源 → ⑧ 开启手机解析待命

### 产物信息

| 项 | armv7 | arm64 |
| --- | --- | --- |
| 文件 | `yuedu_pro_app_1.3-armv7.apk` | `yuedu_pro_app_1.3-arm64.apk` |
| ABI | armeabi-v7a | arm64-v8a |
| 体积 | 18,339,722 B（17.49 MB） | 18,384,689 B（17.53 MB） |
| SHA256 | `5DDB3748FA6E32D85BF66C946EBAA6CFDFE1E4DA3A8D4C5AC2F5875C4F9D8CEF` | `4887C49A94D3585C75D8B4DDEB3E33A051B7E556633CD337BF863A451D728380` |

公共参数：包名 `io.legado.pro`、versionName `1.3`、versionCode `10300`、minSdk `24`、targetSdk `33`、compileSdk `34`、
签名证书 `CN=FnReader YueDu`（SHA-256 `81ce1c10476fa400ed962c4a18b54ae4497581320b1d940d896d62b6c609ef0f`）。

### 验证情况（MuMu，Android 12）

- ⑧ 开启后 `dumpsys` 显示服务 `isForeground=true foregroundId=108`
- 用 `POST /api/remote/job` 打真实任务，三类全部回 `ok:true`：
  - `search`（鬼吹灯源，斗破苍穹）→ 50 本
  - `toc`（穿越斗破苍穹）→ 225 章
  - `content`（第 1 章）→ 22 段真实正文

### 已知限制

- 未做混淆 / 资源压缩，单包约 17.5 MB
- 手机解析待命要求 App 进程活着；被系统清理后重进「我的 → 接入 FnReader 服务器」会自动拉起
- 手机解析不了的书源仍会失败，此时错误是「解析超时（45 秒没出结果）」，不会一直挂着

### 关于旧版本

Release 列表只保留当前版本，`v1.3` 之前的版本在下次触发 prune 后会清理。
