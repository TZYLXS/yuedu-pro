# APP 回传接受规范

> 服务端依据：FnReader `1.11.3`（`docs/APP同步接口.md` 第 4 / 9 / 11 / 13 / 14 / 15 / 16 / 17 章）
> 客户端：阅读Pro `1.10`（`io.legado.pro`）、安卓4.0 版 `1.10`（`io.legado.api19`）
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
{ "ok": true, "coverUrl": "http://192.168.5.114:1122/api/covers/5c1490be35afe31c.png",
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

服务端 1.11.3 只改 legado-py：补 `applyReplaceRules`（正文净化）、`formatJs` 结果生效、脚本/测试目录名统一、日志写入 `docs`、README 版本自动同步。`docs/APP同步接口.md` 本轮未变（27 458 B / SHA256 `7b46d6a6…`），第 4 / 9 / 11 / 13 / 14 / 15 / 16 / 17 章的字段表与自测清单继续有效，客户端 1.10 无代码改动。

实测补记：`POST /api/remote/job {"type":"rank"}` → `400 {"error":"不支持的任务类型"}`；`rank` 只能由服务端 `/api/daily` 内部 `remoteRank` 下发，外部不能直接下单。
