# APP 回传接受规范

> 服务端依据：FnReader `1.9.1`（`docs/APP同步接口.md` 第 4 / 9 / 11 / 13 / 14 章）
> 客户端：阅读Pro `1.6`（`io.legado.pro`）、安卓4.0 版 `1.6`（`io.legado.api19`）
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
| `originName` | — | string | 来源名，APP 推的统一写 `手机端` |
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
- 1.10.0 起新增任务类型 `rank`（让手机代抓起点榜单）：本客户端只执行 `search` / `toc` / `content`，收到 `rank` 直接回 `{ok:false,error:"不支持的任务"}`，服务端会自动降级到纵横 / 17K
- 1.10.0 新增 `GET /api/daily`（每日推荐，6 小时缓存，`stale:true` 表示用的是旧结果），与回传无关，APP 只读

## 6 本项目 1.6 的落实方式

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
