# APP 回传接受规范

> 服务端依据：FnReader `1.11.3`（`docs/APP同步接口.md` 第 4 / 9 / 11 / 13 / 14 / 15 / 16 / 17 章）
> 客户端：阅读Pro `1.11`（`io.legado.pro`）、安卓4.0 版 `1.11`（`io.legado.api19`）
> 本文写的是**服务端接收侧**的规矩：APP 往飞牛回传什么、字段怎么填、什么会被拒、什么会被合并。

## 0 通用约定

| 项 | 规定 |
| --- | --- |
| 基址 | `http://<局域网IP>:1122`，反代后可为 `https://域名`（可带 path 前缀，如 `/app/legado-web`） |
| 鉴权 | 请求头 `X-Api-Token: <令牌>`。APP 自己发的请求**一律不把令牌拼进 URL**；仅「导入书源」交给 Legado 的链接（`legado://import/bookSource?src=…`）除外，因为第三方客户端无法自定义请求头 |
| 请求体 | `Content-Type: application/json; charset=utf-8`；UTF-8 编码 |
| 响应 | JSON；非 2xx 时正文可能是 `{"error":"…"}` 或纯文本（如 `token 无效`） |
| 大小上限 | 单个 JSON 请求体不宜超过 8 MB（封面 base64 内嵌时尤其注意，见 §1.3） |
| 错误码 | `401` 令牌缺失/无效 → 重新填令牌；`404` 书不在书架 / 章节不存在；`405` 方法不对；`502` 上游书源抓取失败（可对同一接口重试一次） |
| 实时反馈 | 推书成功、换封面成功后服务端通过 `GET /api/events`（SSE）广播，网页书架自动刷新，无需轮询 |

回传一共 5 类：**推书（含封面）**、**回传进度**、**单传封面**、**推书源**、**回传解析结果**。

## 1 推书：`POST /api/m/books`

### 1.1 单本

```http
POST /api/m/books      X-Api-Token: …
{
  "bookUrl": "https://…",
  "name": "书名",
  "author": "作者",
  "coverUrl": "https://…",
  "intro": "简介",
  "originName": "手机端",
  "totalChapterNum": 120,
  "index": 3,
  "title": "第三章 标题"
}
```

### 1.2 批量

```http
POST /api/m/books      X-Api-Token: …
{ "books": [ { … }, { … } ] }
```

也接受顶层直接是数组 `[ {…}, {…} ]`。**单批 ≤ 20 本**（内嵌 base64 封面时建议 10~15 本）。

响应：

```json
{ "ok": true, "added": 1, "updated": 0, "total": 11 }
```

### 1.3 字段表

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `bookUrl` | ✅ | string | 主键。**服务端按它合并**，不是按书名 |
| `name` | ✅ | string | 书名，空名会被当作无效记录 |
| `author` | — | string | 作者 |
| `coverUrl` | — | string | 在线封面地址（只要 http/https） |
| `coverMime` | — | string | `image/png` / `image/jpeg` / `image/webp` / `image/gif`，与 `coverData` 配对 |
| `coverData` | — | string | 封面 base64（可带 `data:image/png;base64,` 前缀，也可纯 base64）；落盘后该书 `coverUrl` 自动改指向 `/api/covers/<文件名>` |
| `intro` | — | string | 简介，建议 ≤ 500 字 |
| `origin` | ✅ | string | **书源真实地址**，取 `BookSource.bookSourceUrl`（如 `http://www.qiushu.info`）。服务端靠它取目录与正文，空 = 这本书没有来源（见 §10） |
| `originName` | — | string | 该源的 `bookSourceName`（如 `🎉 狗狗书籍`）。**不要写 `手机端` 之类占位** |
| `totalChapterNum` | — | int | 总章节数 |
| `index` | — | int | 当前读到的章节下标（从 0 起） |
| `title` | — | string | 当前章节标题 |

### 1.4 服务端处理规则

- **按 `bookUrl` 合并**：已存在 → `updated`，只覆盖**本次传上来的字段**，没传的字段保持原值；不存在 → `added`
- 同一本重复推是安全的（幂等），可以放心重试
- 封面：带 `coverData` 时落盘到应用数据目录 `covers/`，文件名取 `bookUrl` 的 **SHA1 前 16 位** + 扩展名；单张 ≤ **8 MB**
- 推书成功后广播 `{"type":"shelf","action":"add","bookUrl":"…","via":"app"}`

## 2 回传进度：`POST /api/m/progress/batch`

```http
POST /api/m/progress/batch      X-Api-Token: …
{ "items": [
  { "bookUrl": "https://…", "name": "书名", "author": "作者",
    "index": 45, "pos": 0, "title": "第46章 石清虚" }
] }
```

响应：

```json
{ "ok": true, "updated": 2, "total": 3, "missing": ["https://…"] }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `bookUrl` | ✅ | 对应书架里的书 |
| `index` | ✅ | 章节下标（从 0 起） |
| `pos` | — | 章内字符位置 |
| `title` | — | 章节标题 |
| `name` | — | **带上它，服务端会在书不存在时自动建书**；不带则进 `missing` |
| `author` | — | 与 `name` 同时生效 |

规则：

- 书不存在的项：带 `name` → 走「建书 + 写进度」；不带 `name` → `missing` 里返回 `bookUrl`，APP 可据此补推
- 单条回传用 `POST /api/m/progress`，字段同上（`{bookUrl,index,pos,title}`）
- 每条成功的项都会广播 `{"type":"progress","bookUrl":…,"index":…,"title":…,"via":"app"}`

## 3 单传封面：`POST /api/m/cover`

**A. JSON + base64**

```http
POST /api/m/cover      X-Api-Token: …
{ "bookUrl": "https://…", "mime": "image/jpeg", "data": "<base64>" }
```

**B. 直接传二进制**（更省内存，推荐大图）

```http
POST /api/m/cover?bookUrl=https%3A%2F%2F…      X-Api-Token: …
Content-Type: image/png
<二进制>
```

响应：

```json
{ "ok": true, "coverUrl": "http://IP:1122/api/covers/5c1490be35afe31c.png",
  "size": 70, "book": true }
```

- `bookUrl` 命中书架里的书时自动改写该书 `coverUrl`（`book: true`）
- 读取：`GET /api/covers/<文件名>`，**无需令牌**，带 7 天缓存
- 单张 ≤ 8 MB；png / jpeg / webp / gif

## 4 推书源：`POST /api/m/sources`

```http
POST /api/m/sources      X-Api-Token: …
[ { "bookSourceName": "…", "bookSourceUrl": "…", "ruleSearch": { … } }, … ]
```

也接受 `{ "sources": [ … ] }`。按 `bookSourceUrl` 去重：已存在 → 用新字段覆盖（`enabled` 不传则保留原值），新源默认启用。响应 `{ "ok": true, "added": 1, "updated": 0, "total": 1253 }`。

## 5 领任务 / 回结果（软总线）

- 领任务：`GET /api/m/remote/pending?wait=15000&limit=5&id=<设备id>&name=<设备名>&model=<机型>&ver=<版本>`（长轮询，`wait` ≤ 30s）；也可用请求头 `X-Device-Id / X-Device-Name / X-Device-Model`
- 回结果：`POST /api/m/remote/result`，`{ "id": "<任务id>", "ok": true, "data": { … } }`；失败传 `{ "id": …, "ok": false, "error": "原因" }`
- 60 秒内有轮询算在线；服务端按 `id` 去重
- 任务类型：`search` / `toc` / `content`（本机书源规则）/ `rank`（代抓榜单，1.9 起支持）
  - `rank` 的 `payload` 是 `{ "url": "https://www.qidian.com/rank/recom/" }`，`data` 约定为
    `{ "source": "起点", "url": "…", "books": [ { "rank": 1, "name": "…", "url": "…", "author": "…", "cover": "…", "score": "…" } ] }`
  - 本客户端抓页面不足 10 KB（反爬挑战页）或解析 0 本时回 `{ok:false,error:"原因"}`，服务端自动降级到纵横 / 17K
  - `rank` 超时按 23 秒（与正文同档），单独协程执行，不阻塞其它任务
- 1.10.0 新增 `GET /api/daily`（每日推荐，6 小时缓存，`stale:true` 表示用的是旧结果），与回传无关，APP 只读

## 6 本项目 1.6 / 1.7 / 1.8 的落实方式

「③ 全部同步到飞牛书架」按钮 = 一次把 **推书 + 回传进度** 跑完：

1. `GET /api/m/sync`（带 `X-Api-Token`）拿飞牛书架，建三份索引：`bookUrl`、归一化书名、`bookUrl → coverUrl`
2. 本机书架按「有书名、非 `file:` 源」过滤；同名多源只留一本（记「本机重复」）
3. 分类：飞牛有同 `bookUrl` → 只回传进度；飞牛有同名但 URL 不同 → **跳过**（记「重名跳过」）；都没有 → 推书 + 回传进度
4. 飞牛有这本书但 `coverUrl` 为空、本机有封面 → 也推一次，服务端只覆盖所传字段，等于补封面（记「补封面」）
5. 推书按 §1.2 每批 **15 本**：`{ "books": [ … ] }`，每本带 `bookUrl/name/author/coverUrl|coverMime+coverData/intro/originName/totalChapterNum/index/title`
6. 封面两条通道：在线图给 `coverUrl`；本机缓存图读文件转 base64 给 `coverData`（跳过 `content://`，单张 > **3 MB** 不发，留余量给请求体）
7. 进度一次性 `POST /api/m/progress/batch`，每项带 `bookUrl/name/author/index/pos/title`；响应里的 `missing` 再补推一次对应书
8. 任何一步非 2xx 都报出**是哪一步**（读书架 / 推书 / 回传进度）+ 原因（令牌、超时、DNS、端口、证书分因提示）

与规范不一致、但按用户要求保留的差异：

- 规范建议「只推新增」，本客户端按需求**全量比对后推**（服务端按 `bookUrl` 合并，幂等，代价是多一次元数据覆盖）
- 规范 14.4 的**持久化重试队列（加书即同步）尚未实现**，仍由用户点 ③ 触发

## 7 上线前自测清单

- [ ] `POST /api/m/books` 单本 → `{ok:true,added:1}`，网页书架立刻出现（SSE 广播）
- [ ] 同 `bookUrl` 再推 → `updated:1`，不重复加书
- [ ] 带 `coverData` 推 → 该书 `coverUrl` 变 `…/api/covers/<16位>.png`，`GET` 该地址返回 200 且 `Content-Type` 为图片
- [ ] `POST /api/m/progress/batch` 带 `name` → `updated` 计数正确；不带 `name` 且书不存在 → `missing` 有值
- [ ] 同 `bookUrl` 但书名为空 → 服务端不建记录
- [ ] 令牌错 → 401 且提示「令牌不对或已被重置」
- [ ] 关掉服务端再点同步 → 提示「连不上端口」，不是一句「同步失败」
- [ ] `POST /api/m/cache` 推一本 → NAS `cache/<书名>/` 出现 `meta.json`、`0001 …txt`、`raw/`，网页能离线读正文
- [ ] 书源首次同步后服务端 `total` 不少于同步前（一次都不许掉源），`POST /api/m/sources/sync` 只落在增量上

## 8 缓存同步：`POST /api/m/cache`（1.10.1 起，1.10.2 改成传 Legado 原文）

```json
{
  "layout": "legado",
  "book": { "bookUrl": "…", "name": "…", "author": "…", "origin": "…", "originName": "…",
            "intro": "…", "durChapterIndex": 3 },
  "coverData": "<base64>", "coverMime": "image/png",
  "chapters": [ { "index": 0, "title": "特别篇－苏醒3", "file": "00002-31b6549c458e124e.nb",
                  "raw": "<.nb 文件原文>" } ],
  "images": [ { "name": "0001-xxx.jpg", "data": "<base64>" } ]
}
```

- `raw` 必须是客户端缓存文件**原文**：不去标签、不补缩进，服务端自己转码
- 单章 `raw` 上限 **32 MB**；整本一次推更推荐，太大就分多次
- 服务端落 `cache/<书名>/`：`meta.json`（`totalChapterNum` / `durChapterIndex` / 章节表）+ `0001 章节名.txt`（转码后正文）+ `raw/` 原文件 + `images/`；封面落 `covers/`
- 响应 `{ "ok": true, "layout": "legado", "dir": "…", "chapters": 45, "withText": 45, "images": 0, "bytes": 756736, "cover": "…" }`
- 落盘后网页阅读命中该书直接读本地文件，不再回源
- 重复上传同一本会另建 `书名 (2)`（服务端按目录去重），不会覆盖旧目录

## 9 书源双向同步：`GET|POST /api/m/sources/sync`（1.11.0）

```http
GET  /api/m/sources/sync?since=<毫秒>      X-Api-Token: …
POST /api/m/sources/sync                   X-Api-Token: …
{ "since": 1794148000000,
  "updated": [ { "bookSourceUrl": "…", "bookSourceName": "…", "lastUpdateTime": 1794149100000, … } ],
  "deleted": [ { "bookSourceUrl": "https://…", "lastUpdateTime": 1794149110000 } ] }
```

- LWW 判定：`lastUpdateTime` 大的赢；客户端没带时间戳按「刚刚修改」处理
- `GET`：`since=0` 回全量，否则只回 `lastUpdateTime > since` 的源；响应带 `serverTime`，客户端存下来当下次的 `since`
- `POST` 响应 `{ ok, applied, skipped, pull, pullDeleted, serverTime, truncated, total }`
  - `pull`：服务端更进的版本，客户端按 `bookSourceUrl` 覆盖本地
  - `pullDeleted`：服务端删掉的源，客户端同步删本地
  - 单次返回有上限，`truncated: true` 时再请求一次接着拉
- **实测坑（重要）**：把「本机全量 + `since: 0`」推给 `POST /api/m/sources/sync`，服务端会当成整库快照，把它自己那份里**本机没有的源删掉**（本机 835 条 + 服务端 1253 条 → 服务端掉到 995，丢 258 条；靠 `backup/sources-*.json` + `POST /api/m/sources` 才补回）
  - 首次接入改用 §4 的 `POST /api/m/sources`（按 `bookSourceUrl` 合并，幂等安全），只把 `GET …/sync?since=<now>` 返回的 `serverTime` 记成下次的 `since`
  - 之后只在本地确有 `lastUpdateTime > since` 的改动时 POST 增量；本地没改动就只 GET，不再每次重推上千条源

## 10 1.8：origin 必须是真实书源地址（客户端侧修复，服务端 ≥ 1.11.1 才软兜底）

1.7 出过一次真实事故：客户端加书时 `origin` 传空、`originName` 传 `手机端`，服务端靠 `book.origin`
取目录与正文，结果网页端打开提示「没有书源」、目录空、正文读不出。1.8 起按下列规矩发请求：

| 项 | 规定 |
| --- | --- |
| `origin` | **必填**，值 = 该书源的 `BookSource.bookSourceUrl`（如 `http://www.qiushu.info`）。禁止空串、禁止 `手机端` / `本地` / `未知` 之类占位 |
| `originName` | 该源的 `bookSourceName`（如 `🎉 狗狗书籍`）。不要写 `手机端` |
| `bookUrl` | 必须是书籍详情页。搜索页 / 临时跳转（`/search`、`?q=`、`&q=`、`?wd=`、`?keyword=`、`/so/`、`s?ie=`）不要推 |
| 拿不到 origin | 用 `bookUrl` 的 host 去书源列表匹配 `bookSourceUrl` 的 host，命中就填（多源取同名那条）；再退按源名唯一匹配；都不中才留空 |
| `POST /api/m/cache` | 除 `origin` 外必须带 `layout:"legado"`、`chapters[].file`、`chapters[].raw`（`.nb` 原文，不做 `Html.fromHtml` 之类处理） |
| 书源改/删 | 每次都写 `lastUpdateTime = System.currentTimeMillis()`；删除也带；服务端「时间戳大的赢、相等服务端赢」，`pull` 回本地要覆盖 |

### 自测清单（1.8 已逐条跑过）

- [x] 加书后看 `POST /api/m/books` 的 `origin` 是否等于书源 `bookSourceUrl`（服务端书架 9 本逐本核对，全部一致）
- [x] 网页端 / `GET /api/m/toc` + `/api/m/content` 打开该书：目录非空、能翻正文（6 本 5227/858/12/1674/1375/922 章）
- [x] `POST /api/m/cache` 的 `chapters[].raw` 是 `.nb` 原文（NAS `raw/*.nb` 与本地一致，未转义、未去标签）
- [x] 同步的书网页端有封面（`coverData` 有值，NAS `covers/*.jpg`，`coverUrl` 改指 `/api/covers/`）
- [x] 改一次书源 → 一轮同步 → 网页端出现同样改动；再让服务端改 → 本地被覆盖（`lastUpdateTime` 双向生效）

> 注：`gaysay.com` 这类纯 JS 渲染的站，详情页没有章节链接，书源规则取不到目录，
> 客户端与服务端表现一致（只有 1 章且无正文），属于书源规则问题，不是 origin 问题。

## 11 1.9：每日推荐（`GET /api/daily`）与 `rank` 代抓

服务端每天抓一次榜单落 `daily.json`，6 小时缓存；网页「搜索 → 每日推荐」直接读它。
抓取顺序 **起点 → 纵横 → 17K**，全失败则返回磁盘上一次结果并带 `stale:true`。
起点 `https://www.qidian.com/rank/recom/` 有反爬（202 挑战页），服务端直连基本拿不到，
所以在有在线手机时先下发 `rank` 任务（12 秒等结果），手机抓不到就继续走纵横。

客户端职责（1.9 实现）：

| 项 | 规定 |
| --- | --- |
| 领取 | 照旧 `GET /api/m/remote/pending`，`type=rank` 与 search/toc/content 同一队列 |
| 抓取 | 浏览器 UA + `Accept-Language: zh-CN`，8 秒连接 / 8 秒读取，跟随跳转 |
| 判定反爬 | 响应正文 < 10 000 字节直接判失败（挑战页只有几 KB，解析只会得到 0 本） |
| 解析 | 起点 `.book-img-text li` / `.rank-list li`（书名 `h2 a`、作者 `.author`、封面首个 `img`）；纵横 `.zh-modules-rank-book`；17K `a[href*="/book/"]` 且路径形如 `/book/<数字>.html` |
| 回传 | `{source,url,books:[{rank,name,url,author,cover,score}]}`，`rank` 按抓取顺序补 1..N，`url` 转绝对地址、按 url 去重 |
| 失败 | `{ok:false,error:"…"}`；服务端拿不到就不再等，改走自己的纵横 / 17K 解析 |

自测清单（1.9 已跑）：

- [x] 模拟器装 1.9 包，前台桥接服务连上 `1.11.2`，`GET /api/m/remote/pending` 能领到任务
- [x] `GET /api/daily` 返回 30 本、`source=纵横`（起点被反爬时由服务端降级，客户端此时回 ok:false）
- [x] 修正：`POST /api/remote/job {"type":"rank"}` 在 1.11.2 / 1.11.3 上均返回 `400 {"error":"不支持的任务类型"}`，`rank` 只能由服务端 `/api/daily` 内部的 `remoteRank` 触发；手机端只负责在长轮询队列里领 `type=rank`
- [x] 其余回传（推书 / 进度 / 封面 / 缓存 / 书源）字段未变，1.8 的 9 本实测结论继续有效

## 12 1.11.3（2026-11-10）：服务端内部修复，接口无变化

服务端 1.11.3 只改 legado-py：补 `applyReplaceRules`（正文净化）、`formatJs` 结果生效、脚本/测试目录名统一、日志写入 `docs`、README 版本自动同步。`docs/APP同步接口.md` 本轮未变（27 458 B / SHA256 `7b46d6a6…`），第 4 / 9 / 11 / 13 / 14 / 15 / 16 / 17 章的字段表与自测清单继续有效，客户端 1.11 未改 FnReader 对接代码（新增能力是 lean 订阅与 GitHub 许愿，与回传协议无关）。

实测补记：`POST /api/remote/job {"type":"rank"}` → `400 {"error":"不支持的任务类型"}`；`rank` 只能由服务端 `/api/daily` 内部 `remoteRank` 下发，外部不能直接下单。

## 13 2.0.2（2026-11-10）：`bookinfo` 简介补全 + 软总线两条队列

`docs/APP同步接口.md` 新增 **§18 简介补全：让手机把书籍详情带回来**，`docs/更新日志.md` 新增 2.0.2 / 2.0.1 / 2.0.0 / 1.11.4。

### 13.1 任务与回传字段（与文档逐一核对，客户端已一致）

| 方向 | 字段 | 说明 |
| --- | --- | --- |
| 下行 payload | `bookUrl` / `source` / `name` / `author` | `source` 可为空，空时客户端按 `bookUrl` 的 host 匹配本机书源 |
| 上行 data | `intro` / `author` / `coverUrl` / `kind` / `wordCount` / `lastChapter` / `totalChapterNum` | **全部可选**，拿不到就省略（回空串会被服务端当成「这书没简介」不再重试） |

`intro` 会先把富文本转纯文本（`<br>` 转换行、标签全去、压缩空行）。

### 13.2 服务端什么时候问手机

| 入口 | 触发条件 |
| --- | --- |
| `GET /api/intro?name=&author=&url=` | 本地书源 + 晋江都没拿到简介，且传了 `url` |
| `GET /api/detail?url=&origin=` | 详情页 `intro` 或 `coverUrl` 为空 |

成功响应带 `"via": "phone"` 并写回书架；**超时 10 秒**，拿不到按原样返回，不阻塞页面。

### 13.3 客户端落实（2.0.2）

- 超时：`bookinfo` 由 23 s 收到 **9 s**（`JOB_TIMEOUT_BOOKINFO`），保证比服务端 10 秒窗口早放手
- 队列：`bookinfo` 走独立 `infoQueue`（单并发）；`search` / `toc` / `content` / `rank` 走 `searchQueue`（2 并发），互不挤占
- 字段：`FnReaderRemote.bookInfo()` 按上表回传，`source` 为空时 `matchSource()` 按 host 兜底，`plainText()` 洗简介

自测清单：

- [x] 两仓 `FnReaderBridgeService.kt` 同构（双队列 + `JOB_TIMEOUT_BOOKINFO=9000L`），SHA256 一致
- [x] 首启更新日志首条为本版（`2026/11/10（2.0.2）`），老段落保留
- [x] 面板 ①–⑤ 与自动同步一致，无 ⑧⑨ 残留按钮
## 14 2.0.6 – 2.6.7（2026-11-14 ~ 11-16）：接口契约未变，回传不用改

这一段时间服务端连发了 2.0.6 / 2.4.3 / 2.4.4 / 2.6.0 – 2.6.7，逐条核对后**没有一条改到 APP 回传协议**：

| 版本 | 服务端改了什么 | 对客户端的影响 |
| --- | --- | --- |
| 2.0.6 | 新增 `ebook-parse` 软总线任务，EPUB / MOBI 整本交手机解析，回传 `fnreader.ebook.v1` JSON 分片 | 客户端 2.0.6 已实现，payload 字段 `sha1` / `format` / `name` / `size` / `fileUrl` / `token` 未变 |
| 2.4.3 | 目录先行：解析完只回目录，正文按章按需补齐 | 客户端 2.4.3 已实现，`tocOnly` / `mode:toc` 仍是分支判定 |
| 2.4.4 | 新增传书：`GET /api/ebook/upload` 取令牌、`POST /api/m/ebook/upload?name=` 传裸 body | 客户端 2.4.4.1 已实现，令牌每次现取 + 401 `upload-token` 重取一次 |
| 2.6.3 | `phoneSearch` 超时 8 s → 20 s，`search` 只传 `source`（书源 URL 字符串） | 客户端本来就用 `payload.optString("source")` 当 URL，一致 |
| 2.6.4 | 删掉 `/api/m/remote/task` 与 `/api/m/remote/pending` 的确认闸门 | 客户端只轮询 `pending` 并回 `result`，闸门在服务端，去掉后到手任务更多，不用改 |
| 2.6.5 | Node / Python 引擎统一桌面 UA（Chrome/157 + Edg/157） | 纯服务端请求头，客户端不参与 |
| 2.6.6 | 「替换·屏蔽」净化规则开始作用于 `/api/m/content` | 返回体 `title` / `paragraphs` 字段与结构不变，只是内容已被净化 |
| 2.6.7 | 新增 `/api/offline`、`GET /api/ebook/export`、`GET /api/update/check` | 三个入口都只在网页端用，APP 无对应界面 |
| 2.6.8 | 监听改 `::` 双栈、`/api/m/info` 新增 `ipv6Lans`（公网 IPv6 展示） | 客户端只取 `info` 的版本号，新增字段忽略；IPv4 连接不受影响 |
| 2.6.9 / 2.6.10 | 网页设置页 IPv4 / IPv6 分行、IPv6 独立复制按钮，`ipv6Lans` 过滤 `::1` 回环 | 纯网页 UI 改动，`server.js` 与 `/api/m/*` 未动，客户端无需改动 |

结论（2026-11-17 核对）：软总线任务类型仍是 `search` / `toc` / `content` / `rank` / `bookinfo`，
超时 `JOB_TIMEOUT_SEARCH/TOC = 18000 ms`、`CONTENT = 23000 ms`、`RANK = 23000 ms`、`BOOKINFO = 9000 ms`；
鉴权仍是 `X-Api-Token`（回传）与 `X-Upload-Token`（传书，现取）；
`/api/m/books`、`/api/m/progress/batch`、`/api/m/cover`、`/api/m/sources`、`/api/m/sources/sync`、
`/api/m/cache`、`/api/m/remote/pending|result` 的路径、参数、回传字段全部未变。客户端在 2.6.8.1 – 2.6.11.1 期间代码一行未改（与 2.6.8.1、2.6.7.1 逐字一致），2.6.12.1 起因调试设备规则才动了两个文件（见 §15）。

自测清单（2.6.8.1 – 2.6.11.1 期间逐条核对）：

- [x] 两仓 `FnReaderRemote.kt` / `FnReaderBridgeService.kt` 代码与 2.4.4.1 完全一致，SHA256 未变
- [x] 面板 ①–⑧ 与本文档 §5 / §8 / §9 / §13 / §14 一致，无 ⑧⑨ 旧自动按钮残留
- [x] 各版首启更新日志首条为本版，老段落保留
- [x] `toc` / `content` 任务字段表与 `/api/m/content` 实际响应（2.6.6 净化后）逐字段比对通过

## 15 2.6.12（2026-11-17）：软总线忽略调试设备回传（本版唯一的客户端契约变更）

服务端 `remote.js` 新增 `isDebugDevice(id, name)`：把 `id` 与 `name` 拼成一个字符串，
命中 `/(bus[-_]?debug|\bdebug\b)/i` 或含「调试」即判为调试设备。围绕这个判定改了三处：

| 位置 | 2.6.12 的行为 | 客户端要做什么 |
| --- | --- | --- |
| `hello()` 登记设备 | 设备记录新增 `debug` 字段 | 无需上报，服务端自己判 |
| `GET /api/m/remote/pending` | 响应新增 `debug: true/false`（当前请求方是否被判为调试设备）；`id` 取 `?id=` 或 `X-Device-Id` | 解析 `debug`，命中时提示用户改设备 ID / 名称 |
| `POST /api/m/remote/result` | 调试设备回传直接丢弃：`200 {ok:true, accepted:false, ignored:true, reason:"调试设备结果已忽略"}`，任务不 resolve、不计 `handled` | 必须把 `ignored:true` 当失败，不能静默当成功 |

任务分流：`create()` 时给 job 打 `debug = payload.debug === true`；`pending()` 按请求方身份过滤——
普通设备只拿非调试任务（真机不再收到调试任务），调试设备只拿 `payload.debug=true` 的调试任务。

### 15.1 客户端落实（2.6.12.1，两仓同构）

- `FnReaderRemote.poll()` 返回值由 `List<Job>` 改为 `Pending(jobs, debug)`：先按对象取 `jobs`，再读 `debug`（缺省 `false`，兼容 2.6.11 及更早服务端）。
- `FnReaderBridgeService` 轮询循环：`pending.debug && !debugWarned` 时通知栏一次性提示「本机被服务端识别为调试设备，回传会被忽略，请改设备 ID / 名称」，避免每秒刷通知。
- `FnReaderRemote.submit()`：响应体若 `ignored == true`，抛 `RemoteException`，消息带上服务端 `reason` 与命中规则说明；三处调用点都在 `runCatching` 内，会被记成明确失败。
- 本机默认设备标识不触碰规则：`deviceId = AppConst.androidId`（十六进制串）、`deviceName = Build.MODEL`，正常手机不会被误判。

### 15.2 自测清单（2.6.12.1）

- [x] 真机 / 模拟器（androidId + 机型名）`pending` 返回 `debug:false`，普通 `search` / `bookinfo` 任务照常领到
- [x] 把设备名改成含「调试」后 `pending` 返回 `debug:true`，通知栏出现一次提示
- [x] 调试设备身份回结果得 `ignored:true`，客户端按失败记录，任务仍留在队列等真机或超时
- [x] `payload.debug=true` 的任务只出现在调试设备的 `pending` 里，普通设备列表为空
- [x] 两仓 `FnReaderRemote.kt` / `FnReaderBridgeService.kt` 改动逐条对齐，A4 自有差异（`openWebShelf` 拼 `/app/legado-web`、`rawServer` 判 `/app/`、`serverBase` 的 path 分支）未动

## 16 2.6.13（2026-11-17）：调试设备识别扩展（busmon 等）

服务端 `b85fb31..737cd6e`（`release(v2.6.13)`）把 §15 的判定放宽，同时修掉调试工具污染真实数据的问题：

```js
// 2.6.13
return /(bus[-_]?debug|busmon|\bmonitor\b|\bmock\b|\bfake\b|\be2e\b|\bdebug\b)/i.test(text) ||
  /(调试|监控|测试机)/.test(text);
```

| 维度 | 2.6.12 | 2.6.13 |
| --- | --- | --- |
| 调试设备 ID 关键字 | `bus-debug` / `debug` | 追加 `busmon` / `monitor` / `mock` / `fake` / `e2e` |
| 调试设备名称关键字 | 「调试」 | 追加「监控」「测试机」 |
| 接口形状 | `pending` 带 `debug`，`result` 回 `ignored:true` | **未变** |
| 任务类型 / 超时 / 令牌 | 不变 | **不变** |
| 附带修复 | — | 调试设备回传不再参与真实解析，真实书籍目录不会再被写成 `http://debug.local/...` |

### 16.1 客户端落实（2.6.13.1，两仓同构）

- 契约本身无需改代码：`poll()` 仍读 `debug`，`submit()` 仍把 `ignored:true` 当失败。
- 提示文案：`FnReaderRemote.submit()` 抛出的失败原因由「ID 含 bus-debug/debug，或名称含「调试」」
  改为「ID 含 bus-debug/busmon/monitor/mock/fake/e2e/debug，或名称含「调试」「监控」「测试机」」，用户改名时能直接对照；
  `Pending` 的 KDoc 同步更新。
- 本机默认标识（`AppConst.androidId` + `Build.MODEL`）仍不会命中任何关键字。
- 同版本还做了一处与契约无关的客户端优化：`FnReaderBridgeService` 的协程改成三条真线程池——
  `parseDispatcher`（检索/目录/正文/榜单，Pro 按核数自适应 6 / 4 / 3 / 2 条，A4 固定 2 条）、
  `infoDispatcher`（封面与简介独占，Pro 8 核 2 条、其余 1 条，A4 1 条）、`ebookDispatcher`（整本解析独占 1 条）。
  这只影响本机吞吐，不改任何回传字段与超时。

### 16.2 自测清单（2.6.13.1）

- [x] 源码核对：两仓 `FnReaderRemote.kt` 的规则文案与 `remote.js` 完全对应，其余逻辑与 2.6.12.1 逐字一致
- [x] 回归风险核对：`pending` / `result` 字段、任务类型、超时、令牌均未变，2.6.12.1 的行为可直接沿用
- [x] 线程池核对：两仓 `FnReaderBridgeService.kt` 三条 dispatcher 均在建服务时创建、`onDestroy` 时 `close()`，
      检索/简介/电子书互不占位；A4 的检索线程数固定 2，Pro 按 `availableProcessors()` 自适应
- [ ] 真机把设备名改成含「监控」后 `pending` 返回 `debug:true`（当前无真机在线，待补跑）
- [ ] 名称含「测试机」的设备回结果得 `ignored:true` 且任务留在队列（同上，待补跑）
- [ ] 并发压测：同时派 4 条 search 到同源，观察是否被站点限流（待真机跑，Pro 6 条线程更明显）

## 17 2.6.14 – 2.6.16（2026-11-17）：服务端路由与规则保护，客户端零改动

- 上游提交区间 `737cd6e..b639ec2`，变更文件 5 个：`docs/更新日志.txt`、`legado-web/app/server/src/rules.js`、
  `legado-web/app/server/src/server.js`、`legado-web/app/web/index.html`、`legado-web/manifest`；
  **`remote.js` 未在其中**。
- **2.6.14**：`preferRemote` 开启后，章节正文也优先走软总线（`remoteContent`，拿不到再回退本机 Python / Node）；
  `/api/bus` 响应新增 `usage`（`phoneSearch` / `phoneToc` / `phoneContent` / `localToc` / `localContent`）。
  客户端无需改动，只是会被更频繁地派 `content` 任务。
- **2.6.15**：搜索顺序调整——`preferRemote` 时先问手机、再跑本机；正文提前到「本机无书源」之前尝试手机。
  客户端无需改动：仍按 `pending` 领任务、按 `result` 回结果。
- **2.6.16**：规则引擎递归保护，书源规则自我引用最多 200 层，超过返回 502「书源规则递归过深，已中止解析」。
  客户端无需改动：该 502 产生在服务端解析链路，客户端看到的是任务失败/超时。
- `/api/m/*`（含 `/api/m/remote/pending|result`）路径、字段、令牌、任务类型、超时全部未变。

### 17.1 对客户端的影响评估（2.6.16.1）

- 代码零改动，只升版本号与文档；2.6.13.1 的三条线程池设定与回传逻辑可直接对接 2.6.16 服务端。
- 可观察项：`/api/bus` 的 `usage.phoneContent` 计数上升即说明 2.6.15 的「正文优先手机」已生效；客户端无需展示该字段。

### 17.2 自测清单（2.6.16.1）

- [x] 文档 SHA 核对：`APP同步接口.md` 33 802 B、`更新日志.md` 25 298 B 与上一版一致；`更新日志.txt` 40 728 → 41 867 B
- [x] 变更文件核对：`737cd6e...b639ec2` 仅 5 个文件，`remote.js` 未在其中
- [x] 联调核对：MuMu 实装 2.6.16.1 后连上服务端，显示「已连接 FnReader 2.6.15（保活中）」
- [ ] 真机观察 `usage.phoneContent` 增长（服务端 2.6.15 已上线，待有书可解析时确认）
- [ ] 递归保护 502 的客户端表现（构造自我引用书源，确认客户端按失败记录，待真机跑）

## 18 2.6.17 – 2.6.18（2026-11-17）：软总线加权路由、领取锁定与封面/简介拆分，客户端有改动

- 上游提交区间 `b639ec2..ecf040`，变更文件 10 个：新增 `AGENTS.md`、`docs/软总线路由与权重.md`、
  `legado-web/app/server/src/busweights.js`；修改 `docs/APP同步接口.md`（+6/-1）、`docs/更新日志.txt`（+15）、
  `docs/软总线调试APK.md`（+10）、`legado-web/app/server/src/remote.js`（+149/-34）、
  `legado-web/app/server/src/server.js`（+44/-3）、`index.html`、`manifest`（2.6.18）。
- **2.6.17**：任务分三类——`book`（search/toc/rank）、`content`（content/ebook-parse/ebook-chapter）、
  `media`（cover/intro/bookinfo）；每台设备维护 0–100 隐藏性能分（初始 50，成功 +4、失败 −8、>8s 再 −2），
  分数不对外暴露；`score>=55` 为高分设备（可领全部类，在途上限 3），`<55` 为低分设备（只领 media，上限 2）；
  无高分设备在线时自动放开全部任务。领取即写 `dispatchedTo` 锁定 30 秒；结果幂等，重复回传返回 `duplicate`，
  非锁定设备回传丢弃返回 `stale`；权重计算独立成纯函数模块 `busweights.js`，异常自动降级。
- **2.6.18**：在途数改为按实际锁定任务重算，修复异常路径的 inflight 泄漏（设备被卡死不再领任务）；
  隐藏分与内部统计从 `/api/bus` 设备列表移除，仅服务端内部使用；`/api/bus` 增加 `highScore`/`lowScore` 计数。
- **接口形状**：`pending` 响应仍是 `{jobs, waitMs, debug}`，每个 job 多带 `taskClass` 字段；
  `result` 成功仍回 `{ok:true, accepted:true}`，新增字符串态 `ok:"duplicate"` / `ok:"stale"`，调试设备仍回 `ignored:true`。

### 18.1 客户端落实（2.6.18.1，两仓同构）

- 新增任务类型 `cover` / `intro`，payload 与 bookinfo 相同 `{bookUrl, source, name, author}`：
  - 抽出 `fetchMediaBook(payload)`，bookinfo / cover / intro 共用同一次 `WebBook.getBookInfoAwait`；
  - `cover()` 只回 `{coverUrl}`（服务端兼容读 `url`），`intro()` 回 `{intro, author, kind, wordCount, lastChapter, totalChapterNum}`；
  - 拿不到的字段一律省略，不硬塞空串；不支持时服务端自动回退 bookinfo。
- `submit()` 幂等识别：`ok` 为 `duplicate` / `stale` 时直接正常返回（不抛错、不重试），`ignored` 仍按调试设备失败抛出。
- 队列路由：`FnReaderBridgeService` 把 `bookinfo` / `cover` / `intro` 统一送 info 队列，检索任务仍走 search 队列，
  ebook 走 ebook 队列；线程池大小与超时（18/18/23/23/9 秒）不变，服务端 `maxInflight` 在派发侧限流、与本机线程池不冲突。
- A4 保持低性能机口径（检索 2、简介 1），Pro 保持按核数自适应。

### 18.2 自测清单（2.6.18.1）

- [x] 文档 SHA 核对：`APP同步接口.md` 33 802 → 34 273 B、`更新日志.txt` 41 867 → 43 194 B、`更新日志.md` 25 298 B 未变
- [x] 变更文件核对：`b639ec2...ecf040` 共 10 个文件，含 `remote.js`/`busweights.js` 真实契约改动
- [x] 代码核对：两仓 `execute()` 覆盖 cover/intro，`fetchMediaBook` 三处共用，无重复函数；`submit()` 三态（duplicate/stale/ignored）齐全
- [x] 队列核对：两仓 cover/intro 均进 info 队列，不与检索抢线程
- [ ] 真机派 `cover` 任务确认只回 coverUrl、派 `intro` 确认回简介元数据（待真机/服务端实派）
- [ ] 重复回传同一 id 得 `ok:"duplicate"`、锁过期后回传得 `ok:"stale"`（待真机构造）
- [ ] 观察 `/api/bus` 的 `highScore`/`lowScore` 随设备成败变化（隐藏分本身不暴露，只看计数）

## 19 2.6.18 后续文档补充（2026-11-17）：/api/bus 在线设备字段说明，客户端零改动

- 上游提交区间 `ecf0408..c3922a3` 共 2 个提交：`6bfdbdc6`（docs: 增加工具调用约定）与
  `c3922a35`（docs: 补充 /api/bus 在线设备字段说明与当前软总线设备快照）。
- 变更文件仅 `AGENTS.md`（+11）与 `docs/软总线调试APK.md`（+29）；三份重点文档 blob 未变：
  `APP同步接口.md` = `6b9b701f…`、`更新日志.md` = `84c99abc…`、`更新日志.txt` = `f1099dcb…`。
- `AGENTS.md` 的 `ops_bash` 等约定只适用于上游执行环境，不是客户端接口或构建要求。

### 19.1 `/api/bus` 字段与客户端影响（2.6.18.2）

`GET /api/bus` 的 `devices[]` 字段如下，均为只读状态：

| 字段 | 含义 |
| --- | --- |
| `id` / `name` / `model` / `version` | 设备 ID、名称、机型、客户端版本 |
| `lastSeen` | 最近心跳时间（毫秒时间戳） |
| `handled` | 累计处理任务数 |
| `enabled` | 是否启用；服务端可用 `POST /api/bus/toggle` 暂停 |
| `debug` / `online` | 是否调试设备、是否在线 |
| `idle` | 距上次活动的空闲秒数 |

顶层另有 `pending`（待领取任务数）、`recent`（最近任务流水）、`usage`（各任务类型计数）。
两仓面板⑦本来就读取 `GET /api/bus`，本次只是字段说明与历史快照补充；`/api/m/*` 路径、字段、鉴权、任务类型和超时均未变，客户端代码零改动。

文档校正：`cover` / `intro` 在两仓 `timeoutOf()` 中均无独立分支，实际使用 `JOB_TIMEOUT_DEFAULT = 20s`；
`bookinfo` 独立分支仍为 9s。本版把 README 与接入文档中的 cover/intro 超时从误写的 9s 改为 20s，不修改代码。

### 19.2 自测清单（2.6.18.2）

- [x] compare 核对：`ecf0408...c3922a3` 共 2 提交、2 文件，不含 `remote.js` / `server.js`
- [x] 三份重点文档 blob SHA 与 2.6.18 基线一致
- [x] 两仓源码核对：cover/intro 均落 `JOB_TIMEOUT_DEFAULT`，文档已按 20s 校正，代码未改
- [x] MuMu 安装本版：Pro / A4 首启均显示 2.6.18.2；Pro 面板可打开并连接 FnReader 2.6.18，菜单正常

## 20 3.0.x 客户端适配补齐（2026-11-22，3.0.2.1）

服务端 `3.0.0 → 3.0.1 → 3.0.2` 引入完整的 6 类回传契约。客户端 `2.6.30.1` 已有手动回传，
本版补齐自动化与遗漏项，接口均沿用 `X-Api-Token` 或 `?token=`：

| 能力 | 接口 | 本版实现 |
| --- | --- | --- |
| 加书/改书 | `POST /api/m/books` | `Book.save()` 后 2 秒防抖，`FnReaderAutoSync.scheduleBookPush()` |
| 单本进度 | `POST /api/m/progress` | `ReadBook.saveRead()` 后 3 秒防抖 |
| 批量进度 | `POST /api/m/progress/batch` | 服务层每轮按 `fnReaderProgressSyncedMs` 水位只推增量 |
| 封面上传 | `POST /api/m/cover` | 新增独立调用，JSON `{bookUrl,mime,data:"data:image/...;base64,..."}`，单张 8 MB；404 自动停用 |
| 书源双向同步 | `POST /api/m/sources/sync` | 本机快照比较，本机删除写入 `deleted`；`lastUpdateTime<=0` 补当前时间戳 |
| 缓存正文 | `POST /api/m/cache` | 沿用已有实现，全下完才传 |

### 20.1 版本号口径

按「服务端版号 + 0.0.0.1」对齐：服务端 `3.0.2` → 客户端 `3.0.2.1`，versionCode
`3*1000000 + 0*10000 + 2*100 + 1 = 3000201`。`AppUpdateGitHub.versionCodeOf()` 同步支持四段 tag，
否则 `v3.0.2.1` 会被误算为 `30002`，低于当前 versionCode，应用内检查更新失效。

### 20.2 自测清单（3.0.2.1）

- [x] 两仓 `compileAppReleaseKotlin` 通过，`GRADLE_EXIT=0`
- [x] 三包 `aapt2 badging`：`versionCode='3000201' versionName='3.0.2.1'`，无 `application-debuggable`
- [x] 三包 `apksigner`：v2 通过，证书 `CN=FnReader YueDu`
- [x] 三包包内 `assets/updateLog.md` 首条为 `**2026/11/22（3.0.2.1）**`
- [ ] 真机加书 / 翻页 / 切后台后观察服务端是否收到 books、progress、cover 请求
- [ ] 真机删除一本书源，确认 `/sources/sync` 的 `deleted` 数组生效、服务端对应源被移除
- [ ] 首次启用后确认进度水位从 0 推进，第二轮不再重复推旧记录

## 21 服务端 3.0.8 版号同步（2026-11-22，3.0.8.1）

服务端 `3.0.3 → 3.0.8` 只有 Web UI、书城补全、TXT 导出、引擎启动等内部实现更新，
`docs/APP同步接口.md` blob 仍为 `f4b8c9f957a1d609eb0a231123fadaa196ec5644`，
`/api/m/*` 路径、字段、鉴权、超时均未变化，客户端无需改代码。

- 版本号按「服务端版号 + 0.0.0.1」：服务端 `3.0.8` → 客户端 `3.0.8.1`，versionCode `3000801`
- 三包 `aapt2 badging` 均为 `versionCode='3000801' versionName='3.0.8.1'`，无 `application-debuggable`
- 三包 `apksigner`：Pro v2 true（minSdk 24 下 v1 false 正常），A4 v1/v2 均 true，证书 `CN=FnReader YueDu`
- 三包包内 `assets/updateLog.md` 首条为 `**2026/11/22（3.0.8.1）**`
- 本版无客户端代码改动，未重复执行真机 6 类回传验证
