## 阅读Pro 1.7（versionCode 10700）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）1.7** 同步发布、同一套版本号。

### 本版主线：适配 FnReader 1.11.0 —— §16 缓存同步（⑧）与 §17 书源双向同步（⑨）

服务端 `docs/APP同步接口.md` 这轮新增了三章（§15 每日推荐 / §16 缓存同步 / §17 书源双向同步），
本版把后两章接进客户端，菜单从 ①–⑦ 扩到 ①–⑨。

**⑧ 上传本机缓存到飞牛（网页离线读正文）**

- 扫本机 `book_cache/<书名目录>/` 下的 `.nb` 文件，按文档 §16 组载荷整本一次推：`layout / book / coverData+coverMime / chapters[{index,title,file,raw}] / images[]`
- `raw` 传缓存文件**原文**（不去标签、不补缩进），转码交给服务端做
- 章节名从本地章节表取（`bookChapterDao.getChapter(bookUrl, index)`），标题为空就退回文件名
- 章节图片 1 B–2 MB 的转 base64 一起带；单次请求超过 **30 MB** 的本子跳过并在结果里报「过大跳过」
- 本机一道缓存都没有时提示「本机还没有已缓存的章节…先在阅读里下载几章」

**⑨ 书源双向同步（最后一次修改为准）**

- 首次接入：本机上千条源走一次性合并接口 `POST /api/m/sources`（按 `bookSourceUrl` 合并，幂等），
  只把服务端 `serverTime` 记成下次的 `since`
- 之后每次：本地有 `lastUpdateTime > since` 的改动才 POST 增量 `{since, updated, deleted}`，
  服务端更进的版本经 `pull` 回来覆盖本地；本地没改动时只 GET 增量，不再每次重推全库
- 服务端删掉的源（`pullDeleted`）同步删本地；`truncated: true` 时提示「再点一次继续」
- **为什么首次不用 `POST /api/m/sources/sync`**：实测把「本机全量 + `since: 0`」推过去，
  服务端会把它当成整库快照，删掉本机没有的源（1253 → 995，丢 258 条），
  后来靠 `backup/sources-*.json` + `POST /api/m/sources` 才补回。这条已写进 `docs/app回传接受规范.md` §9

**其它**

- `request()` 增加可选读超时：缓存上传 60s、书源合并上传 180s、书源增量 120s，其余接口保持 12s
- 菜单 ①–⑦ 文案与行为不变；新增的两项排在最后
- 文档同步：`docs/app回传接受规范.md` 扩到 9 章（新增 §8 缓存同步、§9 书源双向同步，含上面那个坑），自测清单加两条

### 实测（MuMu 模拟器 Android 12 + NAS 上 FnReader 1.11.0）

- 弹窗状态区：`已连接 FnReader 1.11.0 / 书架 8 本 / 进度 8 条 / 书源 1253 个（启用 170）`
- ⑧：`缓存上传完成：上传 4 本　共 45 章　图片 0 张　落盘 739 KB`；SSH 进 NAS 核对
  `cache/希灵帝国/meta.json` + `0001 特别篇－苏醒3.txt` + `raw/*.nb` 都在，`meta.json` 里 `totalChapterNum: 11`、`durChapterIndex: 3`
- ⑨：首次合并上传 835 条，服务端 `total` 与同步前一致（未掉源）；再点一次走增量，结果为「推送 0 个　拉取 0 个　飞牛书源 1253 个　本地 839 个」，服务端书源数保持 1253
- 首次只做合并上传 + 记 `serverTime`，**不拉服务端全量**（1253 条约 8 MB，要全量导入用 ① 导入书源更稳）
- 模拟器内 `curl` 直连 `http://10.5.4.70:1122/api/m/info` 返回 1.11.0，模拟器 ↔ 服务端通信成立

### 安装

- arm64 设备用 `yuedu_pro_app_1.7-arm64.apk`，32 位老设备用 `yuedu_pro_app_1.7-armv7.apk`
- 覆盖安装保留书架、书源、阅读进度与令牌；首次启动会弹 1.7 更新日志
