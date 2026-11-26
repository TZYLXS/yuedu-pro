# 阅读Pro 3.3.5.1

同步 FnReader `v3.3.5`（main `7adf7fe`，2026-11-22）。两仓共用版号 `3.3.5.1` / versionCode `3300501`。

## 跟进的协议变更
- `docs/APP同步接口.md` 新增 §17.2.1「同步细节（0.1.50+）」：时间戳相等不回传、`pull` 只回 `lastUpdateTime > since` 且单次最多 500 条、响应带 `nextSince`/`truncated`、`bookSourceType` 统一 0/1/2/3、布尔字段规范化、坏源过滤
- `docs/软总线路由与权重.md`：`score >=55` 与 `<55` 的设备都能领 book/content/media，仅并发与在途额度不同
- `/api/m/*` 无新增客户端必填字段

## 客户端改动
- 书源同步改分页循环：`since` 起拉，`truncated=true` 用 `nextSince` 继续，GET 与 POST（含 `updated`/`deleted`）两条路径都拉到 `truncated=false`，最多 10 轮保护
- `pull` 落库改字段级合并，不再整行 REPLACE：规则与源定义取服务端，`enabled`/`enabledExplore`/`enabledCookieJar`/`customOrder`/`respondTime`/`bookSourceGroup` 保留本地，`lastUpdateTime` 取双方较大值（本地更晚的下一轮重新推上去）
- 本地书回传：导入的 txt/epub/pdf（`origin=loc_book`、`file:`、`content:` 路径）不再被 `runPush` 过滤，`/api/m/books`、`/api/m/progress`、`/api/m/progress/batch` 统一带 `localFile=true` 与 `fileName`，供服务端扫 ebook 目录后按文件名绑定关系；老服务端会忽略这类条目，不影响网文同步

## 服务端同期变化（3.3.0–3.3.5）
- 封面链路重构：客户端封面优先、只存相对路径、按请求 Host 返回绝对地址、死链过滤、批量刷新跳过 `coverSource=client`
- 书源同步加固：时间戳相同不回传、`bookSourceType`/布尔规范化、坏源过滤、整体擦除纪元防回填
- 软总线：低分设备参与分担、每设备 TOC/搜索/正文计数
- 正文抽取兜底：本地结果过短回退通用抽取，仍失败明确报错，不再返回标题或单句伪正文

## 产物
- `yuedu_pro_app_3.3.5.1-arm64.apk`（arm64-v8a）
- `yuedu_pro_app_3.3.5.1-armv7.apk`（armeabi-v7a）

包名 `io.legado.pro`，minSdk 24 / targetSdk 33，正式签名（v2），无 debuggable。
