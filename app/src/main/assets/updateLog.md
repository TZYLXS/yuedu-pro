# 更新日志

* 关注公众号 **[开源阅读]** 菜单•软件下载 提前享受新版本。
* 关注合作公众号 **[小说拾遗]** 获取好看的小说。

## **必读**

【温馨提醒】 *更新前一定要做好备份，以免数据丢失！*

* 阅读只是一个转码工具，不提供内容，第一次安装app，需要自己手动导入书源，可以从公众号 **[开源阅读]**
  、QQ群、酷安评论里获取由书友制作分享的书源。
* 正文出现缺字漏字、内容缺失、排版错乱等情况，有可能是净化规则或简繁转换出现问题。
* 漫画源看书显示乱码，**阅读与其他软件的源并不通用**，请导入阅读的支持的漫画源！

**2026/11/22（3.0.2.1）**

* 按 `3.0.x更新与客户端适配清单.md` 补齐自动回传：加书/改书 2 秒防抖回传书架，翻页/退出/切后台 3 秒防抖回传进度
* 新增独立封面上传 `POST /api/m/cover`：`bookUrl/mime/data:image/...;base64,...`，单张上限 8 MB；接口 404 时自动停用
* 书源自动同步加入本机删除 `deleted` 队列与快照比较，旧书源 `lastUpdateTime<=0` 自动补当前时间戳
* 自动补推服务端缺失的本地书，按书名归一化去重，同名不同源跳过；服务层每轮按水位批量补进度
* 版本号按服务端 `3.0.2` 对齐为 `3.0.2.1` / versionCode `3000201`

**2026/11/22（2.6.30.1）**

* 修复书架中 `origin` 为空的书（显示为「手机缓存」）打开报「未找到书源」：新增 `BookSourceResolver`，按 `bookUrl` 的 host 匹配已启用书源的主域，命中后回写 `origin/originName` 并落库
* `ReadBook.upWebBook` 改用解析器；`FnReaderAutoSync.mergeShelf` 对空 `origin` 或找不到源的书先解析，仍为空则跳过导入，避免把坏数据写进书架
* 修复书源自带 Referer 被 `bookUrl` 覆盖导致的接口书 `incorrect referer`
* 软总线跟进 2.6.19 / 上游 3.0.0：领任务时上报 `cores` / `mem` / `inflight` / `supports`，服务端据此算能力初值分并按 `book`/`content`/`media` 切额度
* 按服务端下发的 `timeoutMs` 收手：`cover`/`intro` 7 秒、`bookinfo` 9 秒、其余按经验值；老服务端没下发时自动回落
* 版本号对齐上游 3.0.0：`2.6.30.1` / versionCode `2063001`

**2026/11/17（2.6.18.2）**

* 跟进上游 `ecf0408..c3922a3` 两份文档提交：本版**仅跟进文档，无代码改动**
* `docs/软总线调试APK.md` 补充 `/api/bus` 设备字段 `id/name/model/version/lastSeen/handled/enabled/debug/online/idle` 及 `pending/recent/usage`；这些是面板⑦的只读信息，`/api/m/*` 路径、字段、鉴权、任务类型均未变
* 三份重点文档 SHA 未变；`AGENTS.md` 的工具约定只适用于上游环境，不影响本客户端
* 同步校正文档口径：cover/intro 当前落客户端 default 20 秒超时，bookinfo 仍为 9 秒；本版未改超时代码

**2026/11/17（2.6.18.1）**

* 跟进服务端 2.6.17 / 2.6.18「软总线加权路由 + 在途泄漏修复」，本版有真实代码改动
* 软总线新机制：任务分 `book`（search/toc/rank）、`content`（content/ebook）、`media`（cover/intro/bookinfo）三类，服务端按设备隐藏性能分（0–100，不对外暴露）路由；领取即锁定 30 秒，其它设备不重复领；每台设备在途上限高分 3 / 低分 2；2.6.18 按实际锁定任务重算在途数，修复异常路径把设备卡死
* 新增拆分任务 `cover` / `intro`：payload 与 bookinfo 相同 `{bookUrl, source, name, author}`，客户端已实现——`cover` 只回 `coverUrl`，`intro` 回简介与元数据；bookinfo/cover/intro 共用同一次详情抓取，拿不到的字段省略
* 回结果幂等：`submit()` 识别服务端返回的 `duplicate`（重复回传，正常不重试）与 `stale`（锁过期被别的设备领走，不重试）；`ignored` 仍按调试设备失败处理
* 客户端线程池与超时不变（18 / 18 / 23 / 23 / 9 秒），cover/intro 走简介队列，不与检索抢线程

**2026/11/17（2.6.16.1）**

* 跟进服务端 2.6.14 / 2.6.15 / 2.6.16 三版：本版**仅跟进文档，无代码改动**
* 2.6.14：开启「优先手机解析」后，章节正文也优先走软总线（手机在线时先让手机解析，失败再回退本机）；`/api/bus` 新增
  `usage` 统计（phoneSearch / phoneToc / phoneContent / localToc / localContent），能看到手机解析跑了多少
* 2.6.15：修掉「开了手机代理仍优先本地」——搜索先问手机，再跑本机 Python / Node；正文也提前到「本机无书源」之前尝试手机
* 2.6.16：规则引擎加递归保护，书源规则自我引用最多 200 层，超过返回 502「书源规则递归过深，已中止解析」，不再 500 / 爆栈
* 客户端契约零变化：`docs/APP同步接口.md`（33 802 B）与 `docs/更新日志.md`（25 298 B）本轮零改动，`remote.js` 未改，
  `/api/m/*` 的路径、字段、令牌、任务类型、超时（18 / 18 / 23 / 23 / 9 秒）全部照旧，2.6.13.1 的客户端逻辑可直接对接 2.6.16 服务端
* 本机实测：MuMu 装本包后「我的 → 接入 FnReader 服务器」连上 `http://IP:1122`，显示「已连接 FnReader 2.6.15（保活中）」，菜单与同步项正常

**2026/11/17（2.6.13.1）**

* 跟进服务端 2.6.13「扩展调试设备识别（busmon 等）」：调试设备判定由原来的「ID 含 bus-debug/debug 或名称含调试」放宽为
  ID 含 `bus-debug` / `busmon` / `monitor` / `mock` / `fake` / `e2e` / `debug`，或名称含「调试」「监控」「测试机」
* 客户端可执行改动只有提示文案：回传被忽略（`ignored:true`）时抛出的失败原因按新规则列出全部关键字，用户一眼能看出自己踩了哪条
* 服务端同时修掉调试工具把真实书籍目录写成 `http://debug.local/...` 的问题（调试设备不再参与真实解析），本机不受影响
* 接口形状未变：`pending` 仍是 `{jobs, waitMs, debug}`，`result` 仍回 `{ok:true, accepted:false, ignored:true, reason}`；
  任务类型、超时（18 / 18 / 23 / 23 / 9 秒）、`X-Api-Token` 全部照旧，本次不动代码逻辑
* 手机解析改成**多线程**：Pro 面向 8 核 / 2 大核（A72 及以上）的高性能机，按 CPU 核数自适应开解析线程——
  8 核及以上 6 条、6 核 4 条、4 核 3 条、再低 2 条；搜索 / 目录 / 正文 / 榜单真正并行跑，一个慢源不再堵住后面的任务
* **封面与简介（`bookinfo`）单独一条线程池**：8 核机 2 条、其余 1 条，固定不排在检索任务后面，补简介和补封面不再被解析队列拖住
* 电子书整本解析独占 1 条线程，避免吃 CPU 的 ebook 任务抢走检索与简介的线程
* 超时、回传格式、令牌、错误码全部不变；解析失败仍按原样回 `ok:false` + 原因，服务端行为不受影响

**2026/11/17（2.6.12.1）**

* 跟进服务端 2.6.12「软总线忽略调试设备回传」：调试设备（ID 含 `bus-debug` / `debug`，或名称含「调试」）的回传一律丢弃，任务留给真机或超时
* 领任务 `GET /api/m/remote/pending` 响应新增 `debug` 字段：客户端现在解析它，命中调试规则时通知栏提示「本机被服务端识别为调试设备，回传会被忽略，请改设备 ID / 名称」
* 回结果 `POST /api/m/remote/result` 新增 `{ok:true, accepted:false, ignored:true, reason}` 回执：客户端识别为明确失败并抛出原因，不再静默当成功
* 调试任务只投递给调试设备（`payload.debug=true`），真机不再收到；本机默认设备 ID 取 androidId、名称为机型，正常不会被判成调试设备
* 其余契约未变：任务类型 `search` / `toc` / `content` / `rank` / `bookinfo`、超时 18 / 18 / 23 / 23 / 9 秒、`X-Api-Token` 与传书 `X-Upload-Token` 照旧

**2026/11/17（2.6.11.1）**

* 本版仅跟进文档，无代码改动：服务端 2.6.11 把应用信息里的开发者 / 维护者 / 发布者统一改为 `TZYLXS`
* `docs/fnos-fpk-build.md` 的 `maintainer` / `maintainer_url` / `distributor` 示例同步换成 `TZYLXS`；`legado-web/manifest`、`web/index.html` 只动了这几行元信息
* `docs/APP同步接口.md` 仍是 33802 B / `e04f3466…` 零改动，`docs/更新日志.md` 也未变；只有 `docs/更新日志.txt` 新增 2.6.11 一段
* 新增 `docs/软总线调试APK.md`（207 行，配 `tools/bus-debug-app` 调试 APK），是给调试机单独装的小工具，与两端客户端契约无关
* 契约复核：任务类型 `search` / `toc` / `content` / `rank` / `bookinfo`、超时 18 / 18 / 23 / 23 / 9 秒、`X-Api-Token` 与传书 `X-Upload-Token`（现取）全部未变

**2026/11/17（2.6.10.1）**

* 本版仅跟进文档，无代码改动：服务端 2.6.9 把设置页「其它地址」的 IPv4 / IPv6 分行显示，2.6.10 再把 IPv6 拆成独立一行 + 独立复制按钮（IPv4 的复制只复制 IPv4，并过滤掉 `[::1]` 回环）
* `docs/APP同步接口.md` 仍是 33802 B / `e04f3466…` 零改动，`docs/更新日志.md` 也未变，只有 `docs/更新日志.txt` 新增 2.6.9 / 2.6.10 两段
* 改动面只有 `legado-web/app.js`（+26/−4）、`index.html`、`manifest` 版本串；`server.js` 与全部 `/api/m/*` 一个字没动
* 契约复核：软总线任务类型仍为 `search` / `toc` / `content` / `rank` / `bookinfo`，超时 18 s / 18 s / 23 s / 23 s / 9 s 未变；`X-Api-Token` 与传书令牌现取（`X-Upload-Token`）未变

**2026/11/16（2.6.8.1）**

* 本版仅跟进文档，无代码改动：服务端 v2.6.8 只把监听从 `0.0.0.0` 换成 `::`（IPv4/IPv6 双栈），设置页「其它地址」多列出本机公网 IPv6，方便手机用流量直连
* `docs/APP同步接口.md` 零改动（33802 B / `e04f3466…`）；`docs/更新日志.txt` 新增 2.6.8 一段，另多了一份 `docs/fnos-fpk-build.md`（FPK 打包上架指南，纯文档）
* `/api/m/info` 只是多返回一个 `ipv6Lans` 数组（只含 `2xxx` / `3xxx` 开头的公网 IPv6），App 只取版本号，新增字段忽略即可
* 契约复核：软总线任务类型仍为 `search` / `toc` / `content` / `rank` / `bookinfo`，超时 18 s / 18 s / 23 s / 23 s / 9 s 未变；`X-Api-Token` 与传书令牌现取（`X-Upload-Token`）未变；IPv4 连接照常

**2026/11/16（2.6.7.1）**

* 本版仅跟进文档，无代码改动：服务端 2.6.6 / 2.6.7 两版的 `docs/APP同步接口.md` 一行未动（33802 B / `e04f3466…`），`docs/更新日志.txt` 新增这两段（`.md` 自 2.6.0 起服务端只写 `.txt`）
* 2.6.6：净化规则（替换·屏蔽）现在对客户端接口 `/api/m/content` 同样生效，App 正文与网页一致；回传的 `title` / `paragraphs` 字段名与结构不变，只是内容已被服务端洗过，客户端不用改
* 2.6.7：服务端新增「离线缓存」页、`GET /api/ebook/export`（整本 TXT 导出）、`GET /api/update/check`（启动检查 GitHub 最新 Release）——三个新入口都只在网页端用，App 侧没有对应界面
* 契约复核：软总线任务类型仍为 `search` / `toc` / `content` / `rank` / `bookinfo`，超时 18 s / 18 s / 23 s / 23 s / 9 s 未变；`X-Api-Token` 鉴权与传书令牌现取（`X-Upload-Token`）未变

**2026/11/15（2.4.4.1）**

* 新增「传书到飞牛」：本机电子书（pdf / mobi / azw3 / epub / txt）直接推上去，上传令牌每次现取

**2026/11/15（2.4.3）**

* 新增目录先行：解析电子书时只回传目录，正文按章按需补齐
* 新增按章回传：飞牛请求某章时，手机用本地原文件取出该章回传，配合服务端的按需补章

**2026/11/14（2.0.6）**

* 新增软总线 `ebook-parse` 任务：飞牛扫到 EPUB/MOBI 时把整本文件发给手机，手机解析成 `fnreader.ebook.v1` JSON 分片回传，服务端只按 JSON 渲染；PDF/TXT 仍由服务端本机解析
* 新增 `FnReaderEbook`：EPUB（container/OPF/spine/nav+NCX）与 MOBI6（PalmDOC 压缩 1/2、EXTH、h1~h3 切章）本地解析，段落类型 p / h1~h6 / quote / hr / img，与草稿 `docs/软总线电子书解析设计.md` §5 一致；KF8/AZW3 明确返回 `kf8-unsupported`
* 解析队列独立：电子书任务不占 search/toc/content 的 2 个并发；下载支持 Range 续传，分片按 50 章 / 2MB 切分，单片远低于服务端 8MB 上限
* 失败语义：drm / corrupt / unsupported / kf8-unsupported 原样回传，服务端不再本地重试；网络或超时失败由服务端自动兜底本机解析

**2026/11/14（2.0.5）**

* 本版仅跟进文档，无代码改动：服务端 `docs/更新日志.md` 新增 2.0.7 / 2.0.8 / 2.0.9 三段，`docs/APP同步接口.md` 未变（`68e3f7954681`，29343 B）
* 2.0.7：`<下载目录>/cache` 下同一本书不再生成「书名 (2)(3)…」，重复同步复用原目录；启动时自动合并历史重复目录，新增 `/api/cache/dedupe`
* 2.0.8：去重逻辑改为异步、只由 `/api/cache/dedupe` 触发，避免扫描大量目录卡住服务；重复同步复用原目录
* 2.0.9：按「书名+作者」归并跨书源同名目录，合并改用 rename（同盘瞬时）；已把 NAS 上 1641 个重复目录清到 158 个
* 客户端无需改动：这三段全是服务端缓存落盘与目录去重，`/api/cache/dedupe` 由服务端自己调；`shelf` / `search` / `toc` / `content` / `progress` / `sources/sync` / `cover` / 封面与书源上传的路径、字段、鉴权、超时都没变
* 超时照旧：`JOB_TIMEOUT_SEARCH/TOC = 18000L`、`JOB_TIMEOUT_CONTENT = 23000L`、`JOB_TIMEOUT_BOOKINFO = 9000L`；软总线任务类型仍为 search / toc / content / rank / bookinfo

**2026/11/14（2.0.4）**

* 本版仅跟进文档，无代码改动：服务端 `docs/更新日志.md` 新增 2.0.5 / 2.0.6 两段，`docs/APP同步接口.md` 未变（`68e3f7954681`，29343 B）
* 2.0.5：加入书架后立即抓完整目录（强制 `refresh`、含分页），服务端后台补全并广播，按钮显示章数
* 2.0.6：加入书架立即抓完整目录，有手机在线优先走软总线（能过反爬），无设备或失败自动回落本机解析；按钮与提示显示章数和来源
* 客户端无需改动：`toc` 任务本来就是每次现抓整本目录（`WebBook.getChapterListAwait`，按 `nextTocUrl` / 页面「下一页」翻页，上限 40 页），没有缓存、天然等同服务端说的 `refresh`，收到任务即回 `chapters` 全量
* 超时全部照旧：`JOB_TIMEOUT_TOC = 18000L`（服务端等 20 s）、`JOB_TIMEOUT_CONTENT = 23000L`、`JOB_TIMEOUT_BOOKINFO = 9000L`

**2026/11/10（2.0.3）**

* 本版仅跟进文档，无代码改动：服务端 `docs/更新日志.md` 新增 2.0.3 / 2.0.4 两段，`docs/APP同步接口.md` 未变，客户端 `toc` / `search` / `content` / `bookinfo` 任务契约全部保持原样
* 2.0.3：目录引擎分页上限 5 → 40 页，带循环检测；书源没写 `nextTocUrl` 时按页面「下一页」链接兜底，没有新章节即停
* 2.0.4：Python 解析引擎的目录同样支持分页，桥接层按 `nextTocUrl` / 页面「下一页」循环抓取（上限 40 页、去重、无新增即停）
* 实测（服务端 2.0.4，`GET /api/toc?url=…&refresh=1`）：单书源《群星之子逃婚录》此前只回 50 章，现在一次回完全部目录，末条为「第3139章 白骨王座（三更）」，按标题去重后 1610 条
* 服务端等手机仍是目录 20 秒 / 正文 25 秒，客户端侧 `JOB_TIMEOUT_TOC = 18000L` 不用改：分页由服务端自己做，手机只管交目录

**2026/11/10（2.0.2）**

* 软总线拆队列：`bookinfo`（简介/封面补全）走独立队列、单并发，不再和 search / toc / content 抢并发；搜索被慢源拖住时，简介与封面照样准时回传
* 检索队列（search / toc / content / rank）固定 2 并发，慢源只堵自己那一路，不会把整条待命堵死
* `bookinfo` 超时从 23 秒收紧到 9 秒，对齐 `docs/APP同步接口.md` 18.2 的「超时 10 秒」：手机先放手，服务端按时按原样返回，不再白等一个啃不动的详情页
* 跟进 `docs/APP同步接口.md` 新增第 18 章「简介补全：让手机把书籍详情带回来」：任务 payload（bookUrl / source / name / author）与回传字段（intro、author、coverUrl、kind、wordCount、lastChapter、totalChapterNum）逐项核对一致；`source` 为空时按 bookUrl 的 host 匹配本机书源，服务端 `via: phone` 回写书架
* 紧跟服务端 `docs/更新日志.md` 2.0.2：本客户端不改服务端行为，只对齐上述总线任务；上一版 2.0.1 的书架去重 / 删除墓碑 / `bookinfo` 字段照旧

**2026/11/10（2.0.1）**

* 适配 FnReader 服务端 2.0.1 新增软总线任务类型 `bookinfo`（详情/简介补全）：回传 intro、author、coverUrl、kind、wordCount、lastChapter、totalChapterNum，拿不到的字段一律省略不填；任务里 source 为空时用 bookUrl 的 host 匹配本机书源
* 「lean LEDE 更新日志」「给 lean 许愿（GitHub）」两条入口从「我的 → 设置」页移到「订阅」页右上角菜单，设置页恢复 1.10 样式
* ⑧ 上传本机缓存、⑨ 书源双向同步改为自动执行：服务端可连通即自动开启，每 5 分钟一轮，设置面板里这两个按钮已移除
* 缓存上传增加完成度判断：整本已下完才上传，未下完的计入「待命」；通知栏汇总「上传 N 本 / 待命 M 本未下完」
* 启动页换成「飞牛阅读」竖版毛笔字
* 书架上同名（不同书源）的书只留一本：优先保留本地已有 `.nb` 缓存的那本，其余副本的阅读进度先并过去再删掉；推送与自动同步都按这个规则取书
* 用户手动删掉的书不会被自动同步补回来：同步时记住上一轮服务端推过的书，本地已删的记成墓碑，之后服务端再推同一本一律跳过（通知栏显示「跳过已删 N 本」）

**2026/11/10（1.11）**

* 新增 **lean LEDE 更新日志订阅**：`coolsnowwolf/lede` 没有独立的 CHANGELOG（`CHANGELOG.md` / `CHANGELOG` / `CHANGELOG.txt` / `docs/README.md` 全部 404），它的「更新日志」就是 master 分支的提交历史
  - 内置订阅 `https://github.com/coolsnowwolf/lede/commits/master.atom`（GitHub 官方 Atom 源，分组「固件」、`customOrder=6`），在「订阅」里直接看最近改动；「我的」页也加了同名入口，点一下直接打开
  - 内置 RSS 解析器补上 **Atom（`<entry>`）** 支持：`<entry>` 与 RSS 的 `<item>` 同等进条目态；`<link>` 认 `href` 属性（取 `rel="alternate"`）、`<summary>`/`<content>` 映射摘要与正文、`<updated>`/`<published>` 的 ISO 8601 时间转本地 `yyyy-MM-dd HH:mm`、标题压成一行 —— 此前这种 Atom 源解析出 0 条，订阅加进去是空白
  - 内置源用「只补缺失」的写入方式，不碰用户自己增删的订阅（不会像默认源那样每次升级把删掉的源塞回来）
* 新增 **给 lean 许愿（GitHub）**：`我的` → `给 lean 许愿（GitHub）`，一个输入框 + 提交按钮，登录后一键把许愿做成 PR 提到 `coolsnowwolf/lede`
  - PR 流程：`GET /user` 认人 → 没 fork 先 `POST /repos/coolsnowwolf/lede/forks` 并等复制完成 → 在 fork 的 master 上建 `wish-<时间>` 分支 → 写 `wishlist/<时间>-<账号>.md` → `POST /repos/coolsnowwolf/lede/pulls`（`head=<账号>:<分支>`，`base=master`）
  - 兜底 **改发 Issue**：不想等 fork 就直接 `POST /repos/coolsnowwolf/lede/issues`
  - Token 只存本机 SharedPreferences，不进备份、不写日志；点「退出登录」立刻清掉
  - 权限要求：classic token 勾 `public_repo`；细粒度 token 需 `Contents` 读写 + `Pull requests` 读写，还要 `Repository creation` 才能自动 fork（缺权限时界面直接回显 GitHub 原文与建议，不吞错）
* 「我的」页新增两条入口：`lean LEDE 更新日志`、`给 lean 许愿（GitHub）`
* FnReader 侧：服务端 `1.11.3` 的 `docs/APP同步接口.md` 本轮无新增字段/接口，软总线任务类型与超时未变，本版未改 FnReader 对接代码（1.10 已跟进的文档改动照旧）
* 版本号 1.11（versionCode 11100），与阅读Pro / 安卓 4.0 同步

**2026/11/10（1.10）**

* 跟进 FnReader `docs/更新日志.md` 新增的 **1.11.3（2026-11-10）**：服务端修 legado-py 缺陷清单——补 `applyReplaceRules`（正文净化）、`formatJs` 结果生效、脚本/测试目录名统一、日志写入 `docs`、README 版本自动同步
  - 全部是服务端 Python 侧改动；`docs/APP同步接口.md` 本轮**未变**（27458 B / SHA256 `7b46d6a625031f31…`），接口路径、回传字段、鉴权方式、软总线任务类型与超时都没有变化
  - 因此本版**仅跟进文档，无代码改动**；服务端实测 `GET /api/m/info` → `version:"1.11.3"`，端点仍是旧十条（`/api/m/shelf` … `/api/m/legado/toc`）
* 正文净化 `applyReplaceRules` 与 `formatJs` 都在服务端执行，本机 `/api/m/content` 拿到的已是净化后的正文，客户端不再叠一层（避免二次净化把分段拆乱）
* 版本号 1.10（versionCode 11000），与安卓 4.0 版同步

**2026/11/09（1.9）**

* 跟进 FnReader `docs/APP同步接口.md` 新章节（服务端已到 `1.11.2`）：
  - **§15 每日推荐（`GET /api/daily`）**：榜单按「起点 → 纵横 → 17K」依次取，6 小时缓存；起点有反爬（返回 202 挑战页）时，服务端会先让软总线上的在线手机代抓（任务类型 `rank`）
  - §16 缓存同步、§17 书源双向同步：1.8 已按此实现（`layout:"legado"` + `chapters[].raw` 原文、`lastUpdateTime` LWW），本版复核协议无差异，未改代码
* 软总线新增 **`rank` 任务**：收到 `payload={url}` 就用浏览器 UA 抓榜单页，按起点推荐榜（`.book-img-text li` / `.rank-list li`）、纵横（`.zh-modules-rank-book`）、17K（`/book/<id>.html`）三套结构解析，回传 `{source,url,books:[{rank,name,url,author,cover,score}]}` 给服务端当「每日推荐」数据
  - 反爬页（正文不足 10 KB）或解析出 0 本都回 `ok:false` 并给出原因，服务端自动降级到纵横 / 17K，不影响网页端出榜单
  - 起点榜单任务超时按正文同档 23 秒；`rank` 只抓页面不碰本机书源，失败不会拖住别的任务
* 菜单 ⑨「书源双向同步」文案补一句「服务端更进的版本会覆盖本地（`pull`/`pullDeleted`）」，与 §17.4 的建议流程对齐
* 其余回传字段、接口路径、鉴权方式（`X-Api-Token` 一律走请求头）未变

**2026/11/08（1.8）**

* 修复 1.7 同步给飞牛的书在网页端打不开（提示「没有书源」、目录空、正文读不出）：根因是加书时 `origin` 传空、`originName` 传占位「手机端」，服务端靠 `book.origin` 取目录和正文
  - 推书 `origin` 一律填书源真实地址（`BookSource.bookSourceUrl`，如 `http://www.qiushu.info`），`originName` 填该源名（如 `🎉 狗狗书籍`）
  - 确实拿不到 origin 时，先用 `bookUrl` 的 host 去书源列表匹配同宿主书源（多源取同名那条），再退按源名唯一匹配；都匹配不到才留空，并在结果里报「无匹配书源 N 本」
  - 搜索页 / 临时跳转（`/search`、`?q=`、`&q=`、`?wd=`、`?keyword=`、`/so/` …）不算书籍详情页，直接跳过并报「非详情页跳过 N 本」
* ⑧ 缓存载荷按 §16 补齐：`layout:"legado"` + `chapters[].file` + `chapters[].raw`（`.nb` 原文，不做任何处理）+ `origin`/`originName`；即使 origin 补不上，也能靠章节原文离线读正文
* 封面：本机没有封面文件、或飞牛那边的封面还是源站外链（不是 `/api/covers/` 本地副本）时，② 会现下封面转 base64 补推一次（先不带 Referer 试，再退图片域名 / 书源地址；服务端把图片也 gzip 压过的自动解一层），网页书架与 `cache/<书名>/` 都有图
* ⑨ 每次改/删书源都带 `lastUpdateTime = 现在`：本地改过的按 `bookSourceUrl` 推给飞牛，本机删掉的推 `deleted`（靠书源地址快照识别）；服务端更进的版本 `pull` 回本地覆盖
* 菜单 ② 文案写明 origin 规则；⑧ 文案补 `layout:legado + chapters[].file/raw + origin`；⑨ 文案补「最后一次修改为准 / 删除也同步」
* 版本号 1.8（versionCode 10800），与安卓 4.0 版同步
* 真机验证（MuMu 模拟器 + FnReader 1.11.1 / 1.11.2）：书架 9 本 origin 全部等于书源 `bookSourceUrl`、originName 与源名一致；6 本目录与正文可读（5227 / 858 / 12 / 1674 / 1375 / 922 章）；⑧ 落盘 `cache/<书名>/meta.json`（`layout: legado`、`chapters[].raw` 原文、`raw/*.nb`）；5 本封面落 NAS `covers/` 并改指向 `/api/covers/`；⑨ 双向：本机改名推送后服务端即变，服务端改名后本地被覆盖

**2026/11/08（1.7）**

* 适配 FnReader 1.11.0 新规范（服务端 `APP同步接口.md` 新增 §16 缓存同步、§17 书源双向同步）
* 新增 ⑧ 上传本机缓存到飞牛（网页离线读正文）：把 `book_cache` 里已下载章节的 `.nb` 原文（不去标签、不补缩进）连同章节图片、封面一起 POST `/api/m/cache`
  - 服务端落 `cache/<书名>/`：`meta.json` + 转码后的 `0001 章节名.txt` + `raw/` 原文件 + `images/`，之后网页阅读直接读本地文件、不再回源
  - 整本一次上传，单次请求超过 30 MB 的本子自动跳过；本机没有已缓存章节时提示「先在阅读里下载几章」
* 新增 ⑨ 书源双向同步（最后一次修改为准）：按 `lastUpdateTime` 双向对齐，服务端更进的版本拉回本地覆盖，服务端删掉的同步删本地
  - **首次接入走一次性合并上传**（`POST /api/m/sources`，按 `bookSourceUrl` 合并），只把服务端 `serverTime` 记成下次的 since；不把「本机全量 + since=0」推给 `/api/m/sources/sync`（服务端会当成整库快照，把本机没有的源删掉）
  - 之后每次：本地有改动才 POST 增量（服务端更进的进 `pull` 回来），本地没改动就只 GET 增量；不再每次重推上千条源
* 菜单扩到 ①–⑨：① 导入书源、② 全部同步到飞牛书架、③ 打开飞牛阅读网页、④ 接入手册、⑤ 手机解析待命、⑥ 接入书源桥、⑦ 查看软总线、⑧ 上传本机缓存到飞牛、⑨ 书源双向同步
* 网络请求超时分级：缓存上传 60 秒、书源合并上传 180 秒、书源增量 120 秒，其余接口保持 12 秒
* 已完成真机验证（MuMu 模拟器 + FnReader 1.11.0 服务端）：⑧ 上传 4 本 45 章落盘到 NAS `cache/<书名>/`，⑨ 首次合并上传与服务端书源数一致
* 版本号 1.7（versionCode 10700），与安卓 4.0 版同步

**2026/11/08（1.6）**

* 新增 docs《app回传接受规范》：把 App 回传飞牛的 5 条通道（推书 / 回传进度 / 单传封面 / 推书源 / 回传解析结果）的字段、上限、合并规则与错误码写成一份接收侧规范，供两端与将来对接方对照
* 回传字段按服务端 1.9.1 规范收敛：
  - 推书补上 `totalChapterNum`，`title` 只在有章节名时发送，不再发空串
  - 批量进度不再带 `coverUrl`（规范里没有这个字段），每项保留 `bookUrl / name / author / index / pos / title`
  - 处理 `POST /api/m/progress/batch` 响应里的 `missing`：飞牛没收下的书自动用 `/api/m/books` 补推一次，结果里显示「补推 N 本」
* 设置菜单精简：删掉「② 复制同步接口地址」「④ WebDAV」「⑤ HTTP API」三项，其余顺延为 ① 导入书源、② 全部同步到飞牛书架、③ 打开飞牛阅读网页、④ 接入手册、⑤ 手机解析待命、⑥ 接入书源桥、⑦ 查看软总线
* 版本号 1.6（versionCode 10600），与安卓 4.0 版同步

**2026/11/08（1.5）**

* 同步安全更新（对齐 FnReader 1.7.31–1.9.1 文档「外网访问 → 安全要点」）：App 自己的所有请求（`/api/m/info`、`/sync`、`/progress`、`/remote/pending`、`/remote/result`）**令牌只走 `X-Api-Token` 请求头**，URL 里不再带 `token=`
  - URL 会进浏览器历史、反代日志、服务器日志，令牌拼在地址里等于持续明文外泄；只有「② 复制同步接口地址」还生成带令牌的链接，并注明「这个地址里带令牌，别转发」
  - 地址填的是公网 IP / 域名却用 `http` 时会提示「请用 https，否则令牌在网络上是明文」；反代带 `/app/legado-web` 之类前缀时原样保留，不再改写
* 软总线（FnReader 1.8.0 新增）：手机解析待命领任务时带上 `id / name / model / ver`，服务端据此把本机登记成解析设备，网页「设置 → 软总线」能看到本机在线状态并单独暂停
  - 新增第 ⑩ 项「查看软总线」：列出各设备的在线 / 暂停状态、累计代理解析次数
* 任务超时对齐服务端 1.8.x 口径（原来统一 45 秒）：搜索 18 秒、目录 18 秒、正文 23 秒，超时提示带秒数；飞牛那边目录等 20 秒 / 正文等 25 秒，本机先一步放弃并回报失败，不再干等到服务端超时
* 错误分因提示：401「令牌不对或已被重置」、403 / 404（地址里多了路径）、超时（端口被挡或服务卡住）、找不到主机（地址或 DNS）、连不上端口（服务没起来 / 防火墙）、证书不受信任（自签 HTTPS）；心跳失败的通知也直接写原因
* 接口清单更新：补上 `POST /books`（推书给飞牛）、`/progress/batch` 带 `name` 会自建书、`GET /api/bus`，并注明「令牌只放请求头、公网务必 https」
* 「③ 全部同步到飞牛书架」按 1.9.x 文档重写：本机书架上的书**全部推给飞牛**（不再「飞牛没有就跳过」），飞牛已有同名的**自动跳过不重复添加**，本机同书多源只留一本；推书按文档 14.5 分批（每批 15 本）
  - **封面随书回传**：在线封面给 `coverUrl`，本机缓存的封面图转 base64 走 `coverData` + `coverMime`（文档 13 / 14.2），飞牛落盘到 `covers/` 并从 `/api/covers/…` 直出
  - 飞牛那边已有这本书但**缺封面**的，会顺手把本机封面补推一次（按 `bookUrl` 合并，只更新所传字段）
* 版本号 1.5（versionCode 10500），与安卓 4.0 版同步

**2026/11/08（1.4）**

* 新增「接入书源桥」（FnReader 服务器弹窗第 ⑨ 项）：导入服务端生成的单品书源「飞牛书库」，搜索 / 详情 / 目录 / 正文全部走 `/api/m/legado/search|book|toc` 与 `/api/m/content`，本机不解析、不装几百条书源规则也能看书
* 连接保活：手机解析待命连上后每 60 秒心跳一次 `/api/m/info`，通知栏显示「已连接 FnReader 1.7.30　待命中」，掉线自动重试；令牌被重置或地址变了会立刻显示连不上
* 开机自启：开关开着时重启手机后自动拉起前台服务，不用再进设置页手动点
* 修复「① 导入书源」：改为标准的 `legado://import/bookSource?src=…` 导入链接，不再因为地址缺少 src 参数直接退出
**2026/11/08（1.3）**

* 新增「手机解析待命」（FnReader 服务器弹窗第 ⑧ 项）：飞牛跑不了的书源（要 WebView / 加密接口）派给手机，用本机书源规则跑 search / toc / content 再回传结果
* 开启后前台服务常驻长轮询 `/api/m/remote/pending`，通知栏显示「FnReader 手机解析」，可一键关闭
* 单个任务 45 秒上限 + 每任务独立协程：一个书源卡住只失败这一个任务，不会堵死整条待命
* 书源地址归一化兜底（去协议、尾斜杠、大小写），飞牛与本机存的地址写法不一致也能对上
* 进程被杀后重进「接入 FnReader 服务器」，开关还开着就自动把服务拉起

**2026/11/08（1.2.1）**

* 修复「接入 FnReader 服务器」弹窗里点「同步」看着没反应：点下去先显示「同步中…」，成功弹结果提示，失败直接说原因
* 同步现在真正落库：服务器书架上没有的书会补进本地书架，本地进度比服务器旧才覆盖，状态区显示「新增 N 本 更新 M 条进度」
* 地址改为逐个探测：填的地址 → 同主机 1122 → 同主机 + `/app/legado-web`，连上后自动把可用地址回填到输入框
* 服务器回 `invalid token` 之类的纯文本时翻成可操作的中文提示，不再原样丢出来
* 版本号 1.2.1（versionCode 10201）

**2026/11/08**

* 版本号统一改为 **1.2**（versionCode 10200），与安卓 4.0 版（API19）仓库保持一致；以后每次迭代 +0.1，满 10 进 1
* 我的 → 书源管理下面一行：新增「FnReader 服务器」，按 FnReader「App 同步接口」重新设计
* 弹窗顶部「同步」直接调 `GET /api/m/sync`，显示服务器版本、时间、书架数、进度数、书源总数与启用数
* 一集菜单：导入书源（`sources.json?all=1`）、复制同步地址、回传进度（`progress/batch`）、WebDAV、HTTP API、网页书架、接入手册
* 服务器地址只填 `http://IP:端口`；复制成飞牛网页地址（`http://IP:5666/app/legado-web`）会自动纠正到 FnReader 自己的端口（默认 1122）
* 导入书源改为走 `legado://import/bookSource?src=…`，一次把服务器上 1000+ 个书源全导进来
* 应用内更新读本仓库 `TZYLXS/yuedu-pro` 的 Release，按 tag 版本（`v1.2` → 10200）与 versionCode 比较

**2023/11/01**

* 修复一些bug

**2023/08/02**

* 更新cronet: 117.0.5938.60
* 书签导出md格式
* 优化进度同步,减少webDav下载次数

**2023/07/30**

* 更新cronet: 115.0.5790.138

* 更新cronet: 115.0.5790.136
* 更新kotlin 1.9.0, 迁移到ksp
* 后台导出书籍
* 订阅添加跳转url拦截, js, 返回true拦截,js变量url,可以通过js打开url,比如调用阅读搜索java.searchBook(
  bookName),添加书架等java.addBook(bookUrl),简化规则写法,不用webView js注入
* 导出单个书籍的书签
* 其它优化与bug修复
* 其中一些更新由 Horis, Xwite, ag2s20150909 提供

**2023/07/20**

* 更新cronet: 115.0.5790.85
* 阅读菜单添加生效的替换规则
* 修复没有下一页遮挡菜单问题
* 优化epub导出
* 其它一些优化和bug修复
* 其中一些更新由 Horis, Xwite, Discut 提供

**2023/07/09**

* 更新cronet: 114.0.5735.196
* 修复朗读可能崩溃的bug
* add Vietnamese language
* 修复部分图片无法访问的bug
* 修复阿里云tts
* 其它一些优化和bug修复
* 其中一些更新由 Xwite, Horis 提供

**2023/06/26**

* 书架更新时显示剩余更新数量,在书架布局里配置
* 添加退出按钮
* 发现添加全部加入书架功能
* 其它一些优化和bug修复
* 其中一些更新由 Xwite, Horis 提供

**2023/06/19**

* 更新cronet: 114.0.5735.130
* 修复书源拖动排序乱跳的问题
* 上传导出支持压缩,导入自动解压

**2023/06/15**

* 修复闪退bug

**2023/06/14**

* 更新cronet: 114.0.5735.60
* 修复长按菜单全文搜索结果不全或无结果问题
* 优化全文搜索速度
* 修复正文页数太多时并行获取问题
* 禁用js调用一些类防止一些恶意书源
* 其它一些优化
* 其中一些更新由 Xwite, Horis 提供

**2023/06/06**

* 更新cronet: 114.0.5735.57
* 书架管理界面点击书名打开书籍
* 修复目录无法加载问题
* epub分割导出功能
* 其它一些优化
* 其中一些更新由 Xwite, Horis, Discut 提供

**2023/05/19**

* 更新cronet: 113.0.5672.131

* 目录格式化js添加一个公用变量gInt, int类型默认值0

> 示例1

```
"第" + index + "章" + title
```

> 示例2

```
gInt++
"第" + gInt + "章" + title
```

* 优化修复bug
* 其中一些更新由 Xwite, Horis 提供

**2023/05/16**

* 添加格式化目录规则,只能写js,不用@js:标志,提供变量index和title,index从1开始
* 正文添加标题规则,结果更新到目录,有些网站只能在正文获取正确的标题
* 优化web端
* 其它一些优化
* 其中一些更新由 Xwite, Horis 提供

**2023/05/13**

* 修复在线tts朗读失败bug

**2023/05/12**

* web: 修复进度同步和快捷键设置；添加间距设置；优化超长章节列表渲染；同步触发：关闭浏览器，切换应用，切换tab，返回
* 替换规则添加排除范围字段
* 迁移到Androidx Media3
* 其它一些优化
* 其中一些更新由 Xwite, Horis, ag2s20150909提供

**2023/05/08**

* 更新cronet: 113.0.5672.76
* web: 全新传书页面 [MakeHui](https://github.com/MakeHui)；修正源编辑列表操作逻辑和多选性能
* 优化部分文本提示toast
* webView: 忽略部分网站的证书错误
* 备份恢复webDav: 输入密码后自动验证
* 换源添加加载字数选项
* 修复全文搜索开始bug
* 其中一些更新由 Xwite, Horis, Jason Yao提供

**2023/05/03**

* 文件类书源文件后缀识别：自动识别失败后会读取下载链接UrlOption的type字段

```
https://www.example.com/file/ajzjxhxjncnc, {type: "txt"}
```

* 更新cronet: 112.0.5615.135

* 源添加jsLib字段

> 注入JavaScript到RhinoJs引擎中，支持两种格式  
> `JavaScript Code` 直接填写JavaScript片段
> `{"example":"https://www.example.com/js/example.js", ...}` 自动复用已经下载的js文件

* 导入书源菜单添加选择选中更新源,选中新增源
* web端迁移到vue3, 部分功能未实现
* 添加本地密码设置,备份涉及到的敏感信息用密码加密后再备份,如webdav密码等
* 修复epub章节异常分割的bug
* 其它一些优化
* 其中一些更新由 Xwite, Horis提供

**2023/04/02**

* 更新cronet: 112.0.5615.47
* 添加文字下划线
* 其它一些优化
* 其中一些更新由 Xwite, Horis提供

**2023/04/01**

* 原app最低支持版本提升到android7.0
* 创建lollipop版本支持android5.0
* 增加内置文件管理,管理私有文件
* 书籍分组增加允许下拉刷新设置

**2023/03/29**

* 修复低于android7.0版本无法恢复备份的问题
* 修复低版本安卓崩溃的问题

**2023/03/28**

* 更新cronet: 111.0.5563.115
* 更新rhino: 1.7.14-2
* 修复bug
* 详情页下拉刷新
* 导出为本地文件时不保留vip标识
* 列表结果为NativeObject时支持{{}}@get@put##规则

```
{bookId: 1}
https://www.example.com/book/{{result.bookId}}
```

* 书架整理搜索支持分类和简介
* 其中一些更新由 Xwite, Horis提供

**2023/03/21**

* 修复书签界面删除条目后界面错乱bug
* 一些带有搜索框的界面自动隐藏软键盘
* 本地书籍和远程书籍导入界面支持压缩包
* 内置了两个直链上传配置,可以通过导入默认切换
* 修复无法导入不在数组内的单个书源的本地文件
* getVariable() 默认返回 ""
* 修复bug
* 其中一些更新由 Xwite, Horis提供

**2023/03/14**

* 更新cronet: 111.0.5563.57
* 优化文件型书源,解决压缩文件编码不是UTF-8报错问题
* js添加非对称加密和签名算法
* 文件类书源支持zip 7z rar4解压
* java.ajax(url: Any)自动判断是列表还是字符串
* 修复一些bug
* 其中一些更新由 Xwite, Horis, ag2s20150909 提供
* 修复js加密算法

**2023/03/12**

* 远程书籍添加webDav多配置
* 更新文件类书源详情页界面逻辑
* 尝试修复语音朗读
* 修复特定情况下书籍导入界面计数错误
* 修复阅读界面导航栏更新不及时
* 修复某些以空白开头的链接的拼接
* 修复某些空白备份恢复的错误弹窗
* 修复章节都在一个xhtml里面的epub的内容解析
* 修复某些epub文件里不规范html文档的解析
* 本地备份也采用压缩包,和webDav备份保持一致,从webDav下载的备份文件不需要解压可以直接恢复
* 本地书籍和远程书籍界面添加筛选功能
* 其中一些更新由 Horis 和 Xwite 提供

**2023/03/03**

* 朗读速度添加数值显示
* 章节进度添加xx/yyy格式
* 远程书籍单独配置webDav,多个webDav看情况再添加

**2023/02/27**

* 优化新建书源和订阅源的默认值和退出时的修改判断
* 优化分组更新时界面刷新
* 一些优化 by Horis

**2023/02/22**

* 添加自定义字典规则
* 一些优化 by Horis

**2023/02/16**

* 更新cronet: 110.0.5481.65
* 添加清除 WebView 数据 by Horis
* 用到通知的时候才提示通知权限获取
* 修改订阅源编辑界面和书源一样
* 一些优化 by Horis 和 Xwite

**2023/02/09**

* 添加通知权限设置提示
* 更新日志添加关闭按钮
* 订阅源添加黑名单和白名单,为列表用,分隔支持正则,如果有黑名单,黑名单匹配返回空白,
  没有黑名单再判断白名单,在白名单中的才通过, 都没有不做处理
* 更新web编辑；web书架章节序号更改后会**同步序号**，更精确的已读字数同步请**返回书架界面** by Xwite

**2023/01/31**

* web书架: 修复进度bug和章节跳转bug，段落内嵌文字图片懒加载 by Xwite

**2023/01/29**

* 优化英文两端对齐排版
* web书架：进度同步精确到已读字数，返回书架时同步app by Xwite
* 一些优化 by Horis

**2023/01/20**

* web书架不限制最大宽度 by Xwite
* 一些优化 by Horis

**2023/01/10**

* 其他一些有分组的地方也支持独立排序
* 优化搜索,优化缓存,尝试解决部分书源占用内存过大导致OOM
* 修复中文AndroidZipFile不能读取非ASCII字符文件名 by ag2s20150909
* 其它一些优化

**2023/01/06**

* 书架分组支持独立排序
* 适配 android 13 主题图标
* 一些优化 by Horis

----

* [2022年日志](https://github.com/gedoor/legado/blob/record2022/app/src/main/assets/updateLog.md)
* [2021年日志](https://github.com/gedoor/legado/blob/record2021/app/src/main/assets/updateLog.md)
