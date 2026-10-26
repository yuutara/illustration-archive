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
- 分工：Claude Code 负责主要开发；Codex 负责复杂技术设计与必要的 Review；ChatGPT 负责阶段性产品方向讨论。具体约定见 [AGENTS.md](../AGENTS.md)。

## 后续候选（均未排期，需用户确认）

- 启动与本地安全小加固：空值配置容错与清晰报错、本地默认只绑定 `127.0.0.1`、可选集成按需创建 HttpClient。
- 完成 X Likes 全范围同步：属于真实付费调用，需用户批准预算。
- 按需整理本地仓库（由用户手动执行）：更新本地 `master`，清理已合并的功能分支；不涉及远端分支或 GitHub 仓库设置。
- 依赖补丁更新（例如 Spring Boot 4.1.x 补丁版本），需配套完整测试。
- V0.5 封版时保留的 P1：Picker 重复搜索按钮、Inbox 重复成功链接、来源重复、手机 Detail 管理信息顺序。
- 留待未来版本决定：跨作品导航、超过四页或动态媒体的 AI 分析、AI 结果持久化。
- 探索话题（只讨论，不实施）：桌面化，主要障碍是 MySQL 依赖、相对路径配置、X 授权流程和打包签名。
- 探索话题（只讨论，不实施）：免费的 X Likes 同步路径；官方数据归档、浏览器扩展等方案各有完整性或平台规则风险。

## 文档导航

| 文档 | 内容 | 何时阅读 |
| --- | --- | --- |
| [AGENTS.md](../AGENTS.md) | 协作规则与维护约定 | 每次任务 |
| 本文件 | 当前版本、阶段、健康基线、限制与候选 | 每次任务 |
| [README.md](../README.md) | 功能、API、运行配置、核心设计与测试 | 涉及运行、API 或设计时 |
| [X_LIKES_SYNC_CONSISTENCY.md](X_LIKES_SYNC_CONSISTENCY.md) | 同步预算、Recent / History、检查点、V7 迁移与验收边界 | 涉及 X Sync 时 |
| [PROJECT_HISTORY.md](PROJECT_HISTORY.md) | V0.1–V0.5 与维护阶段的历史归档 | 需要追溯历史决策或证据时 |
