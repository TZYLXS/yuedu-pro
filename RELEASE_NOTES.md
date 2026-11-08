## 阅读Pro 1.8（versionCode 10800）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）1.8** 同步发布、同一套版本号。

### 本版主线：修「同步上去的书在网页端没有书源」

1.7 同步的一本书在飞牛网页端打开提示「没有书源」，目录空、正文读不出来。根因在客户端加书这一步：

```json
{ "bookUrl": "http://www.qiushu.info/t/22759/", "name": "末世狩猎人",
  "origin": "", "originName": "手机端" }
```

服务端靠 `book.origin`（书源地址）去取目录和正文，`origin` 为空就等于这本书没有来源，
除了已缓存正文的书，其余都打不开。本版把这条链路按规范做正：

**① 推书 origin 必须是书源真实地址**

- `origin` = 该书源在书源列表里的 `BookSource.bookSourceUrl`（如 `http://www.qiushu.info`）
- `originName` = 该源的 `bookSourceName`（如 `🎉 狗狗书籍`）
- 新增 `SourceIndex`：先把本机书源建成「`bookSourceUrl` → (地址, 源名)」「host → 同宿主书源」两张表，
  推书时按三级匹配补 origin——① 书里的 origin 已是真实源地址 → 用书源表里规范化的地址和源名；
  ② 按 `bookUrl` 的 host 命中同宿主书源（多源时优先同名那条）；③ 退到按源名唯一命中；全不中才留空

**② 不是详情页的不推**

- `/search`、`?q=`、`&q=`、`?wd=`、`?keyword=`、`/so/`、`s?ie=` 这类搜索页 / 临时跳转，推上去服务端也取不到目录，直接跳过
- 结果区新增「非详情页跳过 N 本」「无匹配书源 N 本」，最后一个括号改成「已发送 X 本进度，origin=bookSourceUrl」

**③ ⑧ 缓存载荷按 §16 补齐**

- `layout: "legado"`、`chapters[].file`、`chapters[].raw`（`.nb` 原文，不 `Html.fromHtml`、不补缩进），`chapters[].raw` 直接 `readText(UTF_8)`
- `book.origin` / `originName` 同推书规则补全；即使 origin 补不上，缓存也能离线读正文

**④ 封面现下再转 base64**

- 本机没有封面文件时，按书的 `coverUrl` 现下载一张（8 秒超时、单张 ≤ 3 MB），转 base64 随书推
- `Referer` **先不带**再逐级退：`gdbzkz.la` 的图带任何 Referer 都 403（不带才 200），另一些站反过来只认自己域名，所以按「空 → 图片域名 → 书源地址」顺序试
- 有的站把 JPEG 也 gzip 预压过（`Content-Encoding: gzip` 却是 `image/jpeg`），解一层再判格式，否则永远认不出是图片
- 飞牛书架里封面还是源站外链（不是 `/api/covers/` 本地副本）的书，② 会补推一次，实测 9/9 全部落到 NAS `covers/`
- 服务端落 `covers/` 后该书 `coverUrl` 自动改指向 `/api/covers/<文件名>`，网页书架不再白图

**⑤ ⑨ 书源同步：每次改/删都带 lastUpdateTime**

- 本地 `lastUpdateTime > since` 的源按 `bookSourceUrl` 推给服务端（`sourceJson()` 给没写过时间戳的源补当前毫秒）
- 本机删掉的源靠 `fnReaderSourcesSnapshot` 快照识别，按 §17.2 带 `lastUpdateTime` 推 `deleted`
- 服务端更进的版本经 `pull` 回本地覆盖（实测：本机把一条源改名推上去，服务端立即变；服务端再改名并抬时间戳，本地下一次同步被覆盖）

### 实测（MuMu 模拟器 Android 12 + NAS 上 FnReader 1.11.1 / 1.11.2）

- ② 全部同步：`新增 3 本　更新 9 条进度　补推 1 本　本机重复 1 本　无匹配书源 1 本 / 飞牛书架共 9 本`
- 服务端 `GET /api/m/sync?full=0` 逐本核对：9 本的 `origin` **全部等于书源列表里真实存在的 `bookSourceUrl`**，`originName` 与源名一致
- 目录 / 正文：`/api/m/toc` + `/api/m/content` 实测 5227 / 858 / 12 / 1674 / 1375 / 922 章可读，两本抽章正文 75 段 / 68 段
  - `gaysay.com` 那 3 本只有 1 章且无正文：该站详情页无章节链接（HTML 6 KB，纯 JS 渲染），**书源规则取不到目录**，客户端与服务端行为一致，不是 origin 问题
- ⑧ 缓存：NAS `cache/<书名>/meta.json` 里 `layout: legado`，`chapters[0]` 带 `file` / `raw`，`raw/*.nb` 内容就是 `.nb` 原文（全角空格未被动过）
- 封面：5 本从源站现下成功，NAS `covers/` 出现 `*.jpg`，服务端 `coverUrl` 改指 `/api/covers/`
- ⑨ 双向：本机改名（`lastUpdateTime` = 当前）→ 服务端同名同时间；再让服务端改名并抬时间 → 本地下一轮被覆盖

### 安装

- arm64 设备用 `yuedu_pro_app_1.8-arm64.apk`，32 位老设备用 `yuedu_pro_app_1.8-armv7.apk`
- 覆盖安装保留书架、书源、阅读进度与令牌；首次启动会弹 1.8 更新日志

### 1.7 内容（上一版）

- 适配 FnReader 1.11.0：新增 ⑧ 上传本机缓存（`POST /api/m/cache`，传 `.nb` 原文）与 ⑨ 书源双向同步（首次 `POST /api/m/sources` 合并，之后按 `lastUpdateTime` 增量）
- 修掉「首次把全量推 `/api/m/sources/sync` 导致服务端删源 258 条」的坑（改成合并上传 + 只取 `serverTime`）
- 菜单扩到 ①–⑨；请求超时分级（缓存 60s / 合并 180s / 增量 120s）

