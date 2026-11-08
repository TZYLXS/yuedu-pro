## 阅读Pro 1.9（versionCode 10900）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）1.9** 同步发布、同一套版本号。

### 本版主线：跟进文档 §15，软总线新增 `rank` 代抓榜单

服务端 FnReader 已到 **1.11.2**。`docs/APP同步接口.md` 这一轮新增：

| 文档章节 | 变更 | 对客户端的影响 |
| --- | --- | --- |
| §15 每日推荐（`GET /api/daily`） | 榜单按「起点 → 纵横 → 17K」依次抓，6 小时缓存；起点有反爬（202 挑战页）时先让软总线上的在线手机抓（任务类型 `rank`） | 需要客户端支持 `rank` 任务 |
| §16 缓存同步 / §17 书源双向同步 | 措辞细化（32 MB 上限、重名建 `(2)`、LWW 与 `pull` / `pullDeleted`） | 1.8 已按此实现，本轮复核协议无差异，未改代码 |

`docs/更新日志.md` 另外补了 1.10.0 – 1.11.2 六段版本记录（每日推荐、`rank`、缓存上传协议、书源 C/S 同步、导源报错修复），只做记录，无接口变化。

### 代码改动（与安卓 4.0 版同构）

- `model/FnReaderRemote.kt`
  - `execute()` 增加 `"rank"` 分支（1.8 及以前收到 `rank` 直接回 `ok:false`）
  - 新增 `rank(payload)`：浏览器 UA 抓 `payload.url`，正文不足 10 KB 视为反爬挑战页直接报错；Jsoup 按三套结构解析
    （起点 `.book-img-text li` / `.rank-list li`、纵横 `.zh-modules-rank-book`、17K `a[href*="/book/"]` + `/book/<数字>.html`），
    按 url 去重、补 `rank` 序号，回传 `{source,url,books:[{rank,name,url,author,cover,score}]}`
  - 新增 `httpGetText()`（8 秒连接 / 8 秒读取、跟随跳转、非 2xx 报错）与 `String.toAbsolute()`
- `service/FnReaderBridgeService.kt`
  - `timeoutOf("rank")` = 23 秒（与正文同档），`rank` 单独跑一根协程，不堵队列
- `ui/config/FnReaderConfigDialog.kt`
  - 菜单 ⑨ 文案补「服务端更进的版本覆盖本地（`pull` / `pullDeleted`）」，与 §17 建议流程对齐

回传字段、接口路径、鉴权方式（`X-Api-Token` 一律走请求头）**都没有变**，1.8 的推书 / 进度 / 封面 / 缓存 / 书源同步照旧。

### 实测结果

- 编译：arm64、armv7 均 `BUILD SUCCESSFUL`
- 模拟器（MuMu，Android 12 / API 31）装 1.9 包：首启更新日志首条为 **2026/11/09（1.9）**；「我的 → 接入 FnReader 服务器」九项菜单齐全，⑤ 手机解析待命可开可关
- 软总线实测：开启待命后以 `ver=1.9` 上报设备（服务端 `/api/bus` 可见），领到 `type=rank` 任务并回传：
  - 起点 `https://www.qidian.com/rank/recom/` → `ok:false`，「榜单页只拿到 209 字节，八成被反爬拦了」——反爬页识别按预期工作
  - 纵横站点请求 → `ok:false`，「榜单页返回 HTTP 500」
  - 两种情况服务端 12 秒等不到结果会自动降级到纵横 / 17K，网页端「每日推荐」不退化
- 未跑通的路径：**成功解析 + 成功回传**这一条本轮没在真机复现——当天服务端 `daily.json` 还在 6 小时缓存有效期内（`/api/daily` 返回 `cached:true`），没触发 `rank` 下发；本地假站点又被本机端口监听环境挡住。解析用的选择器与回传结构是按服务端 `Tre()` / `Dre()` / `yre()` 与 `ph()` 对齐的

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 1.0 – 1.8 一致（证书 SHA-256 `81ce1c10…09ef0f`），可直接覆盖升级，数据不丢

### 包信息

| APK | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_1.9-arm64.apk` | 17.56 MB（18 412 516 B） | `DC1F3F16DEE24CCDF3EFE5C051D6A9C8CE4D943EBF57CD7D202DB5EFCCC71D63` |
| `yuedu_pro_app_1.9-armv7.apk` | 17.52 MB（18 367 539 B） | `1E11DC4FAEB898305F3F64D3EE73046214834D29CB07A59A896C2FEA57705F79` |

校验：`aapt2 dump badging` → `io.legado.pro` / `versionCode 10900` / `versionName 1.9` / `sdkVersion 24` / `targetSdkVersion 33`；
`apksigner verify` v2 通过，证书 SHA-256 `81ce1c10476fa400ed962c4a18b54ae4497581320b1d940d896d62b6c609ef0f`。

### 升级方式

装了 1.7 / 1.8 的直接覆盖安装即可；服务端地址与令牌、书源、书架、进度都保留。
本轮不需要重新导入书源，也不需要重建缓存（回传协议未变）。
