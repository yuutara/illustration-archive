# Illustration Archive - Project State

> 本文件只记录**当前**项目阶段，是阶段判断的权威来源；与仓库实际不一致时以仓库为准。
> 历史阶段、开发流水与旧验收证据已归档到 [PROJECT_HISTORY.md](PROJECT_HISTORY.md)，默认不需要阅读。
> 最后更新：2026-10-31（多图牌堆改为连续可逆，见“当前工作”）。

## 当前版本与阶段

- 最新发布：`v0.5.0`，指向 `396ed46 feat: complete V0.5 AI analysis and UX polish`；V0.5 已正式封版。
- 阶段：**Windows 桌面产品化**（用户 2026-10-30 确定）。目标是普通用户双击即用的完整桌面应用，保留并接入原有 X Likes、Inbox、GIF / 多图、Gemini 分析与 OCR / 翻译。路线见 [DESKTOP_ROADMAP.md](DESKTOP_ROADMAP.md)。版本号未定，发布由用户决定。
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
- GitHub Actions CI：Java 21 `verify`、Node 24 JS 测试、Docker image build。
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
- 原服务器方式依赖 MySQL，本地配置从进程工作目录下的 `./config/` 读取；桌面版用 H2，配置在数据目录内。
- 整数 / 布尔配置写成空值（例如 `AI_ANALYSIS_TIMEOUT_SECONDS=`）会覆盖默认值并导致启动失败；不用时应删除该行，而不是留空。
- X API 按量计费，费用记在发起请求的开发者账号上；Likes 接口没有 `since_id` 或点赞时间；同步结果是"按 API 观察衔接"，不保证与网页版完全一致。
- 真实 X Sync 会产生费用，调用前须确认预算；History 的旧游标可能已失效，需显式重置。
- Gemini 模型由代码固定为 `gemini-3.1-flash-lite`；图片会发送到 Google，免费额度以用户的 Google 项目为准。
- 数据库事务不能回滚文件系统操作；已应用的 migration 不得修改，schema 变更只能新增 migration。
- 2026-10-15 的 SQL 备份是 V7 状态，不是 V6 恢复点。
- LocalStack 只用于临时实验，不能作为持久图库。

## 当前工作

- 进行中：Windows 桌面产品化。SWT + WebView2 窗口的真实图库已可用，品牌与动效打磨待用户验收，真实 X / Gemini 集成验收后置。
- 2026-10-26：完成接手健康检查；同日完成本次文档精简与维护流程调整（只改文档）。
- 2026-10-26：完成 Windows 本地图库 PoC（文件型 H2 + jpackage + 系统浏览器 + 控制窗），自动化测试与人工验收通过，详见 [DESKTOP_POC.md](DESKTOP_POC.md)。属于探索原型，未推进阶段；MySQL 运行方式不变。若保留 PoC，新的 schema migration 需同时提供 `db/migration-h2` 等价版本。
- 2026-10-27：桌面版 UI / UX 方向探索。`prototypes/desktop-ui/` 提供两套使用相同测试数据的静态原型（A 工作台、B 画廊），双击其中的 `index.html` 即可打开。用户选定 **B 画廊**，并吸收 A 的多选批量编辑与键盘快捷键，均已并入原型 B。原型未接后端，正式实现尚未开始；实现时需为图库查询补充“最近导入 / 待整理”范围和排序参数。
- 2026-10-28：原型 B 的视觉暂定采用 **C 暖纸**（浅色 / 深色都保留，原型默认即为 C），视觉后续可微调，不再新增方向；A 展厅 / B 微光仅作对照。第三方参考截图只留在本机，不随代码提交。
- 2026-10-28：第一个接入包 **C 图库浏览（只读预览）**：现有 Spring Boot 同源提供 `/c/`，沿用桌面启动器的访问保护；接入分页作品列表、缩略图（缺失时回退原图）、详情、原图与多图浏览，保留循环牌堆与浅 / 深色。旧图库 `/` 不变（“操作”菜单增加入口）。编辑、删除、导入、批量整理、搜索与筛选在新页面中明确禁用。未扩数据库或业务架构，无新 migration。启动与验收见 [DESKTOP_POC.md](DESKTOP_POC.md#c-图库浏览只读预览2026-10-28)。后续：“最近导入 / 待整理”范围与其他排序（需新查询参数）、编辑 / 删除 / 导入接入、视频预览。
- 2026-10-28：第二个接入包 **C 图库搜索与作者 / 标签筛选（只读）**：复用现有列表与候选 API（作者组内或、标签组内或、组间及关键词且，服务端全库筛选），候选可翻过前 20 条；换条件重置分页并丢弃过期响应；条件同步到页面地址。验收中发现 H2 下 `@账号` 搜索区分大小写（拼接结果没有继承列的忽略大小写），已在 `IllustrationRepository` 对这一比较两侧调用 `LOWER` 修复，无 schema / migration 改动。Codex Review 与 ego-browser 验收已完成；修复后用最新 JAR 经桌面启动器复验通过。真实 MySQL 未验证，旧 `dist` 安装包未更新。详见 [DESKTOP_POC.md](DESKTOP_POC.md#搜索与作者--标签筛选2026-10-28)。已提交为 `bae985e`。
- 2026-10-28：第三个接入包 **C 图库单件编辑**：卡片、看图器的“编辑”和 E 键共用一个编辑面板，可修改标题、作者（选择已有 / 清空）、标签（选择已有 / 移除）、备注和来源链接。复用现有详情、候选与 PATCH 接口：读完完整详情才能编辑，只发送改动过的字段，204 不解析。保存中防止重复提交，失败保留草稿；取消、Esc、关闭、切换作品都走同一个离开确认。保存后同步卡片、详情与看图器；有筛选时按服务端结果重读已载入的列表。后端、数据库与 migration 都没有改动。自动化测试与 ego-browser 实机验收（最新 JAR、隔离 H2 图库）已通过；真实 MySQL 未验证，`dist` 仍是旧包。删除、导入、批量整理、排序留待后续。详见 [DESKTOP_POC.md](DESKTOP_POC.md#单件编辑2026-10-28)。已提交为 `09b7dd4`。
- 2026-10-28：第四个接入包 **C 图库本地导入**：可以选择或拖入多个 JPEG / PNG / GIF，每个文件单独请求现有导入接口、按顺序处理，逐项显示“已导入 / 图库中已存在 / 失败 / 无法确认”；部分失败不影响其他文件。断网时如实标为“无法确认”并暂停；“重试”只重发失败与无法确认的。完成后按服务端结果刷新列表，筛选条件保留，新作品不符合条件时给出“清除条件查看”。后端、数据库与 migration 都没有改动。自动化测试已通过；Codex 独立 Review 与浏览器复验通过（含复审后修复的队列卡住与“无法确认”重试同步问题）。已知：现有校验不检查图片能否完整解码，残缺的 GIF 会被接受。详见 [DESKTOP_POC.md](DESKTOP_POC.md#本地导入2026-10-28)。
- 2026-10-28：第五个接入包 **C 图库单件删除**：看图器“更多 → 删除作品”和 Delete 键共用一个确认框（名称、图片数、永久删除且无法恢复，默认焦点在“取消”）。复用现有 DELETE 接口，数据库提交后清理文件的顺序不变；删除中防重复提交；回执丢失或服务出错时重新读取详情核实，核实不了就如实显示“无法确认”。成功后回到图库，条件保留，列表与总数同步，分页不漏不重。唯一后端改动（经用户确认）：作品不存在时详情、编辑、删除接口由 500 改为 404，无 migration。相关 Java 27 项（含隔离 H2 删除用例）与 JS 216/216 通过。Codex 独立 Review 与浏览器验收已通过（最新 `target/classes`、隔离 H2）：作品墙删除焦点 P2 已修复并复验，取消与看图器焦点小回归通过，本轮独立相关 JS 14/14。真实 MySQL 未验证，`dist` 仍是旧包。详见 [DESKTOP_POC.md](DESKTOP_POC.md#单件删除2026-10-28)。
- 2026-10-28：**桌面默认打开 C 图库**：会话入口写入 Cookie 后跳转 `/c/`，首次启动与控制窗“打开图库”都直接进入新版图库；旧图库 `/`、访问保护与 MySQL 运行方式不变。入口测试（隔离 H2）12/12 通过。`dist` 已用最新代码重新打包，旧包保留在 `dist\previous-2026-10-27\`。Codex 独立 Review、包内核对与隔离浏览器验收通过，用户已确认双击 EXE 体验通过。详见 [DESKTOP_POC.md](DESKTOP_POC.md#默认进入-c-图库与桌面包更新2026-10-28)。
- 2026-10-29：**图库列表读取的双库契约测试 + MyBatis 局部迁移**。`IllustrationRepository` 的 `count`、`findGalleryPage`、`findGalleryAssetsByIllustrationIds` 改由 `IllustrationGalleryMapper`（XML 动态 SQL + constructor 映射）执行，公开方法与服务层不变；其余 SQL（导入、编辑、删除、详情、X 归档与同步）仍用 JdbcTemplate。新增依赖 `mybatis-spring-boot-starter` 4.1.0；`mybatis.configuration.local-cache-scope=statement`。无 schema / migration 改动。阶段 A 先让新契约测试（`IllustrationGalleryReadContract`：组合筛选、关键词转义与大小写、稳定分页、封面与资源映射、空作者、空结果）在原 JdbcTemplate 实现上通过（H2 7/7、隔离 MySQL 7/7），阶段 B 迁移后原样复用：H2 7/7、隔离 MySQL 7/7；F03 图库 MySQL 测试与 X Sync 迁移 MySQL 测试通过（临时 schema 均已删除）；桌面 Spring 上下文测试验证同一 DataSource、同一事务与 JDBC 写入后再读的新鲜度（改回 SESSION 缓存时该测试失败）。`mvnw verify`：412 tests，0 failures，11 skipped，BUILD SUCCESS。被取代的 6 个 mock SQL 字符串测试已删除。Codex 独立 Review 与浏览器验收已通过；Review 指出的 MySQL 契约测试清理缺少所有权判断已修复：`DisposableSchema` 只删除本次成功创建的 schema，创建后迁移失败立即清理、初始化失败由 `@AfterAll` 清理（`DisposableSchemaTest` 4 项；真实 MySQL 上验证同名库已存在时原库及数据保留、注入初始化失败后临时库被删除，无残留）。`dist` 未重新打包。后续单独做"未打标签 + 稳定排序"。
- 2026-10-30：**C 图库视觉重做（静展）**：用户在可交互预览中选定“1+2 融合”，取代 C 暖纸。`theme-quiet.css` 取代 `theme-paper.css`：只用无衬线、中性灰阶加群青、玻璃保留且折射仍关闭。说明只写真实信息，看图器信息改为右侧栏并加作品主色环境光，顶栏下滑收起，未接入入口先隐藏。后端、数据库、migration 未改；已验收的交互保留，只调了牌堆的展开角度。Refactoring UI Skill 按需使用：固定在提交 `4459662`，放在仓库外的 `D:\ClaudeProjects\tools`，未注册、未写配置。详见 [DESKTOP_POC.md](DESKTOP_POC.md#视觉重做静展2026-10-30)。视觉与手感待用户验收；真实 MySQL 与 `dist` 未更新。
- 2026-10-30：**C 图库未打标签与排序**：列表接口新增 `untagged`、`sort`（newest / oldest / title / author），与关键词、作者、标签及分页组合；每种排序以作品 ID 收尾，翻页不漏不重，无标题 / 无作者排最后。页面增加“全部 / 未打标签”范围（带全库待打标签数）和排序菜单，条件写进地址。无 schema / migration 改动；MyBatis 列表读取结构不变。H2 与一次性 MySQL schema 契约测试通过，真实 MySQL 业务库未写入。用户已人工验收通过；`dist` 未更新。详见 [DESKTOP_POC.md](DESKTOP_POC.md#未打标签与排序2026-10-30)。已提交为 `4b7a486`。
- 2026-10-30：**C 图库批量整理**：选择模式（点选、Ctrl / Shift、全选已载入）加整理面板，统一改作者、增减标签，先成草稿再一次应用。新增 `POST /api/illustrations/batch/metadata`（单事务，全部生效或全部不生效，可安全重试）与 `/batch/tags`。不提供批量删除。无 schema / migration 改动。H2 与一次性 MySQL schema 契约测试通过，事务回滚经真实 Spring 与 HTTP 验证，真实 MySQL 业务库未写入。`dist` 未更新，桌面视觉与交互待用户验收。详见 [DESKTOP_POC.md](DESKTOP_POC.md#批量整理2026-10-30)。
- 2026-10-30：**桌面产品化第一轮：能力打通**。X 同步、Inbox、归档接入 H2 桌面模式，复用原服务：`UTC_TIMESTAMP(3)` 改为 Java 传入的 UTC 参数（H2 没有该函数，注册自定义函数会让数据库文件依赖 Java 类名），`x_like_item` 插入按列名取键；同步失败原先被吞掉，现在记日志。新增 PKCE 公开客户端登录（`XAuthorizationService`，独立回调监听 `127.0.0.1:47392`）、用户自有 Client ID、设置页、统一导航、X 收藏页、看图器 AI 分区、MP4 播放、代理（跟随 Windows 或手动）、Gemini 密钥运行时生效。Edge `--app` 独立窗口，SSE 在线通道判断关窗退出。`--migrate-from-mysql` 只读迁移到新目录。REVIEW 的 R2–R4、R6–R8 已修。无 schema / migration 改动。`mvnw test`：459 tests，0 failures / errors，3 skipped（F03 / F06 MySQL、LocalStack，本轮未配置），含一次性 MySQL schema 上的迁移工具与 X Sync 迁移测试，服务器上无残留 schema；此后的小改动重跑了相关类。JS 256/256。隔离 H2 + 假 Inbox 用 ego-browser 自查了 X 收藏、设置、AI 分区和新建入口。`dist\IllustrationArchive` 已构建，未启动过。同日经用户批准，先对真实 MySQL 只读试跑，再复制到 `%LOCALAPPDATA%\IllustrationArchive`（作品 31、X 条目 425）：独立核对 9 张表逐行一致、68 个文件 SHA-256 一致，源库与媒体未变。该目录现在是桌面版的默认图库，是当时的快照；MySQL 仍在，之后的新增不会自动同步，也没有 X 登录。用户已从资源管理器双击新包验收：独立窗口、导航、真实图库 31 件、X 收藏 384 件待整理都正常。**未验证**：真实 X 授权与同步（付费）、真实 Gemini、物理拖放、MP4 播放。详见 [DESKTOP_ROADMAP.md](DESKTOP_ROADMAP.md) 与 [DESKTOP_POC.md](DESKTOP_POC.md#桌面产品化第一轮2026-10-30)。
- 2026-10-30：**X 收藏修复、桌面选型、图标与启动图**。图片失败的主因：X CDN 对本机 Referer 返回 351，干净配置的 Edge 里旧页面 0/39 张加载；两个 Inbox 页面改为 `no-referrer`。列表用 `small`、预览用 `large`，解码量降 67%；读不到时显示占位，逐件整理时卡片不再重建。接口 P50 3.9 ms，后端未改。桌面模式静态资源 `no-cache`。选定 SWT + WebView2（隔离原型通过代理继承、媒体、性能、关闭四项，正式迁移与 Java 21 留到下一轮）。新增应用图标与启动图，正常启动不再显示 Swing 控制窗。Java 定向 18/18、JS 259/259，未跑全量。新包在 `dist\round2`，`dist\IllustrationArchive` 未更新。真实图库手感、观感待用户验收。详见 [DESKTOP_ROADMAP.md](DESKTOP_ROADMAP.md) 与 [DESKTOP_POC.md](DESKTOP_POC.md#x-收藏修复与启动图2026-10-30)。
- 2026-10-31：**SWT + WebView2 窗口迁移**。用户先验收了 round2：X 收藏能正常浏览，GIF 能播放；仍读不到的媒体对应 X 上已删除、隐藏的帖子或被冻结的账号，属于上游不可访问，保留占位、重试与打开原帖，不自动删除记录。项目升到 Java 21（CI、Docker 同步），JVM 直接持有窗口，删除 Swing 控制窗和 SSE 关窗判断，Edge `--app` 只作缺少 WebView2 时的回退。窗口位置与主题记忆、标题栏跟随主题、外链走默认浏览器、再次双击唤起窗口。打包改为 jlink 运行时 + 解压布局 + 打包时生成的 CDS 归档，到页面就绪约 3 秒（原约 8 秒），包体 252 MB。业务服务、数据库与 migration 未改。`mvnw test`（JDK 21）462 tests，0 failures，19 skipped；JS 264/264。隔离图库上实机验证通过，真实图库待用户验收；新包在 `dist\round3`。详见 [DESKTOP_POC.md](DESKTOP_POC.md#swt--webview2-窗口2026-10-31)。
- 2026-10-31：**品牌与动效打磨**。启动图换成不随主题变化的深墨卡片，加真实阶段文字和进度线；窗口改为等页面报告首屏就绪再显现（最多多等 700 ms），作品区在每次启动的第一屏淡入一次。补了 125% / 150% 缩放的任务栏图标尺寸。新增 `motion.css`，三个页面共用切页与主题过渡、弹层进出场、基础反馈和系统“减少动效”规则，X 收藏补上卡片、图片渐显和预览开合。onetake 是生成视频的 Agent Skill，许可证禁止商用，只作设计思路参考。后端业务、数据库、migration 未改。JS 272/272，Java 定向 23/23，未跑全量。隔离合成图库上，到窗口显现的中位数 4266 ms → 4405 ms；此前有两次 5.8–6.1 s 的慢启动，后续 6 轮未再现。用户已手工验收并提交。详见 [DESKTOP_POC.md](DESKTOP_POC.md#品牌与动效打磨2026-10-31)。
- 2026-10-31：**应用图标定稿**。用户从生成稿中选定蓝色卡片叠留白作品加朱砂印的图案，取代旧的蓝底牌堆；母版 `packaging\icon\app-icon.png`，16–20 px 单独手绘，EXE、窗口、网页标签和启动图同步更换。落选稿与概念预览页已删除。
- 2026-10-31：**系统托盘与弹窗收尾**。关闭窗口可选最小化到托盘或退出，选择可记住，设置页“关闭窗口时”可改回；询问框在页面里，页面答不上来（旧图库、页面卡死）时用系统提示框。托盘图标左键或菜单恢复窗口，右键菜单退出，再次打开程序也能恢复。SWT 隐藏窗口后 WebView2 仍满帧渲染（实测 180 fps），所以隐藏时经反射调用 `put_IsVisible(false)`，页面进入 hidden、渲染停止。注销或关机时走同一退出流程。桌面版去掉顶栏的“插画档案”（标题栏已有）和设置页的“打开旧版界面”，`/` 路由保留。弹窗宽度收敛为 420 / 520 / 620，放不下时内部滚动；费用确认和快捷键补上焦点约束与焦点归还；X 收藏与设置页的提示条、圆角、输入框对齐图库。无 schema / migration 改动。JS 285/285，Java 定向 36/36，未跑全量。隔离图库上实测通过询问、记住、恢复、最大化保持、最小化时询问、系统提示框兜底，退出后无残留进程，实例锁与 `entry.txt` 已释放。托盘菜单“退出”的点击、注销 / 关机路径未实测，待用户验收。新包在 `dist\round6`。详见 [DESKTOP_POC.md](DESKTOP_POC.md#系统托盘与弹窗收尾2026-10-31)。
- 2026-10-31：**对话框内部视觉统一**。导入弹窗的拖放区改为“图标 + 标题 + 说明 + 按钮”，不再是下划线链接加长句，拖入时变为实线群青；加入文件后收成一行。主题新增凹面、虚线、遮罩、焦点环、错误底色等 token，输入框、作品摘要、状态说明、关闭选项、设置页输入框共用，浅深色同一套。用户验收时要求对话框本身也是透明主题，所以对话框和编辑面板改为半透明玻璃，浅色 78%、深色 82% 不透明，通透程度一致，并按最坏背景保证小字对比度不低于 4.5。导入逻辑、托盘、X、Gemini 未改。JS 全量 290/290 通过。详见 [DESKTOP_POC.md](DESKTOP_POC.md#对话框内部视觉统一2026-10-31)。
- 2026-10-31：**修复升级后仍显示旧页面**。打包脚本为 CDS 把 jar 时间固定为常量，Spring 用它作 `Last-Modified`，所以新版本的页面都被判为未修改，WebView2 一直用最早缓存的版本；用户在 round6 上看到旧导航、旧导入框就是这个原因。桌面模式关闭 `use-last-modified`，静态资源每次完整返回。新包在旧缓存配置上直接显示新页面，用户的旧缓存无需手工清理。
- 2026-10-31：**修复多图快速滑动闪烁**。落定前等正面图片解码，最多 120 ms；叠卡图片改为同步解码。当时的“一次按住只走一个方向”不符合用户要的手感，已被下一条取代。
- 2026-10-31：**多图牌堆改为连续可逆**。按用户提供的视频重写：所有牌的姿态只由一个连续位置 `t` 决定（`c-deck-model.js`），按住期间可以左右来回拖，不用松手，松手时才按位置和速度落定；上一页、下一页静止时分列左右，换方向不用换底牌；两张牌分到最开、不再重叠时才交换前后；动画中按下就从当前位置接着拖。同时修掉一个老缺陷：悬停的 `.45s` 过渡优先级更高，拖动时一直在平滑，牌追不上手。无后端改动。JS 全量通过；真实 WebView2 + 可信鼠标事件：手与牌偏差 0 px，2 / 3 / 4 张图 27 条路径全过，错页帧、空白帧、角标不一致都是 0。真实手感待用户验收。新包在 `dist\round9`。详见 [DESKTOP_POC.md](DESKTOP_POC.md#多图牌堆连续可逆2026-10-31)。
- 分工：Claude Code 负责主要开发；Codex 负责复杂技术设计与必要的 Review；ChatGPT 负责阶段性产品方向讨论。具体约定见 [AGENTS.md](../AGENTS.md)。

## 后续候选（均未排期，需用户确认）

- 启动与本地安全小加固：空值配置容错与清晰报错、本地默认只绑定 `127.0.0.1`、可选集成按需创建 HttpClient。
- 完成 X Likes 全范围同步：属于真实付费调用，需用户批准预算。
- 按需整理本地仓库（由用户手动执行）：更新本地 `master`，清理已合并的功能分支；不涉及远端分支或 GitHub 仓库设置。
- 依赖补丁更新（例如 Spring Boot 4.1.x 补丁版本），需配套完整测试。
- V0.5 封版时保留的 P1：Picker 重复搜索按钮、Inbox 重复成功链接、来源重复、手机 Detail 管理信息顺序。
- 留待未来版本决定：跨作品导航、超过四页或动态媒体的 AI 分析、AI 结果持久化。
- 桌面化的后续工作包见 [DESKTOP_ROADMAP.md](DESKTOP_ROADMAP.md#工作包)。

## 文档导航

| 文档 | 内容 | 何时阅读 |
| --- | --- | --- |
| [AGENTS.md](../AGENTS.md) | 协作规则与维护约定 | 每次任务 |
| 本文件 | 当前版本、阶段、健康基线、限制与候选 | 每次任务 |
| [README.md](../README.md) | 功能、API、运行配置、核心设计与测试 | 涉及运行、API 或设计时 |
| [DESKTOP_ROADMAP.md](DESKTOP_ROADMAP.md) | 桌面应用的能力现状、关键决定、X 费用与风险、工作包 | 规划桌面工作时 |
| [DESKTOP_POC.md](DESKTOP_POC.md) | Windows 桌面版：架构、构建、测试与验收记录 | 涉及桌面化、H2 或打包时 |
| [X_LIKES_SYNC_CONSISTENCY.md](X_LIKES_SYNC_CONSISTENCY.md) | 同步预算、Recent / History、检查点、V7 迁移与验收边界 | 涉及 X Sync 时 |
| [PROJECT_HISTORY.md](PROJECT_HISTORY.md) | V0.1–V0.5 与维护阶段的历史归档 | 需要追溯历史决策或证据时 |
