## 阅读Pro 3.2.0.1（versionCode 3200001）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）3.2.0.1** 同步发布、同一套版本号；版号按服务端 FnReader **3.2.0 + 0.0.0.1** 对齐。

### 同步服务端 FnReader v3.2.0

服务端 main/tag `v3.2.0` 为 `43920411e3af2cd3c0b8529ae467fb85dce5298d`，发布说明：

- **多客户端 / 多书源缓存合并**：同一本书按 `workId` 自动归并，章节取并集；同章不同正文保留多版本（`variants`），正文取更长的一份；`raw/<srcHash>/` 按来源分目录，8 源并发不分裂
- **「书籍」页与本地 `.nb` 缓存清单**：每本显示缓存章数、同步章数、来源数、变体数
- **设置页按功能重排**：IPv6 地址独立成行，书源导入链接改明码并修复缺斜杠
- **引擎与导出优化**：增量哈希、导出流式写、Python 池扩到核数，修复阅读接入详情 404

`docs/APP同步接口.md` 仅新增「多客户端 / 多书源并发合并（0.1.25+）」说明，`/api/m/*` 路径、字段、鉴权、超时、任务类型无新增客户端必填项；`docs/软总线路由与权重.md`、`docs/AI构建指南.md` 未变化。

### 客户端本版

- FnReader 同步通知分道、新增同步记录页、空书源过滤（T1–T5）
- FnReader 配置菜单改为分组列表：服务器地址/访问令牌整行点击弹输入框；手机解析待命、TOC 目录先行、TRE 代理解析改为滑动开关；每个动作独立一行，同步书架/立即同步/测试连接/传书点击先弹进度框
- 顶部状态、TOC pref 持久化、TRE 服务端写入失败回滚、同步记录入口均已联调

### 三包

| APK | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_3.2.0.1-arm64.apk` | 18 813 374 B（17.94 MB） | `5FE1C2B4C45DF09BA43F074D7516948E9CFF151D729CB6DBB58B4488293D6A4A` |
| `yuedu_pro_app_3.2.0.1-armv7.apk` | 18 768 391 B（17.90 MB） | `EAA1CAE1C92F20EC86B609A0136B9F8D72D02DA3BB07027FE899BA613156F769` |

### 校验

- `aapt2 dump badging`：两包均 `package='io.legado.pro' versionCode='3200001' versionName='3.2.0.1'`，minSdk 24 / target 33，无 `application-debuggable`
- `apksigner verify`：v2 true（minSdk 24 下 v1 false 正常）
- 包内 `assets/updateLog.md` 首条为 `**2026/11/23（3.2.0.1）**`
- debug 包 MuMu Android 12 覆盖安装：菜单打开、服务器/令牌输入框、三个开关、测试连接进度框与同步书架均正常

### 说明

- 本版服务端契约未新增客户端必填字段；多源缓存合并与「书籍」页由服务端/Web 侧生效，客户端沿用现有 `/api/m/*`
- 应用内「检查更新」指向 GitHub `TZYLXS/yuedu-pro` 的 `releases/latest`；本 Release 发布后即指向 `v3.2.0.1`
