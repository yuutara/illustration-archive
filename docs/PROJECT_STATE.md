# Illustration Archive - Project State

> 本文件只记录**当前**项目阶段，是阶段判断的权威来源；与仓库实际不一致时以仓库为准。
> 历史阶段、开发流水与旧验收证据已归档到 [PROJECT_HISTORY.md](PROJECT_HISTORY.md)，默认不需要阅读。
> 最后整理：2026-10-26（文档精简；未改变代码、配置、migration 或项目阶段）。

## 当前版本与阶段

- 最新发布：`v0.5.0`，指向 `396ed46 feat: complete V0.5 AI analysis and UX polish`；V0.5 已正式封版。
- 阶段：maintenance / interest-driven。**没有已批准的 V0.6**，新阶段须由用户明确确认。
- 主线分支：`v0.5-ai-analysis`，HEAD 为 `4645b22`。
- 最近一次维护：X Likes 同步一致性修复（`5800c64`），2026-10-15 经 PR #1 合并（`4645b22`）；`v0.5.0` tag 未改变。
- `master`：远端 `origin/master` 已于 2026-10-26 00:38 推送到 `4645b22`，与主线一致（同日 `git ls-remote` 联网核对）；本地 `master` 分支仍停在 V0.4 发布提交 `a40ece5`，尚未更新。
- 历次 tag：`v0.1.0` `46434c3`、`v0.2.0` `04f8771`、`v0.3.0` `83f2fd6`、`v0.4.0` `a40ece5`、`v0.5.0` `396ed46`。
- 已应用 Flyway migration：V1–V7（V7 为 X Likes API 观察顺序与 Recent 检查点）。

## 当前能力摘要

- 本地单张 / 批量 / 多图归档；SHA-256 内容去重并由数据库唯一约束兜底；批量部分成功。
- 数据库事务与文件补偿分离：先存文件再写库，失败补偿删除；删除在事务提交后清理文件。
- JPEG / PNG 缩略图；GIF 保留原图；X `animated_gif` 保存为 MP4。
- X Likes → Inbox → Archive → Gallery；Post ID 幂等，同图不同 Post 在归档时再做 SHA-256 去重。
- 综合 Sync：持久化 Recent 续传与 History 游标共用请求预算，Recent / History 分别报告进度。
- OAuth access token 按需自动刷新；初始 token 仍需在 X Developer Console 手工生成，没有浏览器授权流程。
- Gallery Masonry、统一 Viewer 与 Inspector、全库搜索、Author / Tag 多选筛选、元数据编辑、Dark Mode、移动端布局。
- 可选能力（默认关闭或需显式选择）：Gemini 多图 / 漫画分析（结果不持久化）、S3-compatible 存储实验、Docker Compose。
- GitHub Actions CI：Java 17 `verify`、Node 24 JS 测试、Docker image build。
- 功能、API、配置和核心设计细节见 [README](../README.md)。

## 近期健康基线

来源：2026-10-26 Claude Code 接手健康检查，结果记录在当次会话中，没有单独提交日志；本次文档精简未重跑。

- Java：从 HEAD `4645b22` 导出的干净副本，JDK 17.0.19，`mvnw verify`：385 tests，0 failures / errors，4 skipped，BUILD SUCCESS，JAR 打包成功。
- 4 个 skipped：未设置环境变量的 3 个 MySQL opt-in 测试，以及 1 个 LocalStack 测试。
- JavaScript：`node --test` 运行全部 `src/test/js/*.test.js`，133/133 通过。
- 真实数据库（只读会话查询）：Flyway V1–V7 均成功，V7 于 2026-10-15 应用，没有待执行 migration。
- 运行中的本地实例（只读 HTTP）：Gallery、Inbox、Author / Tag 列表，以及原图 / MP4 / 缩略图读取正常。
- 当次检查时，X Likes 的 Recent 与 History 都尚未完成全范围同步；之后的进度以 Inbox 页面 `syncProgress` 为准。
- 当次未验证：真实 X Sync / OAuth 刷新、真实 Gemini、真实写入路径、Docker Compose、S3、浏览器 UI。
- 历史实现阶段的 "385 tests / 382 passed / 3 skipped" 运行条件不同（启用了迁移 MySQL 测试），见 [X Likes 专项文档](X_LIKES_SYNC_CONSISTENCY.md#验证记录)。

## 重要限制

- 无用户鉴权：仅供本机使用，不要暴露到公网或不可信局域网。
- 依赖 MySQL；本地配置从进程工作目录下的 `./config/` 读取。
- 整数 / 布尔配置写成空值（例如 `AI_ANALYSIS_TIMEOUT_SECONDS=`）会覆盖默认值并导致启动失败；不用时应删除该行，而不是留空。
- X API 按量计费，Likes 接口没有 `since_id` 或点赞时间；同步结果是"按 API 观察衔接"，不保证与网页版完全一致。
- 真实 X Sync 会产生费用，调用前须确认预算；History 的旧游标可能已失效，需显式重置。
- Gemini 模型由代码固定为 `gemini-3.1-flash-lite`；图片会发送到 Google，免费额度以用户的 Google 项目为准。
- 数据库事务不能回滚文件系统操作；已应用的 migration 不得修改，schema 变更只能新增 migration。
- 2026-10-15 的 SQL 备份是 V7 状态，不是 V6 恢复点。
- LocalStack 只用于临时实验，不能作为持久图库。

## 当前工作

- 没有进行中的功能开发。
- 2026-10-26：完成接手健康检查；同日完成本次文档精简与维护流程调整（只改文档）。
- 2026-10-26：完成 Windows 本地图库 PoC（文件型 H2 + jpackage + 系统浏览器 + 控制窗），自动化测试与人工验收通过，详见 [DESKTOP_POC.md](DESKTOP_POC.md)。属于探索原型，未推进阶段；MySQL 运行方式不变。若保留 PoC，新的 schema migration 需同时提供 `db/migration-h2` 等价版本。
- 2026-10-27：桌面版 UI / UX 方向探索。`prototypes/desktop-ui/` 提供两套使用相同测试数据的静态原型（A 工作台、B 画廊），双击其中的 `index.html` 即可打开。用户选定 **B 画廊**，并吸收 A 的多选批量编辑与键盘快捷键，均已并入原型 B。原型未接后端，正式实现尚未开始；实现时需为图库查询补充“最近导入 / 待整理”范围和排序参数。
- 2026-10-28：原型 B 的视觉暂定采用 **C 暖纸**（浅色 / 深色都保留，原型默认即为 C），视觉后续可微调，不再新增方向；A 展厅 / B 微光仅作对照。第三方参考截图只留在本机，不随代码提交。
- 2026-10-28：第一个接入包 **C 图库浏览（只读预览）**：现有 Spring Boot 同源提供 `/c/`，沿用桌面启动器的访问保护；接入分页作品列表、缩略图（缺失时回退原图）、详情、原图与多图浏览，保留循环牌堆与浅 / 深色。旧图库 `/` 不变（“操作”菜单增加入口）。编辑、删除、导入、批量整理、搜索与筛选在新页面中明确禁用。未扩数据库或业务架构，无新 migration。启动与验收见 [DESKTOP_POC.md](DESKTOP_POC.md#c-图库浏览只读预览2026-10-28)。后续：“最近导入 / 待整理”范围与其他排序（需新查询参数）、编辑 / 删除 / 导入接入、视频预览。
- 2026-10-28：第二个接入包 **C 图库搜索与作者 / 标签筛选（只读）**：复用现有列表与候选 API（作者组内或、标签组内或、组间及关键词且，服务端全库筛选），候选可翻过前 20 条；换条件重置分页并丢弃过期响应；条件同步到页面地址。验收中发现 H2 下 `@账号` 搜索区分大小写（拼接结果没有继承列的忽略大小写），已在 `IllustrationRepository` 对这一比较两侧调用 `LOWER` 修复，无 schema / migration 改动。Codex Review 与 ego-browser 验收已完成；修复后用最新 JAR 经桌面启动器复验通过。真实 MySQL 未验证，旧 `dist` 安装包未更新。详见 [DESKTOP_POC.md](DESKTOP_POC.md#搜索与作者--标签筛选2026-10-28)。已提交为 `bae985e`。
- 2026-10-28：第三个接入包 **C 图库单件编辑**：卡片、看图器的“编辑”和 E 键共用一个编辑面板，可修改标题、作者（选择已有 / 清空）、标签（选择已有 / 移除）、备注和来源链接。复用现有详情、候选与 PATCH 接口：读完完整详情才能编辑，只发送改动过的字段，204 不解析。保存中防止重复提交，失败保留草稿；取消、Esc、关闭、切换作品都走同一个离开确认。保存后同步卡片、详情与看图器；有筛选时按服务端结果重读已载入的列表。后端、数据库与 migration 都没有改动。自动化测试与 ego-browser 实机验收（最新 JAR、隔离 H2 图库）已通过；真实 MySQL 未验证，`dist` 仍是旧包。删除、导入、批量整理、排序留待后续。详见 [DESKTOP_POC.md](DESKTOP_POC.md#单件编辑2026-10-28)。已提交为 `09b7dd4`。
- 2026-10-28：第四个接入包 **C 图库本地导入**：可以选择或拖入多个 JPEG / PNG / GIF，每个文件单独请求现有导入接口、按顺序处理，逐项显示“已导入 / 图库中已存在 / 失败 / 无法确认”；部分失败不影响其他文件。断网时如实标为“无法确认”并暂停；“重试”只重发失败与无法确认的。完成后按服务端结果刷新列表，筛选条件保留，新作品不符合条件时给出“清除条件查看”。后端、数据库与 migration 都没有改动。自动化测试已通过；Codex 独立 Review 与浏览器复验通过（含复审后修复的队列卡住与“无法确认”重试同步问题）。已知：现有校验不检查图片能否完整解码，残缺的 GIF 会被接受。详见 [DESKTOP_POC.md](DESKTOP_POC.md#本地导入2026-10-28)。
- 分工：Claude Code 负责主要开发；Codex 负责复杂技术设计与必要的 Review；ChatGPT 负责阶段性产品方向讨论。具体约定见 [AGENTS.md](../AGENTS.md)。

## 后续候选（均未排期，需用户确认）

- 启动与本地安全小加固：空值配置容错与清晰报错、本地默认只绑定 `127.0.0.1`、可选集成按需创建 HttpClient。
- 完成 X Likes 全范围同步：属于真实付费调用，需用户批准预算。
- 按需整理本地仓库（由用户手动执行）：更新本地 `master`，清理已合并的功能分支；不涉及远端分支或 GitHub 仓库设置。
- 依赖补丁更新（例如 Spring Boot 4.1.x 补丁版本），需配套完整测试。
- V0.5 封版时保留的 P1：Picker 重复搜索按钮、Inbox 重复成功链接、来源重复、手机 Detail 管理信息顺序。
- 留待未来版本决定：跨作品导航、超过四页或动态媒体的 AI 分析、AI 结果持久化。
- 桌面化：PoC 已验证 H2 替代 MySQL、独立数据目录与 jpackage 打包可行（见 [DESKTOP_POC.md](DESKTOP_POC.md) 的已知问题）；X 授权流程、安装器 / 签名、自动更新仍未处理，正式立项需用户确认。
- 探索话题（只讨论，不实施）：免费的 X Likes 同步路径；官方数据归档、浏览器扩展等方案各有完整性或平台规则风险。

## 文档导航

| 文档 | 内容 | 何时阅读 |
| --- | --- | --- |
| [AGENTS.md](../AGENTS.md) | 协作规则与维护约定 | 每次任务 |
| 本文件 | 当前版本、阶段、健康基线、限制与候选 | 每次任务 |
| [README.md](../README.md) | 功能、API、运行配置、核心设计与测试 | 涉及运行、API 或设计时 |
| [DESKTOP_POC.md](DESKTOP_POC.md) | Windows 本地图库 PoC：架构、构建、测试与验收记录 | 涉及桌面化、H2 或打包时 |
| [X_LIKES_SYNC_CONSISTENCY.md](X_LIKES_SYNC_CONSISTENCY.md) | 同步预算、Recent / History、检查点、V7 迁移与验收边界 | 涉及 X Sync 时 |
| [PROJECT_HISTORY.md](PROJECT_HISTORY.md) | V0.1–V0.5 与维护阶段的历史归档 | 需要追溯历史决策或证据时 |
