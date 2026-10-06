# Illustration Archive - Project State

> This file records the current project state and important design decisions.
> When summaries and the actual repository conflict, the repository is the source of truth.

## Current Version

V0.5 已完成并准备封版（图库使用体验；本轮 P0 交互收敛与封版验收通过）

V0.5 实施分支：`v0.5-ai-analysis`；历史稳定基线来自 `v0.5-dark-mode`（`3faabe0 feat: add dark mode`）。

### V0.5｜交互收敛与封版验收（完成）

2026-10-06，按用户批准的五项 P0 实施减法，并达到本轮指定的封版停止条件；final patch Review 已通过，没有代码 blocker。V0.5 已完成并准备封版，停止继续打磨 V0.5，不自动进入 V0.6。以下历史阶段记录保留当时证据，其中分支、commit / push 与待验收描述只反映各阶段当时状态，不代表当前状态；最新交互与验收结论以本节为准。

- Gallery Viewer 只保留 chrome 中一个“查看详情”，已完成 AI 结果自动带入，保留当前 Asset 和 Gallery 返回位置。删除 Inspector 的“查看完整详情”“在详情继续读”，从 Detail 打开的 Viewer 不再链接回同一 Detail。
- Viewer 是完整 AI 阅读界面；单一“信息 / AI”入口打开 Inspector，内部切换作品信息 / AI 分析，切图保留所选视图。当前页描述、原文与译文优先显示，全篇摘要 / 角色 / 关系 / Tag 建议用原生 details 默认折叠。Detail 删除完整结果区与第二个分析按钮，顶部入口直接打开当前 Asset 的 Viewer AI 视图。结果仍临时保存在页面内存，一次性移交消费后删除，刷新后消失。
- Gallery Header 桌面只有品牌、搜索、统一筛选与操作；作品数和 chips 在内容区。操作菜单包含本地导入、X Inbox、主题，手机搜索独占第二行。390×844 实测搜索输入约 334px；Viewer 底栏两行、144px，Inspector 300px，图片区域 328px。
- 纯 GIF / MP4 不提供可执行分析入口。源图上限 20 MiB，处理后 JPEG 上限 3 MiB，保留 1600 万像素 / 四张静态图 / 内存 ImageIO。GeminiClient 在发送前计算实际 UTF-8 JSON bytes，限制 20,000,000 bytes；四图各达到 3 MiB 的自动化请求仍低于总量上限。失败文案提供查看原图或重新导入较小版本的动作。没有新框架、动画库、依赖、schema、队列、OCR、抽帧或分析持久化。

**自动化验证：**AI 定向 Maven `GeminiClientTest,IllustrationAiAnalysisServiceTest,IllustrationAiAnalysisControllerTest` 为 **30 tests，0 failures / errors / skipped，BUILD SUCCESS**；完整 Maven `test` 为 **350 tests，0 failures / errors，3 skipped，BUILD SUCCESS**（两个 MySQL、一个 LocalStack opt-in 未启用）。最终完整 JS 为 **126/126 通过，0 failures / skipped**；`git diff --check` 通过。覆盖单一详情导航与一次性移交、当前 Asset / 刷新边界、Inspector 键盘切换与切图保留、纯动态入口、12 MiB 源图、四图实际请求大小及总量超限拒绝。

实际命令：`.\mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp' '-Dtest=GeminiClientTest,IllustrationAiAnalysisServiceTest,IllustrationAiAnalysisControllerTest' test`；同一缓存 / temp 参数执行完整 `test`；`node --test (rg --files src/test/js -g '*.test.js')`；`git diff --check`。没有执行 clean，避免碰触既有运行进程的锁文件。

**真实浏览器与媒体验收：**最新临时后端在 18085 使用真实本机 MySQL / 原图存储及 Gemini 配置；Edge CDP 的 DOM 驱动与截图检查覆盖 1440×900 / 390×844 的 Light / Dark。Gallery → Viewer → Inspector 两个视图 → 四图切换 / 放大 → 单一详情入口 → Detail 顶部重开 AI → Gallery 已走通；移交后仍为第 4 张 Asset 39，Detail 不自动重开 Viewer，也没有完整结果区。搜索 `Cat2Sora` + Author 13 + Tag 8 得到一件作品，清除恢复全库；返回浏览位置与当前 Asset 可恢复，稳定布局无横向溢出。真实键盘 ArrowRight 切到 AI，第一次 Esc 收起 Inspector，第二次 Esc 关闭 Viewer。截图留在项目外。

真实漫画作品 34 的四页 Asset 36–39 均有描述 / 原文 / 译文且映射正确；作品 25 的 JPEG **12,691,206 bytes（12.10 MiB）、4565×3040（13.88MP）** 分析成功；纯 MP4 作品 39 / Asset 44 原视频可播放，Viewer AI tab 与 Detail 顶部 AI 入口均隐藏。这些证据验证可达性与页码归属，不保证每条译文或角色判断正确，不替代物理触屏及独立 S3 环境测试。

**受控真实归档：**只选择 Inbox **455**（`satomoriumi`，X Post `2106727722534027679`，一张 photo）执行一次 Archive，结果成功 1、重复 0、失败 0。新增 Illustration **40** / Asset **45**；Inbox **339 → 338**，Gallery **30 → 31**。归档后 Detail 原图 **1478×2048**、作者、来源和编辑入口正常，取消编辑未保存；返回 Gallery 可再次找到新作品。该链路使用真实 MySQL、存储与 CDN HTTP；没有额外 X sync、Skip、元数据 PATCH 或删除。

**运行与证据边界：**`chrome_devtools` MCP `list_pages` 仍返回 `Unexpected server response: 404`，验收通过已确认可用的 `127.0.0.1:9222` Edge CDP 完成，没有 Computer Use。临时启动最初被 Java 25 的内部 loopback socket 错误阻塞，使用已安装 JBR 21 与短 socket 路径及现有代理启动成功；未改生产代码或全局配置来绕过环境问题。临时实例验收后关闭，既有 8080 进程保留，使用新后端大小门槛前需重启该实例。

**停止边界：**本轮五项 P0 完成即停止。Picker 重复搜索按钮、Inbox 重复成功链接、来源重复和手机 Detail 管理信息顺序保留为独立 P1 记录；跨作品导航、超过四页 / 动态媒体分析、结果持久化在 V0.6 再决定，均不阻止本轮封版，不继续追加实现。

### V0.5｜AI Multi-image / Comic Analysis MVP（技术边界与历史阶段记录）

2026-10-05，按 Review 后收缩范围实现手动 Detail 实验，验证多图漫画分析是否有趣/有价值；2026-10-06 按 Review 将接入层替换为 Google Gemini API 免费层，完全保留图片处理、结果结构和 Detail UI。不推进 V0.5 Final Acceptance，未 commit / push。

- `POST /api/illustrations/{id}/ai-analysis` → 三个核心类 `IllustrationAiAnalysisController` / `IllustrationAiAnalysisService` / `GeminiClient` → Google Gemini API `gemini-3.1-flash-lite` → 当前 Viewer Inspector 展示。只读数据库和原图，无新 migration、持久化、Author/Tag 写入、历史记录、队列、全局并发系统、多 Provider 抽象、AI framework / SDK 或新依赖。
- Asset 内部查询按 `sort_order ASC, id ASC`；JPEG/PNG 1–4 张，超过四张或无静态图返回 422。GIF/MP4 跳过并返回 Asset ID、原作品位置和 MIME，逐图结果映射到原作品位置，不抽帧、不默默截取前四张。
- ImageIO 在内存中一次缩放到最大边 2048px，保持比例、小图不放大、透明 PNG 白底、统一 JPEG quality 0.88；当前独立分析限制为源图 <=20 MiB / 1600 万像素、处理后 JPEG <=3 MiB（初版为 10 / 1.5 MiB，已由本轮收敛调整）。解码前读取图片尺寸并确认格式，不使用已有 600px thumbnail、不写临时/长期文件、不新增 thumbnail pipeline。
- Java HttpClient + 现有 Jackson，非流式 Gemini REST `generateContent`；`x-goog-api-key` 只在后端请求头，`contents[].parts[]` 放有序 JPEG `inlineData`，`systemInstruction` 使用提示词，`generationConfig.responseMimeType=application/json` / `responseSchema` 请求五字段 JSON，Schema 不发送 `additionalProperties`。检查五字段、类型、页数、连续 index、数组数量及文字长度；只接受 `finishReason=STOP`，拼接最终文本 parts 并跳过 thought parts；角色身份不确定时使用描述性候选，不按作品常识猜姓名，模糊对白不补写。无自动修复、重试或其他模型 fallback。
- 配置默认关闭，`GEMINI_API_KEY` 仅从后端环境/已忽略本地配置读取，`GEMINI_MODEL=gemini-3.1-flash-lite`、`AI_ANALYSIS_TIMEOUT_SECONDS=90`；Google Free Tier 按项目决定，模型名本身不保证免费，应用不启用计费。503 表示未配置/服务不可用，422 表示图片范围/处理失败，502 为上游请求、无效结果或截断/安全拒绝，504 超时，429 有明确额度/速率提示；不会回传上游原始错误 body 或 Key。完整上游响应等待默认 90 秒，浏览器 120 秒；Storage 读取不承诺同一整次 deadline，离页取消 fetch 不保证上游立即终止。Google 免费层内容可能用于改进产品，真实调用前应确认项目资格和适用数据条款。
- Viewer Inspector 的四状态与五类结果显示跳过媒体，所有模型文本用 `textContent`，Tag 只展示，沿用 Light/Dark tokens；Detail 仅提供 Viewer 入口。普通重复点击由按钮和在途状态阻止；离页取消和丢弃迟到结果。结果仅当前 JS 内存，刷新消失。
- 2026-10-06 小扩展：每页 `texts[]` 返回原文 `source`、简体中文 `translation`、位置/类型 `note`，按大致阅读顺序输出正文，模糊文字标记 `[无法辨认]`，无文字则为空数组。后端检查每页最多 40 条、原文/翻译各最多 2000 字、备注最多 200 字；Detail 在每页描述下安全展示。复用同一次 Gemini 请求，不增加 OCR、依赖或第二个模型，不改变图片处理/代理/模型。

**历史自动化基线（2026-10-05，接入层替换前）：**完整 Maven `test` 337 tests、0 failures、0 errors、3 skipped，BUILD SUCCESS；完整 JS 119/119 通过；`git diff --check` 通过。此记录不作为当前 Gemini 接入的验证结果；替换后全量验证另行记录。

**Gemini 接入验证（2026-10-06）：**完整 Maven `test` **339 tests、0 failures、0 errors、3 skipped，BUILD SUCCESS**；完整 JS **119/119 通过，0 failures / skipped**；`git diff --check` 通过。GeminiClient 测试覆盖 REST endpoint、后端 Key 请求头、有序 inlineData、五字段 Schema、1/4 图结果、截断、安全拒绝/缺少候选、多段最终文本/忽略 thought、HTTP 错误、非法 JSON 和超时；现有图片处理、Service、Controller、Repository 和 Detail 测试仍通过。三个 skipped 仍为两个 MySQL 与一个 LocalStack opt-in 测试。

本轮完整命令沿用 `.\mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp' test`、`node --test (rg --files src/test/js -g '*.test.js')`、`git diff --check`。最初 `clean test` 被既有运行时锁住的 `target/final-acceptance-tmp/hsperfdata_YuanYuChou` 阻塞，尚未进入测试；确认旧 Client class 已清除，仅删除两个残留的旧 Test class 后直接运行完整 `test` 成功，没有停止既有应用或强删锁文件。当前代码/编译产物无旧接入类；测试日志为 ignored `.maven/gemini-analysis-maven.log`、`.maven/gemini-analysis-js.log`。未调用真实 API、未 commit / push。

**真实验收边界：**ImageIO 测试使用实际内存图片；Repository / HttpClient 与 MVC / JS 为自动化或模拟证据。2026-10-06 用户确认真实 Gemini 分析已成功，能够理解画面和剧情；本次新增文字识别/翻译尚待真实漫画验收。漫画小字可读性、角色/关系质量、真实 Local/S3 与浏览器 Light/Dark 展示未据此宣告通过；免费资格与额度仍按用户 Google 项目实际状态确认，不以 mock 代替真实验收。

**文字识别/翻译扩展验证（2026-10-06）：**相关 Maven `GeminiClientTest,IllustrationAiAnalysisServiceTest,IllustrationAiAnalysisControllerTest` **28 tests、0 failures / errors / skipped，BUILD SUCCESS**；完整 JS **120/120 通过**；`git diff --check` 与本轮修改的未跟踪文件空白检查通过。覆盖嵌套 Schema / Prompt、原文与翻译/备注、空文字页、阅读顺序、多页归属、异常字段/条数/长度保护、DTO JSON 和安全渲染；本轮未调用真实 API、未 commit / push。

本次命令：`.\mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp' '-Dtest=GeminiClientTest,IllustrationAiAnalysisServiceTest,IllustrationAiAnalysisControllerTest' test`、`node --test (rg --files src/test/js -g '*.test.js')`、`git diff --check`；ignored 测试日志为 `.maven/gemini-texts-focused.log` / `.maven/gemini-texts-js.log`。

### V0.5｜Viewer / AI 阅读流（初版历史记录，已由上方收敛方案替代）

2026-10-06，在当前未提交的 Gemini AI MVP 上调整前端交互，不推进项目阶段。Gallery / Detail Viewer 的 Inspector 增加手动 AI 分析视图，优先显示当前 Asset 的描述及文字/翻译；同页面按作品复用分析状态。Detail 顶部可直接打开 Viewer AI 视图，原有 Detail 结果区与 Viewer 共用结果。Gallery Viewer 的普通“查看详情”与“在详情继续读”使用同一导航令牌做一次性结果移交；Detail 核对作品和 Asset，消费临时存储后分别落到普通 Detail 或同一 Asset 的 Viewer AI 面板。刷新后没有分析历史；Inbox Viewer 不显示 AI。Inspector 已保存作者/标签可进入 Gallery 筛选，Inbox 成功归档链接直接显示。

本次未改变 AI API、响应结构、数据库或后端。完整 JS `node --test (rg --files src/test/js -g '*.test.js')` 为 **125/125 通过**；`git diff --check` 通过。本地模拟 API 的浏览器检查确认手机底部 Sheet 与桌面侧栏能同时显示图片和长文本，并验证“在详情继续读”移交后打开相同 Asset；模拟检查不等于真实 Gemini、MySQL、文件存储或最终人工手感验收。未 commit / push。

### V0.5-F07｜Dark Mode

2026-10-05，在 `v0.5-x-animated-media` 的稳定基线上进入 `v0.5-dark-mode`。统一 Light / Dark Theme 已完成，用户确认人工视觉验收通过；尚未 commit / push，不宣告 V0.5 Final Acceptance。

- 使用 `html[data-theme="light"]` / `html[data-theme="dark"]` 和 CSS custom properties。Light 保留现有配色；Dark 使用暖黑 / 墨灰背景、柔和浅色文字和低饱和鼠尾草 accent。
- 新增共享 `theme.js`，三个页面在 `style.css` 前同步加载并尽早恢复主题。localStorage key 为 `illustration-archive.theme.v1`，仅保存 `light` / `dark`；无有效保存值默认 Light，存储异常不阻断当前页面切换。Gallery / Detail / Inbox 提供“深色模式”入口并更新 `aria-pressed`，Unified Viewer 自动跟随全局主题。
- 人工视觉验收覆盖 Light / Dark、localStorage 持久化，以及 Gallery / Unified Viewer / Viewer Inspector / Detail / Inbox / Metadata Picker。图片、GIF、MP4 本身不加滤镜、不降低亮度。
- 本工作包仅调整颜色主题和切换入口，不改变既有布局规则、Masonry、Viewer、媒体生命周期或业务状态逻辑；没有第三方主题库、system 自动同步、storage event 跨标签页同步、BFCache 专门同步、URL theme 参数或主题切换动画系统。
- 实现文件：`style.css`、`index.html`、`detail.html`、`x-import.html`；新增 `theme.js`、`src/test/js/theme.test.js`。业务 JS 和既有测试 helper 未修改。

**自动化验证（实现阶段）：**`node --test src/test/js/theme.test.js`，7/7 通过；`node --test (rg --files src/test/js -g '*.test.js')`，**115/115 通过，0 failures / skipped**；`git diff --check` 通过。另做源码对照检查，原有 CSS 声明在解析 Light fallback 后与基线一致；此检查与用户确认的人工视觉验收分别记录。

**验收确认与证据边界：**以上人工视觉验收由用户确认。本次仅更新本文，不重新运行 JS / Maven 测试或浏览器验收；未修改代码，未 commit / push。

### V0.5-F06A｜Inbox Animated Preview Proxy

2026-10-04，在 `v0.5-x-animated-media` 上修复 Inbox 的 CDN hotlink 403。F06A 已完成，用户确认实际 Inbox 预览通过，解决浏览器直接访问 `video.twimg.com` 的 403；与 F06 一同封存。以下保留实现阶段的设计和测试记录，真实验收结论来自用户确认。

- 新增 `GET /api/x-import/inbox/{itemId}/media/{mediaKey}/content`。使用既有 `XLikeMediaRepository.findByItemId(itemId)` 的绑定查询，再匹配 mediaKey，必须是数据库中的 `animated_gif`。不存在 item（FK 保证无对应 media rows）、不存在 media 或非 animated_gif 均返回 404；不接受外部 URL 参数，不更新数据或状态。
- 复用 `XAnimatedMediaUrl.parse` 对保存的 source URL 再次校验：HTTPS、精确 `video.twimg.com`、MP4 路径，禁止 userinfo / port / fragment。Java HttpClient 不跟随重定向，Accept 为 video/mp4。只接受 upstream 完整 200 且 Content-Type 为 video/mp4；上游非 200、非 MP4、非法响应长度、网络失败等返回安全的 502，不转发上游错误 body 或 Location。
- service 只返回未消费的 upstream InputStream 与已知长度；controller 直接 `transferTo` servlet 输出流，返回 `video/mp4`，有已知长度才设置 Content-Length，Cache-Control 为 no-store。成功、校验失败或客户端写入失败都会关闭 upstream body；开始流式写入后若连接断开，不保证还能改写已经提交的 HTTP 状态。
- Inbox 卡片及 Inbox Viewer 的 animated_gif 使用本地 proxy URL，photo 保留原 sourceUrl。没有改 Gallery / Detail / Unified Viewer 文件，没有接入 FileStorage / Asset，没有改变 Archive 语义、ordinary video 的 UNSUPPORTED 规则或 schema；无 Range / 206、缓存系统或新依赖。即使浏览器带 Range，请求仍按完整 200 转发，不把 Range 发给 upstream。
- 实现阶段修改文件：新增 `XAnimatedPreviewService.java`、`XAnimatedPreviewController.java`、`XAnimatedPreviewServiceTest.java`、`XAnimatedPreviewControllerTest.java`；修改 `x-import.js`、`x-import.test.js`、`README.md` 和本文。

**验证：**focused Maven 使用 Wrapper / 既有 cache / repo temp 参数及 `'-Dtest=XAnimatedPreviewServiceTest,XAnimatedPreviewControllerTest' test`，8/8 通过；`node --test src/test/js/x-import.test.js`，25/25 通过。最终完整命令：

```powershell
.\mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp' test
node --test (rg --files src/test/js -g '*.test.js')
git diff --check
```

F06A 完成时，Maven **319 tests、0 failures、0 errors、3 skipped，BUILD SUCCESS**；skipped 仍为未启用的两个 MySQL 与 LocalStack 外部测试。JS **108/108 通过**；diff check 通过。测试覆盖 database record lookup、非 animated_gif / 缺失项、URL 安全校验、HTTP / MIME / 长度 / 网络失败、未预读 body、正常与断线关闭、完整 200 / 无 Range 转发、本地 proxy URL 和 photo 回归。HttpClient / Repository 为 mock，MockMvc / JS 为自动化证据；实际 Inbox 播放另由用户真实验收确认通过，不以 mock 测试代替。日志在 ignored `.maven/f06a-focused.log`、`f06a-test.log`、`f06a-js-focused.log`、`f06a-js.log`。首次 Maven sandbox 缓存 JAR 读取失败，提升权限后执行成功。

### V0.5-F06｜X Animated Media Support

2026-10-04，从 `v0.5-viewer-inspector` 创建并切换到 `v0.5-x-animated-media`。F06 已完成并封存，稳定基线提交为 `2e2046a`（`feat: support X animated media`）；不宣告 V0.5 Final Acceptance。

**真实验收与封存确认（截至 2026-10-05）：**用户确认 X `animated_gif` → 合法 MP4 variant → Inbox → Archive → Gallery / Detail / Unified Viewer 全链路已真实验收通过，重启后已归档 MP4 仍正常播放；F06A Inbox preview proxy 已完成并解决 CDN 403。F06A 完成时的最终自动化基线为 Java **319 tests**、JS **108/108**，详细结果见上节。

- Likes 请求增加 `variants,preview_image_url`，与 X 官方 Likes endpoint / Media 字段文档一致。只支持 `photo` 和 `animated_gif`；普通 `video`、unknown、无媒体或无法选择合法 MP4 的动画使整条 Post 为 `UNSUPPORTED`。DTO `photoUrl` 最小泛化为 `sourceUrl`，沿用既有 `source_url` 字段，不保存 poster。
- MP4 variant 必须声明 `content_type=video/mp4`，URL 为 HTTPS、精确 `video.twimg.com` host、无 userinfo / 显式 port / fragment，路径以 `.mp4` 结尾。数值非负 bitrate 最大者优先；缺失或非数值 bitrate 作为 fallback，同 bitrate / fallback 保留 API 数组中首次出现的合法候选。
- 旧记录只在 Sync 再次遇到同一个 Post、stored `UNSUPPORTED` 且新 candidate `PENDING` 时恢复。条件 UPDATE 再次限制状态，持有 item 锁直到页面事务结束；刷新 media rows 与状态处于同一个 `@Transactional` 页面事务。`IMPORTED` / `SKIPPED` 不更新，不增加历史扫描或 backfill。
- photo 下载器仅调整 DTO accessor；独立 `XAnimatedDownloadService` 直接流式保存所选 MP4，不跟随重定向，不使用 ImageIO。Local / S3 Storage 都执行实际读取大小 <= 50 MiB、流式 SHA-256、临时文件清理与失败补偿。下载响应关闭失败后也清理已保存文件；归档持久化失败或 duplicate 沿用整 Post 文件补偿。
- 共用 `Mp4Validation` 按顶层 ISO BMFF box 的 32-bit size / 64-bit extended size 遍历，支持前置 box；检查边界、`ftyp` 最小 payload / brand 对齐和 MP4 brand，不按固定偏移查字符串。不验证 codec / duration / 可解码性。现有 Asset `mime_type/storage_key/file_size/sort_order/sha256` 足够，无新增字段、Asset enum、Flyway migration 或 Maven 依赖。
- JPEG / PNG thumbnail 和本地 GIF 行为保留。MP4 不生成 thumbnail / poster；既有 MIME gate 使 `/thumbnail` 返回 404。删除 MP4 original 时跳过不存在的 thumbnail。手工 multipart 上传入口仍仅接受 JPEG / PNG / GIF。
- Inbox 根据 `mediaType`、Gallery / Detail / Viewer 根据 MIME 使用原生 `<video muted loop autoplay playsinline>`，无 controls。Gallery 卡片 `preload=none`，接近视口才赋 src / play，离开视口 pause；切换 Asset / 替换页面时解除观察并释放视频，丢弃已排队的旧事件。Masonry 在最多 4 个并发 metadata probe 中读取 `videoWidth/videoHeight`，沿用 ratio cache / 超时 / 固定布局规则。Detail / Inbox 重新渲染也释放旧视频。
- Viewer 使用视频 metadata 作为自然尺寸，共用现有 fit / 1:1 / zoom / pan / 导航 / Inspector / Esc / BrowseContext；切换、重试和关闭释放 video，旧 loadedmetadata / error 由 generation 隔离。Inbox 仍无 Inspector。HTTP content controller 保持既有普通 200 Resource + Content-Type / Content-Length；没有实现 Range / 206。

**修改文件范围：**

- 后端 DTO / Repository：`XLikeMedia.java`、`XLikeMediaRepository.java`、`XLikeRepository.java`。
- 后端 Service：`XApiClient.java`、`XLikePersistenceService.java`、`XPhotoDownloadService.java`、`XImportService.java`、`IllustrationDeleteService.java`；新增 `XAnimatedMediaUrl.java`、`XAnimatedDownloadService.java`。
- Storage：`FileStorageService.java`、`S3FileStorage.java`；新增 `Mp4Validation.java`。
- 静态资源：`app.js`、`detail.js`、`x-import.js`、`image-viewer.js`、`style.css`；未改 BrowseContext / Masonry 算法文件。
- Java 测试：`AssetContentControllerTest.java`、`XImportControllerTest.java`、`XLikeRepositoryTest.java`、`IllustrationDeleteServiceTest.java`、`XApiClientTest.java`、`XImportServiceTest.java`、`XLikePersistenceServiceTest.java`、`XPostPersistenceServiceTest.java`；新增 `XAnimatedDownloadServiceTest.java`、`AnimatedStorageContractTest.java`、`XAnimatedPersistenceMySqlTest.java`。
- JS 测试：`detail.test.js`、`gallery.test.js`、`image-viewer.test.js`、`x-import.test.js`、`helpers/browser.js`。文档：`README.md`、本文。

**F06 核心实现阶段自动化验证（F06A 之前）：**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp' verify
node --test (rg --files src/test/js -g '*.test.js')
git diff --check
```

Maven **311 tests、0 failures、0 errors、3 skipped，BUILD SUCCESS**，完成 JAR 打包。跳过的是未启用的 `IllustrationGalleryMySqlTest`、`XAnimatedPersistenceMySqlTest` 和 `S3StorageLocalStackTest`。JS **107/107 通过、0 failures / skipped**；diff check 通过。集中 Java 测试亦运行过 `-Dtest=XApiClientTest,XLikePersistenceServiceTest,XImportServiceTest,AssetContentControllerTest,AnimatedStorageContractTest,XAnimatedDownloadServiceTest,IllustrationDeleteServiceTest,XLikeRepositoryTest`，49/49 通过。最初 sandbox 无法读取 AWS SDK 缓存 JAR，提升权限后正常；未以产品改动绕过环境问题。日志在 ignored `.maven/f06-focused.log`、`f06-verify.log`、`f06-js.log`。

**实现阶段测试证据边界：**Local contract 使用真实临时文件；S3 contract mock `S3ObjectStore`，不等同真实 S3。MP4 fixtures 为容器 header 测试字节，不是可播放的视频；JS 使用模拟 DOM / metadata / intersection，不等同浏览器播放。新增 opt-in MySQL 测试验证 Spring proxy 下 media replacement 失败的状态 / rows 回滚，并验证恢复及 processed 状态保留；该阶段仅编译、未启用执行。启用须提供 `F06_MYSQL_TEST_URL/USERNAME/PASSWORD`；URL 只允许全新 `illustration_archive_f06_test_<digits>` schema，不得已有，完成后删除该专用 schema。

上述核心实现阶段未调用真实 X Sync、未写现有收藏库、未启动浏览器或真实 S3 验收；后续用户已确认本节所述全链路真实验收通过并封存。本次文档更新不重新执行验收，也不将 mock 或未启用的外部测试计为真实环境证据。

### V0.5-B｜Viewer Inspector / Quick Detail Drawer

2026-10-03，分支 `v0.5-viewer-inspector`，在已封存 A 的基线上实现本工作包；实现阶段未 commit / push / merge / tag，最终封存确认见下文。不宣告 V0.5 Final Acceptance。

- Viewer 新增可选 `metadataProvider`。Gallery 仅在第一次点击“作品信息”后请求既有 `GET /api/illustrations/{id}`（`cache: no-store`）；当前 Viewer 生命周期内复用已渲染 metadata / 在途请求，切换同作品 Asset 和开关面板不重复读取。重新打开时重新读取；打开另一作品或关闭 Viewer 后，旧会话的成功 / 失败响应均忽略。失败仅显示信息层的轻量状态与重试，图片加载、切图与关闭仍独立可用。
- Detail 直接提供 `state.detail` 中的已保存数据，不读取编辑表单或 picker 草稿，也不为 Inspector 再拉详情。作者 display name / `@handle`、现有 Tag chips、安全来源链接和保留换行的备注按有值字段显示；没有占位或编辑入口。Inbox 不提供 provider，信息按钮隐藏，未改 Inbox 文件、API 或数据模型。
- 桌面保留原右侧操作区，点击展开 320px 信息层，图片 stage 同步收缩；hover 只加强按钮提示。面板内容独立滚动，底部“查看完整详情 →”固定可用。390px 使用底部面板，844px 高度下保留约 276px 图片 stage；低高度桌面仍使用侧栏。布局修正仅针对有 provider 的 Viewer，未改变 Inbox 的响应式布局。
- 明确关闭按钮、再次点击信息入口及 Esc 均可收起；第一次 Esc 只关闭 Inspector，第二次关闭 Viewer，收起后焦点回到信息入口。Inspector 不写 History。fit 模式按 stage 尺寸重新测量；手动倍率（含原来恰好 fit=100% 的显式 1:1）保留并重新 clamp pan，ResizeObserver 也覆盖响应式尺寸变化。面板与 stage 为兄弟节点，滚轮、点击和文字选择不触发图片拖动；面板内图片快捷键不生效。
- Gallery 的完整详情链接继续携带当前 Asset / BrowseContext，并沿用原来的 Viewer → Detail 导航。Detail 自身打开 Viewer 时，“查看完整详情”只收起 Viewer，回到下面的现有 Detail，保留 Gallery / Inbox 来源与未保存草稿；修改点击仍有携带来源和 Asset 的原生链接。`browse-context.js` 仅为这一同页返回增加分支，未改 Gallery / Inbox 导航或来源快照结构。

**自动化验证：**完整执行 `node --test (rg --files src/test/js -g '*.test.js')`，8 个测试文件，**102/102 通过，0 failures / skipped**；`git diff --check` 通过。新增 9 个用例覆盖打开 / 关闭 / Esc、懒加载与同作品缓存、切作品与重新打开、late success / failure、错误重试、空字段和不安全来源、面板事件隔离、fit / 1:1 / zoom / pan、详情链接 Asset / ctx、Gallery 关闭后的滚动 / focus、Detail 作者 / Tag / 备注 / 来源草稿隔离、同页返回和无 provider 行为；原有 Gallery、Detail、BrowseContext、Inbox、picker 与 Masonry 全部回归通过。本轮纯前端未运行 Maven / Java 测试。

**浏览器验收：**临时 Spring Boot 实例直接服务本轮静态源码，连接现有 29 件收藏库，只进行读取和编辑草稿后取消；关闭 Flyway / X API，并为数据库连接设置会话只读。1280×720、1280×480、390×844 已看图检查；验证两个来源打开 Inspector、同作品切 Asset、不同作品 metadata 更新、两次 Esc、fit 随布局重算、手动 1:1 保留、Gallery → Inspector → Detail → Gallery、Detail Inspector 返回保留来源 / 草稿、Inbox 无信息入口且一次 Esc 关闭。390px 无横向溢出，最后页面 console 无 error / warn。

另在临时浏览器 fixture 中使用真实本地图片与合成 metadata，验证长作者 / 16 个 Tag / 长来源 / 多行长备注的独立滚动，面板滚轮不改变图片 transform / zoom，真实 pointer drag 后开关面板保留 100% 和 pan offset；模拟 provider 失败后重试成功、3 秒延迟旧响应被新 Viewer 丢弃。上述故障和长 metadata 是模拟数据，不等同真实网络故障或真实库已有对应内容；没有修改收藏库、调用同步、保存 / 新建 / 删除。请求次数与乱序边界以 JS 自动化为依据；未做跨浏览器、物理触屏或原生 200% 缩放验收。

**运行与清理：**临时 18087 / 18088 实例已关闭，日常 8080 未重启；验收中换新来源并禁用临时资源缓存，确保加载最终脚本。截图在项目外 `D:/Codex/visualizations/2026/10/03/01a10210-63b9-7c10-8760-741b8c376578/inspector-desktop.jpg` / `inspector-390.jpg`；临时脚本、fixture 和日志在 ignored `.maven/inspector`，不进入待提交 diff。日常实例需从最新源码重启后使用。

**B 封存确认（2026-10-03）：**用户确认最终实机验收通过，授权最终检查正常后以 `feat: add viewer metadata inspector` 提交当前工作包。本轮未修改功能，仅补充本封存记录；完整 JS 再次执行 `node --test (rg --files src/test/js -g '*.test.js')`，102/102 通过、0 failures / skipped，`git diff --check` 通过。确认本工作包仅含 Viewer / Gallery / Detail 接入、样式、JS 测试及本文档；无后端、数据库、migration、Inbox 文件、Search / Metadata Picker 的非预期改动。上方自动化、真实浏览器与模拟 fixture 的证据边界保留。不执行 push / merge / tag，不继续下一项功能或推进 V0.5 Final Acceptance。

### V0.5-A｜Metadata Picker + Gallery Faceted Browse

2026-10-03，按用户指定范围实现；当前分支 `v0.5-metadata-picker`。未 commit / push / merge / tag，不宣告 V0.5 Final Acceptance。

- 扩展既有 `GET /api/authors`、`GET /api/tags`：空或空白 keyword 列出现有项，`limit` 默认 20、允许 1..100，`offset` 默认 0、不得为负；非法参数 HTTP 400。仍返回数组，不新增总数查询或平行 endpoint。作者按 `display_name ASC, id ASC`、Tag 按 `name ASC, id ASC` 排序，查询使用绑定参数；作者 picker 支持带 `@` 的 handle 搜索。没有 migration 或新数据模型；用户追加授权后的 Gallery 多选查询扩展见下方收尾记录。
- 新增小型原生 `metadata-picker.js`，仅负责列表、辅助搜索、加载更多、loading / error 和选中状态。每次展开直接读取现有项；分页按实际服务器返回条数推进 offset，不将置顶的已选项计入 offset。选中项按 ID 去重并始终置顶显示，搜索和分页也保留勾选状态；输入立即使旧请求失效，关闭后忽略在途响应，加载更多失败保留已读列表并可原位重试。
- Detail 当前作者旁可展开选择，显示 `displayName + @handle`；Tag 点击即可添加或取消。已有新建作者 / 标签入口保留，新建后刷新对应打开的列表。草稿、取消编辑和 PATCH 继续由 `detail.js` 管理，picker 不拥有保存逻辑。列表不持久缓存，重新展开和新建刷新均读取最新数据。
- Gallery 桌面薄工具栏增加作者 / Tag 入口，390px 合为一个“筛选”入口；当前多选版本选择后保留弹层，便于连续勾选。`app.js` 继续拥有筛选提交：保留 q 与另一分类 IDs、从第 0 页读取；在途筛选之后提交搜索也保留待应用的 IDs。继续使用 F03 URL / chips / BrowseContext，未增加常驻面板、分类树、次数统计或前端框架。
- 返回回归发现浏览器可能复用旧 API 响应，因此 Gallery / Detail / picker 的可变读取使用 `cache: no-store`；BrowseContext 的 Gallery 分支在 popstate 覆盖快照前消费 metadata refresh 标记，确保返回后读到保存结果。没有改 `image-viewer.js`、Inbox、Masonry 布局或归档 / Storage / 去重逻辑。

**自动化验证：**完整 Java 执行 `./mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp' test`，289 tests、0 failures、0 errors、2 skipped（未启用的外部 LocalStack 与专用 MySQL 测试），`BUILD SUCCESS`。Windows sandbox 读取既有 AWS SDK 缓存 JAR 曾失败，提升权限重跑后成功；没有修改产品逻辑绕过环境错误。完整 JS 使用 `rg --files src/test/js -g '*.test.js'` 枚举七个测试文件，执行 `node --test`，83/83 通过、0 failures / skipped。覆盖同名作者 IDs、Tag toggle / 取消 / PATCH、创建后刷新、失败保存、搜索及加载更多乱序、立即输入失效、分页重试、q + IDs、历史 / 刷新及返回 refresh 标记。`git diff --check` 通过。

**真实 MySQL 验证：**扩展既有 opt-in `IllustrationGalleryMySqlTest`，仍仅允许全新 `illustration_archive_f03_test_<digits>` schema，使用既有 V1–V6。最终版本显式开启测试环境变量，执行上述 Maven 命令加 `'-Dtest=IllustrationGalleryMySqlTest'`，1 test、0 failures / errors / skipped，`BUILD SUCCESS`。验证原有 60 件作品的组合查询、分页 / Assets / Spring read-only 事务，以及 26 位作者的 20 + 6 分页、27 个 Tag 的 20 + 7 分页、稳定排序 / 无重复 IDs、同名作者区分、`@handle` 搜索、零结果和越界 offset。

**真实浏览器验收：**最终资源的临时 Spring Boot 实例绑定本机 18085（专用 MySQL / 合成媒体），检查作者与 Tag 默认展开、辅助搜索、加载更多、已选 Tag 跨搜索保留；同名作者 `@same_a` / `@same_b` 按 ID 选择。实际取消恢复原作者 / Tags；新建作者 / Tag 后列表刷新，保存后冷刷新读取正确 IDs。q + Author + Tag、Back / Forward、刷新、保存后返回更新、Viewer → Detail 冷刷新 → Gallery 均通过；三 Asset 样本从第 3 张进入 Detail 并返回，条件和第 3 张位置均保留。

用户另行明确授权后，18086 连接现有 29 件收藏库，关闭 Flyway，只做读取、筛选、进入编辑并取消，未执行业务写入或 X sync。作者 / Tag 默认列表可直接选择；“雪子”作者条件返回 1 件，deepseek Tag 与 q“二创”、ChatGPT 作者组合返回 5 件；Back / Forward、刷新及 Viewer → Detail → Gallery 保留三项条件。1280×720 / 390×844 下图片继续占主要画面，工具栏约 48 / 44px，窄屏无横向溢出；最后页面无浏览器 error / warn。请求乱序 / 错误分支属于 JS 模拟覆盖，不等同真实网络故障验收；未验证原生 200% 缩放、跨浏览器或物理触屏。

**运行与清理：**临时 18085 / 18086 实例及本轮两份专用测试 schema 已清理，原有 8080 进程未重启。日常实例需从最新源码重启后使用本工作包。截图 / 合成媒体留在项目外，启动脚本 / 日志位于 ignored `.maven/picker`，不进入待提交 diff。

**A 收尾增强｜本地收藏 / 置顶（2026-10-03）：**收藏逻辑统一位于 `metadata-picker.js`，每行选择按钮旁独立提供 `☆ / ★`；收藏与选中勾号分别显示，点击星号不调用选择回调、不改变 Detail 草稿或 Gallery 条件。收藏项置于顶部“收藏”区域，下方保留正常全部 / 搜索列表，选中项仍可见；Author 与 Tag 分别用 `illustration-archive.metadata-favorites.v1.authors` / `.tags` 的 localStorage ID 数组持久化，Gallery / Detail 共用，每次展开重新读取，兼容返回缓存页面后另一页面修改收藏。

展开时仅在存在收藏 IDs 的情况下，使用既有列表 API 的 `limit=100 / offset` 分页核验；全部找到即停止，未找到则读至末页，成功完成后忽略并清理 stale IDs。收藏核验与正常 20 项分页、搜索请求各自独立，不将收藏条数计入普通 offset；搜索 / 加载更多保留收藏状态，关闭后忽略在途核验。失败读取不清理保存的 IDs；localStorage 不可用时保留当前页面会话内操作。没有改 `detail.js` / `app.js`、Java、API、数据库或 migration，没有最近使用、统计排序、管理页、跨设备同步或新依赖。

**增强验证：**执行 `node --test src/test/js/metadata-picker.test.js src/test/js/detail.test.js src/test/js/gallery.test.js`，42/42 通过；补上缓存页面恢复用例后，执行 `node --test (rg --files src/test/js -g '*.test.js')`，89/89 通过、0 failures / skipped；`git diff --check` 通过。新增 6 个用例覆盖收藏 / 取消与选择互不干扰、收藏区位置、Author / Tag 隔离、同类 picker 共用、跨页面 localStorage 恢复、超过 100 项的收藏核验、stale IDs、搜索 / 加载更多、失败 / 关闭核验、损坏或不可用存储及缓存页面重新展开；这些故障 / stale / 大列表属于模拟 JS 覆盖。本轮纯前端增强未重跑 Java 测试，上方 A 的 Java / MySQL 验证保留。

真实浏览器使用本机 18086 的只读收藏库实例（Flyway 关闭）：收藏 hasei 作者 / 百合 Tag 不改变 Gallery 条件；搜索雪子仍保留 hasei 收藏，刷新后 Detail 恢复同一作者 / Tag 收藏；取消作者收藏保留当前作者，取消编辑不撤销本地收藏。另从已加载 Gallery 进入 Detail 新增收藏并返回，重新展开能读到新收藏；从收藏区选择作者后，取消收藏仍保留作者筛选。1280×720 / 390×844 截图已检查，窄屏无横向溢出，星号按钮 44×44px，最后控制台无 error / warn。真实库没有保存 / PATCH / 删除 / 新建操作；测试用本地收藏已通过 UI 取消，临时实例已关闭，原有 8080 未重启。截图留在项目外 `picker-favorites-desktop.png` / `picker-favorites-390.png`。未 commit / push / merge / tag，仍属于当前 A 工作包。

**A 收尾修正｜X Author 身份复用与 Gallery 多选（2026-10-03）：**先审查真实 Author、Illustration 关联、Inbox 身份快照与创建链路，再实现本工作包内修正。X 导入仍以稳定 `x_user_id` 优先查询；找不到时，只尝试认领唯一、同 handle（去 `@`、忽略大小写）、`x_user_id IS NULL` 的旧记录。存在其他稳定身份快照、多个候选或无法用同一 X 身份快照证明的关联作品时不认领；displayName 不参与身份判断。不确定记录保留。认领、作品 / Asset 插入及 Inbox 状态仍处于原有单 Post 事务，失败一起回滚。唯一键竞争时按稳定 ID 重新读取。手工新建同 handle 且只有一个候选时返回已有作者；多候选要求选择已有项，未提供 handle 的同名作者仍可分别创建。

真实库只有一组明确重复：旧 Author **1**（`@kudo_eru`、无 X ID、无关联作品）与 Author **15**（`kudo_eru`、X ID `1201513602835828737`）。16 条同 handle Inbox 快照均指向这个稳定身份；canonical 15 已有关联作品 36，来源 Post 与 Inbox 相符，未发现冲突身份。既有 V4 和真实数据库已经具有 `uk_author_x_user_id` 唯一约束，允许多个 NULL、禁止重复非空 ID；因此不新增 migration。按用户授权执行一次明确 pair 的事务合并：预先备份两个 Author 原记录及关联 / 数量，更新旧作者作品引用后删除旧 Author；本次实际迁移作品 **0**、删除重复作者 **1**，收藏库作品保持 **29**、Assets 数量不变，其他旧作者不动。脚本默认 dry-run、显式 `-Apply` 才写入，审查条件变化即停止；重新运行返回 `Already merged; no writes.`。备份保留在 ignored `.maven/picker/author-1-15-before.json`，不是启动任务或自动迁移。

Gallery Author / Tag 使用原生 checkbox 多选，`☆ / ★` 是 label 外的独立按钮，仍共用原有本地收藏。每分类内部 OR，q / Author 集合 / Tag 集合之间 AND，空集合不限制。API / URL 采用重复 `authorId` / `tagId` 参数；单项 URL 和旧 BrowseContext 标量快照仍有效。IDs 校验为正数，每分类最多 100 个，去重并稳定排序；未知 ID 不产生错误。count 与 page 共用同一 predicate：Author 用绑定的 `IN`，Tag 用相关 `EXISTS ... IN`，不引入 JOIN 重复作品。响应增加 IDs / summaries 数组，同时保留单项情况下原有 scalar 字段。轻量 chips 按项显示、可单项 / 全部清除；请求期间连续勾选和提交 q 使用待应用集合，旧响应不能覆盖最新条件。BrowseContext 存完整集合，分页、历史、刷新及 Detail 返回保留全部条件。异步列表重绘后恢复原勾选控件焦点。Detail Author 继续单选，Tag 继续多选；没有改 Author 为多对多，没有修改 Viewer / Inbox 或引入新依赖。

**本轮自动化验证：**使用上方 Maven Wrapper / 缓存 / temp 参数运行完整 `test`，普通运行 296 tests、0 failures / errors、2 skipped；设置专用 `F03_MYSQL_TEST_*` 环境后再次运行同一完整命令，**296 tests、0 failures / errors、1 skipped（LocalStack）**，`BUILD SUCCESS`。扩展真实 MySQL opt-in 验证 60 件 fixture 的多作者 OR（48 件）、多 Tag OR（40 件）、q + 两分类 AND（32 件）、未知 / 重复 IDs、count / page 一致、分页无重复；验证旧作者认领、身份冲突 / 歧义 / 未证明作品拒绝认领及现有唯一约束。使用真实 Spring 事务代理制造 Asset 插入后的失败，验证旧作者认领、Illustration / Asset / Inbox 写入全部回滚。未调用真实 X sync。全部 JS 执行 `node --test (rg --files src/test/js -g '*.test.js')`，**93/93 通过，0 failures / skipped**；新增覆盖多选组合、独立收藏、原生 checkbox / 焦点、连续请求乱序、完整历史与 Viewer / Detail 返回，原有收藏 / Detail 测试也通过。`git diff --check` 通过。

**本轮真实浏览器验收：**18085 专用 MySQL fixture 验证同名作者两项 OR、两个 Tag 与 q 合并得到 32 件、24 + 8 分页、单项 / 全部 chips 清除、Back / Forward / 冷刷新，以及 Viewer → Detail → 冷刷新 → Gallery 返回完整集合和页码。Detail 更换作者只产生一个草稿作者，取消恢复原作者；收藏区在 Gallery / Detail 共享，点星号不改变筛选。390×844 合并入口中可取消 / 搜索再添加条件，四项 checkbox 恢复，无横向溢出。18086 真实库关闭 Flyway，浏览器仅查询：kudo_eru 返回唯一 ID 15；q“二创” + Author 4 / 5 + Tag 1 / 4 返回 5 件，390px 恢复全部勾选与 chips，Masonry 保持图片主导。两个页面最后 console 无 error / warn。截图位于项目外 `picker-multi-desktop.png` / `picker-multi-390.png`。测试收藏通过 UI 取消；临时实例与本轮专用 schema 已清理，日常 8080 未重启。真实库唯一业务写入为上述已审查、已备份的 Author 1 → 15 合并；未做其他删除、PATCH、新建或 X sync。仍未 commit / push / merge / tag，不推进 V0.5 Final Acceptance。

**A 封存确认（2026-10-03）：**用户确认最终实机验收通过，授权最终检查正常后以 `feat: improve metadata picking and faceted browsing` 提交当前工作包。本轮不再修改功能。完整 Java 使用上方 Maven Wrapper / 缓存 / temp 参数执行 `test`，296 tests、0 failures、0 errors、2 skipped（未启用的 LocalStack 与专用 MySQL opt-in 测试），`BUILD SUCCESS`；此前真实 MySQL 与浏览器验收证据保留。全部七个 JS 测试文件通过 `node --test (rg --files src/test/js -g '*.test.js')` 执行，93/93 通过、0 failures / skipped；`git diff --check` 通过。确认重复 Author 合并、稳定 X 身份复用、Gallery 多选及本地收藏均已记录；无新增 / 修改 migration、Storage、Viewer、Inbox 或非预期 Archive 改动。`XPostPersistenceService` 仅包含已授权的作者身份复用修正，保留原有事务与归档写入逻辑；BrowseContext 仅支持完整筛选集合及原有 metadata 返回刷新。A 按上述范围封存，不推进下一项功能或 V0.5 Final Acceptance，不 merge / tag / push。

### V0.5-F03｜全库搜索 + Tag / Author 导航

2026-10-03，按用户确认的 Fast Lane 范围实现；尚未 commit / push / merge / tag，不宣告 V0.5 Final Acceptance。

- 扩展现有 `GET /api/illustrations?page=0&size=24&q=...&authorId=...&tagId=...`，不新增查询 endpoint。`q` 去首尾空白、空白等同无关键词，最多 200 个 Unicode code points；对标题、备注、作者显示名 / X handle（可带 `@`）、Tag 名进行整段包含匹配。字段之间 OR，关键词与精确 Author / 单 Tag 条件之间 AND。不做分词、多 Tag、高级语法、相关度排序或新排序。
- Author / Tag 使用正整数 ID 精确筛选；非法参数 HTTP 400，不存在的 ID 保留条件并返回零结果。返回原分页 / items 契约，附加 `filters`（规范化关键词、IDs 与可为空的 Author / Tag 摘要），使零结果也能显示条件名称。
- JdbcTemplate 的 count / page 复用同一 WHERE 与绑定参数顺序；Tag 搜索和精确筛选使用相关 `EXISTS`，不将一对多 Tag JOIN 到外层。保留 `created_at DESC, id DESC`、封面规则与当前页 IDs 的批量 Asset 读取；`%` / `_` / `!` 按字面搜索，SQL 使用参数绑定。Service 加 `@Transactional(readOnly = true)`，没有显式隔离级别或事务配置扩张。本地 MySQL 8.0.42 默认 `REPEATABLE-READ` 已实测；其它环境沿用其配置，本轮没有证明跨请求并发写入期间的 OFFSET 稳定性。
- Gallery 保留薄工具栏与 Masonry：搜索表单嵌在原工具栏，无条件时隐藏 chips；有条件显示 q / Author / Tag 轻量 chips，支持单项 / 全部清除。提交与清除从第 0 页开始；搜索保留精确筛选。Detail 的有效 Author / Tag 转为普通 Gallery 链接，从第 0 页展示该作者 / 标签全部作品。
- Gallery 的 `q / authorId / tagId / page` 是 URL 中的查询状态。用户操作成功读取并准备布局后才提交 URL、总数和卡片；过期请求不能覆盖新条件，失败可重试。Back / Forward 已切换地址时隐藏旧结果并锁住分页，防止把旧作品标为新条件。输入中的下一份搜索草稿不会被在途响应覆盖。
- 扩展现有 BrowseContext / `ctx` / `nav`：`sourceUrl()` 完整保留上述四项，查询 / 页码变化创建不同快照，错误条件的 ctx 不恢复旧 Asset / Viewer / 位置；保留当前 Asset、Detail 返回与刷新恢复。Gallery 冷刷新读取相同查询 / 页码下最新 `pagehide` 位置，避免较旧 History 快照或初次空 DOM capture 覆盖位置。不新增 `returnTo` 或第二套返回状态；实测既有机制足以完成本轮链路。
- 未改 Archive / Storage / 去重、Flyway migration、Inbox 操作、`image-viewer.js` 或 `masonry-layout.js`；没有新增框架、搜索引擎、Collection 或 AI。

**自动化验证：**完整 Java 执行 `./mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp' test`（开启下述专用 MySQL 测试环境变量），284 tests、0 failures、0 errors、1 skipped（外部 LocalStack），`BUILD SUCCESS`。Windows sandbox 读取既有 AWS SDK 缓存 JAR 曾失败，允许的提升权限重跑后成功；没有为此修改产品逻辑。完整 JS 执行 `node --test src/test/js/gallery.test.js src/test/js/detail.test.js src/test/js/masonry-layout.test.js src/test/js/image-viewer.test.js src/test/js/browse-context.test.js src/test/js/x-import.test.js`，74/74 通过、0 failures / skipped。`git diff --check` 通过。

**真实 MySQL 验证：**新增默认禁用的 `IllustrationGalleryMySqlTest`，仅在 `F03_MYSQL_TEST_URL` 指向全新 `illustration_archive_f03_test_<digits>` schema 时启用（另需 `F03_MYSQL_TEST_USERNAME` / `F03_MYSQL_TEST_PASSWORD`）。使用 `CREATE DATABASE`，已存在即失败；应用既有 Flyway V1–V6，创建 60 件作品、重叠 Tag、同名不同 ID 作者、同时间作品、库末备注和三 Asset 样本。经过真实 Spring 事务代理断言 read-only 事务有效，验证 count / 逐页 IDs / Assets 一致，3 页完整有序、无重复；组合条件、中文 / handle / 字面符号、零结果、未知 IDs 和越界页均通过。默认不写媒体；可选 `F03_MYSQL_TEST_MEDIA_ROOT` 在明确指定的测试目录生成合成 PNG / thumbnail，供浏览器验收。测试保留专用 schema，便于后续浏览器验证；重跑须新 schema 或显式清理自己创建的测试库。

**真实浏览器验收：**最新 Java / 静态资源临时实例绑定本机 18083（上述 MySQL fixture）与 18084（现有收藏库只读查询，Flyway 关闭），没有调用 X sync 或真实业务写入。现有收藏库 29 件，默认分页 24 + 5；标题“蓝白双星”的 5 件全部不在默认第一页，搜索仍全部找回。真实作者“雪子”搜索与 Detail 作者链接返回 1 件；Detail 的 deepseek Tag 导航返回 5 件，叠加仅出现在备注中的“二创”仍匹配 5 件。1280×720 / 390×844 无横向溢出，工具栏约 48 / 44px；无条件不显示 chips，组合条件能单项清除并正确重置页码。

60 件 MySQL 样本的 q + Author + Tag 得到 18 件；清 Author 后 30 件，清 Tag 后 q 得到 40 件，第二页 16 件。Back / Forward、刷新保留条件 / 页码；Gallery → Viewer → Detail 冷刷新 → 返回完整保留三项条件，无 `returnTo`。多 Asset 当前项、Viewer 起始项 / 切换 / 关闭焦点 / 刷新正常；真实库窄屏冷刷新前后 `illustration:30` top -100.8125px、scrollY 993.3333129882812 一致。零结果、不存在 IDs、非法 ID 提示及条件页码 99 → 0 的回退也通过。最终页面没有浏览器 error / warn。

**验收边界与运行状态：**没有验证原生 200% 缩放、跨浏览器或物理触屏；模拟失败 / 乱序覆盖与真实浏览器 / MySQL 证据分别记录。验收临时实例和本轮创建的专用测试 schema 已清理，未重启原有 8080 Java 进程；日常实例需从最新源码重启并刷新缓存后才能使用完整 F03。验收截图 / 合成媒体留在项目外，临时启动脚本 / 日志位于 ignored `.maven/f03`，不进入待提交 diff。

**F03 收尾确认（2026-10-03）：**用户确认实机验收通过，授权最终检查正常后以 `feat: add gallery search and metadata navigation` 提交本工作包。本轮未再修改功能。完整 Java 使用上述 Maven Wrapper / 缓存 / temp 参数执行 `test`，284 tests、0 failures、0 errors、2 skipped（未启用的外部 LocalStack 与专用 MySQL opt-in 测试），`BUILD SUCCESS`；此前真实 MySQL 测试已通过，保留上方证据。使用 `rg --files src/test/js` 枚举全部六个 `*.test.js`，执行 `node --test`，74/74 通过、0 failures / skipped；`git diff --check` 通过。核对全部 19 个待提交文件，仅含 F03 查询 / 导航、相关测试与本记录；Archive / Storage / 去重 / Flyway schema 无改动。只收尾 F03，不推进下一项、V0.5 Final Acceptance、merge 或 tag。

### V0.5-F02｜Inbox Continuous Processing Flow

2026-10-03，按确认后的 Fast Lane 范围实现。保留每页 24 条、PENDING 查询与排序、当前加载页明确选中的 IDs、按 Post 独立归档结果、Duplicate 不自动 Skip，以及原有有效页回退。没有改 Java、API、数据库、事务、文件补偿或去重；没有筛选、Search、Author/Tag 导航、无限滚动、AI 或新框架。

- 复用 F01 Viewer：仍从点击的 `mediaKey` 打开，切图标记当前缩略图，关闭后焦点回到最后浏览的媒体入口，保留勾选和位置。Viewer 本体未修改。
- 整卡选中反馈、44px 选择标签和已选数量；卡片背景可切换选择，但图片、链接、按钮、结果控件、可选择文字、拖动、滚动和 pointer cancellation 不触发选择。顶部 sticky 栏显示当前页操作；窄屏使用短标签，栏高约 87px，桌面约 65px。
- 写操作及后续 GET 期间保留旧列表。新数据成功读取后才替换卡片、更新页码与总数并清空选择；新补入项不自动勾选。Inbox 在提交新 DOM 前读取仍可见的 item ID 和偏移，尊重请求期间的滚动；原 item 消失时选后邻、前邻或首个新项，越界沿用最后有效页回退。
- BrowseContext 仅扩展 `locate(node, top = 0, scrollY = 0)` 和可选 `onViewerClose` 回调。位置快照、旧页顺序、附近 item 规则、Archive 失败原因与选择状态均留在 `x-import.js`，不新增共享状态管理。
- 成功摘要显示 6 秒后消失，完整最近结果可展开；空摘要不占布局，出现/消失不挤动卡片。Archive 的 FAILED/DUPLICATE 原因按 item ID 放在对应卡片中，页面生命周期内保留，支持选择此项和仅选择本页 FAILED。Skip 仅显示真实汇总，不根据单个 ID 或当页消失推断结果/原因。
- 写入结果未知或写入完成后列表更新失败时保留画面，分别报告结果与加载错误，锁住重复写入，提供重新加载；不会自动重放 Archive/Skip。

**自动化验证：**执行 `node --test src/test/js/gallery.test.js src/test/js/detail.test.js src/test/js/masonry-layout.test.js src/test/js/image-viewer.test.js src/test/js/browse-context.test.js src/test/js/x-import.test.js`，61/61 通过（0 failures、0 skipped）；`git diff --check` 通过。新增 15 个行为用例覆盖选择排除、触屏事件模拟、Viewer 当前媒体与焦点、写操作/GET 保留 DOM、未知结果、失败重新选择、Skip 汇总、锚点移除后的附近恢复、失败翻页和过期响应。纯前端范围，未运行 Maven/Java 测试；模拟 DOM 不证明真实触屏或数据库事务。

**真实环境验证：**先以最新源码的本地预览连接运行中的真实后端，真实队列为 326 条 PENDING。仅操作用户指定的两条 Post：`2104577976532910511`（Inbox 435）单项 Archive 返回 SUCCESS，生成 Illustration 33 / Asset 35；`2104641917325820377`（Inbox 434）单项 Skip 返回实际 1/1。队列降到 324，无其它真实业务写入，也未调用 X sync。新作品 Detail 原图实际解码为 1152×1225，原图/缩略图 HEAD 均为 HTTP 200。真实第二页 Select Page 仅勾选 24 项、翻页清空；真实三图 Post 从第二张打开（解码宽 936px），切到第三张关闭后当前媒体与焦点一致。Gallery → Viewer → Detail → Viewer 的共享链路正常，未编辑/删除作品。

随后备份并同步本次四个静态资源到现有 `target/classes/static`，四个正式 HTTP 响应均与源文件相同，没有重启 Java。旧 `localhost` 浏览器来源仍命中旧缓存，因此用新的 `127.0.0.1:8080` 来源复核最新正式资源：1280×720、1920×1080、390×844 均无横向溢出，栏高约 65/65/87px；选择、Viewer 开关、当前媒体、焦点和滚动时操作栏可达性正常。

**隔离模拟 API 的真实浏览器验证：**混合 SUCCESS/DUPLICATE/FAILED、卡片原因展开、仅重选 FAILED 后提交单个 ID、部分 Skip 仅显示汇总、响应丢失不自动重放、慢写操作/GET 保留 24 个旧卡片、写入成功后 GET 503 与手动恢复、最后页回退和空队列 `Page 0 / 0` 均通过。390px 原生鼠标拖选文字和滚动未勾选卡片；提示占位修正后，同一锚点处理前 top -18.9375px、处理后及摘要消失后 -18.6667px。模拟结果不视为真实混合写入或事务/文件补偿验收。

**边界：**未验证物理触屏手指滚动、跨浏览器或原生 200% 缩放；内嵌浏览器缩放快捷键未生效，640×360 等效视口无溢出不代替原生缩放。使用已打开的旧页面时需强制刷新缓存。上述证据不宣告 V0.5 Final Acceptance；此前实现与浏览器验收阶段未 commit/push/merge/tag。

**收尾确认：**2026-10-03，用户确认 F02 实机验收通过，授权最终检查通过后以 `feat: improve inbox continuous processing flow` 提交本工作包。完整六项 JS 测试集再次执行，61/61 通过（0 failures、0 skipped）。不推进下一项功能或 V0.5 Final Acceptance，不 merge/tag。

### V0.5-F05A｜Gallery Browse Mode + Detail Presentation Polish

2026-10-02，按确认后的 03「原幅 Masonry」实施。Gallery 仍以 Illustration 为单位，每页 24 件并保留明确分页；没有无限追加、搜索、Author / Tag 导航或新管理页面。后端、数据库、Inbox、`image-viewer.js` 与 `browse-context.js` 均未修改。

- Gallery 使用薄工具栏，现有导入表单默认收起，展开 / 收起保留节点与输入。图片没有常驻标题、缺失作者提示或卡片文字区；有效作者 / 标题在 hover / keyboard focus 时出现，多图保留叠图图标与总数，并在 hover / focus 时显示轻量预览箭头。点击图片继续通过现有 BrowseContext 打开 Viewer，修改键点击保留 Detail 链接。
- 新增无依赖 `masonry-layout.js`：等宽列、最短列放置、同高靠左，DOM 维持接口顺序；高度按原比例计算，桌面最多 8 列，gutter 8px，窄屏两列 / 一列与 6px gutter。Gallery / Detail 的纸白与鼠尾草样式限定在页面作用域，Viewer 样式段保持原样。
- 当前页封面尺寸采用 4 路并发准备，整个准备阶段最多等待 6 秒；最多缓存 128 个预览比例。尺寸确定后一次提交布局，再调用既有 `browse.ready()`。失败 / 超时保留固定占位，迟到的图片不挤动邻图；Viewer 切换异比例 Asset 时，在固定封面格子内 contain。翻页准备期间保留原页，失败保留 URL、作品与滚动位置，重试成功才换页。
- Detail 保留有序原图与原编辑表单：作者 24px，handle 14px，静态鼠尾草 Tag chips；空标题 / 标签 / 来源 / 备注隐藏，缺作者仅显示弱提示。来源与备注降级，编辑 / 删除移到信息末尾并弱化；没有改变保存、删除或来源 URL 校验流程。

**自动化验证：**执行 `node --test src/test/js/gallery.test.js src/test/js/detail.test.js src/test/js/masonry-layout.test.js src/test/js/image-viewer.test.js src/test/js/browse-context.test.js src/test/js/x-import.test.js`，44/44 通过。包括最短列几何、比例 / 长图 / 断点、失败 / 超时 / 乱序尺寸准备、换页失败保留原页、多图切换固定坐标、Detail 空字段及安全来源，以及既有 Viewer / BrowseContext / Inbox 回归。`git diff --check` 通过。纯前端范围，未运行 Maven。

**浏览器验证：**最终源文件通过本地只读预览代理连接真实应用；未重启 Spring Boot。1280×720、1920×1080、390×844 的 Gallery 均无横向溢出，分别为 6 / 8 / 2 列；当前 23 件真实作品首屏完整可见 13 / 23 / 8 件。Detail 在上述桌面与窄屏中检查了标题、作者、handle、chips、来源、备注和弱操作。真实作品的坐标点击开关 Viewer 后，锚点 top 118.90625px、scrollY 844 均保持不变，焦点回到图片入口；Gallery 冷刷新保持同一位置。另一次实际坐标链路完成 Detail Back / Forward / Detail 刷新 / 返回图库，锚点与 scrollY 同样保持；Detail 临时未保存标题在 Viewer 开关后仍存在，随后取消，没有提交编辑。

真实库不足两页，多页、失败翻页重试、页码越界、横 / 竖 / 长图、透明 PNG、GIF、不同宽高多图以及图片失败使用同一正式前端源文件和只读模拟 API 验证，不能视为真实多页验收。现有 bfcache 回归通过；浏览器返回链路未单独证明缓存命中与未命中两条路径。真实库预览未出现 JS error / warn；模拟的 HTTP 404 / 503 属预期。

**尚未验收：**原生浏览器 200% 缩放（内嵌浏览器快捷键未生效，Edge 工具连接不可用）；已补做 640×360 等效视口无横向溢出检查，但不代替原生缩放。仍需更新后的 Spring Boot 静态资源部署、真实多页库与跨浏览器检查。本轮未执行业务写入，也未 commit / push / merge / tag；上述实现与证据不宣告 V0.5 Final Acceptance。

**F05A 实机验收回归修复（2026-10-02）：**恢复旧 Gallery 的多图左右循环预览。单图不创建控件；多图箭头作为图片链接的兄弟按钮，仅在 hover / focus 时出现，点击阻止默认行为与冒泡，并使用现有 `browse.remember()` 同步当前 Asset / Detail href。图片主体仍从当前预览打开 Viewer。没有改变封面决定的格子、分页或 F01 契约；异比例图片使用 contain 与纸白留边。

本次修复只新增修改 `app.js`、Gallery scoped CSS、`gallery.test.js` 和本记录，保留此前 F05A 未提交改动。执行 `node --test src/test/js/gallery.test.js src/test/js/image-viewer.test.js src/test/js/browse-context.test.js src/test/js/masonry-layout.test.js`，28/28 通过；`git diff --check` 通过。新增测试覆盖单 / 多图控件、排序与首尾循环、箭头不打开 Viewer / 不新增历史项、当前 Asset 同步、Viewer 起始项 / 关闭 / 刷新与固定封面格子。

只读真实库源码预览中，1280px 鼠标与 Enter、390px Space 均能轮换真实三图作品 28；点击图片从当前 Asset 29 / 30 打开 Viewer。390px 关闭前后 top 283.5208435058594px / scrollY 90 不变，预览与焦点保留；刷新恢复 Asset 29。使用同一源码的异比例模拟三图从 600×900 切到 1200×400，格子 198.8333×298.25px 与所有邻图坐标不变，未打开 Viewer；随后 Viewer → Detail → 返回 Gallery 保留 Asset 1105。本次未重启应用或执行业务写入，没有 commit / push / merge / tag；此前尚未验收项保持上述边界。

**F05A 收尾验收（2026-10-02）：**用户已确认 F05A 完成真实浏览器验收，恢复多图 Gallery 轮换后也未发现明显问题。该用户实机验收与上方实现阶段的自动化 / 工具验证分别记录，不将此前未单项验证的细节改写为自动测试结论。最终提交前枚举 `src/test/js` 的全部 6 个 `*.test.js` 并执行 `node --test`，46/46 通过（0 failures、0 skipped）；`git diff --check` 通过。全部 11 个待提交文件均属于 F05A Gallery / Detail、多图预览回归修复及其测试 / 状态文档；oil-ui、visualizations、preview server 等项目外产物未进入仓库。F01 两个脚本、后端、数据库与 Inbox 均无改动。本轮只收尾 F05A，不推进其他 V0.5 工作包或宣告版本发布。

### V0.5-F1｜Unified Image Viewer

2026-10-02，统一 Viewer 已实现。Gallery、Inbox、Detail 共用原生 JavaScript 与 `<dialog>`，不增加框架、依赖、后端 API、数据库字段或 migration。V0.4 的同步、归档、去重、事务及文件补偿逻辑保持原实现；本轮没有 commit、push 或 tag。

- Gallery 从当前卡片 Asset 打开 Viewer，切图同步卡片；文字入口继续进入 Detail。Inbox 从被点击的媒体打开，弹层开关保留勾选。Detail 保留有序纵向原图及后续图片 lazy loading，点击任意原图打开 Viewer，不重置编辑表单。
- Viewer 只浏览当前 Illustration/Post，首尾停止。支持适应窗口、1:1、1.25 倍步进缩放、原尺寸 4 倍上限、拖动和弹层内快捷键；切图、重新打开及刷新后重置为适应窗口。只加载当前大图，过期 load/error 回调不会覆盖新图片；GIF 沿用普通 `<img>`。
- 浏览状态由 `browse-context.js` 集中管理，以 Asset ID/mediaKey 保存当前项；页码写入 URL，History API 处理 Back/Forward，sessionStorage 保存标签页上下文。刷新重新读取数据后恢复有效 Viewer；进入 Detail 前移除本次弹层历史项，返回列表时恢复页码、锚点与当前图片。Detail 切图同步来源 Gallery；缓存恢复与重新加载均支持。项目消失、页码越界或存储不可用时合理回退。
- Viewer 不执行 Import、Skip、编辑或删除；既有页面动作与 Inbox 可见页选择规则保持。Detail 保存/删除成功只新增来源列表失效标记与返回处理，不改变其后端操作。

**自动化验证：**执行 `node --test src/test/js/gallery.test.js src/test/js/x-import.test.js src/test/js/detail.test.js src/test/js/image-viewer.test.js src/test/js/browse-context.test.js`，33/33 通过。覆盖现有 Inbox 同步/批量结果/分页刷新、Gallery 卡片循环预览、Detail 纵向原图与 lazy loading，以及 Viewer 的指定图片打开、失败重试、缩放拖动边界、过期回调、Back/Forward、刷新恢复、来源返回、状态失效及未保存表单保留。模拟 DOM 不替代真实浏览器。`git diff --check` 通过；本轮纯前端改动，未运行 Maven 或完整 Java 测试。

**真实浏览器验证：**使用本地源文件预览服务测试最终静态资源，没有重启现有 Spring Boot。预览服务仅允许 GET：

- 使用内存模拟 API 和合成 SVG 验证 Gallery 第 3 页、指定第 2 张打开、首尾停止、1:1 与长图拖动、刷新恢复、Back/Forward、Detail 指定 Asset 定位、返回列表及最后图片同步。可见卡片开关弹层前后锚点 top 均为 250px。
- 模拟 Inbox 验证放大第 2 张后保留勾选和 Import 按钮状态，失效图片反馈、重试、Esc 关闭；390px 窄屏无横向溢出，操作按钮正常换行。
- 通过只读 HTTP 代理连接本机真实应用，验证已有三 Asset 作品从当前图片打开，原图成功解码（1304×2048），Detail Viewer 可用且开关后保留临时、未保存的标题输入。真实 Inbox 的现有 X CDN 图片在 Viewer 中成功解码（1128×1199）。没有提交编辑。验证期间未发现 JavaScript 运行错误；模拟失效媒体的 HTTP 404 属预期。

**尚未验收：**更新后的 Spring Boot 静态资源正式部署、真实触屏单指拖动、跨浏览器兼容，以及可丢弃样本上的真实 Import/Skip/编辑保存/删除回归。上述写操作本轮未执行，也未调用真实 X sync。本工作包已有实现及上述验证证据，尚不代表 V0.5 Final Acceptance。

### V0.4｜工程化与发布记录

Release status:

**V0.3 已正式封存，`v0.3.0` tag 已创建并 push。**现有代码实现 X Likes 手动同步、Import Inbox、受支持照片的单图/多图归档，以及 Gallery/Detail 多 Asset 浏览。下方阶段记录保留各自的自动化测试与真实环境验收边界；正式封存不表示所有边界都经过真实 X API 验收。

V0.4-A1 Runtime Configuration Externalization 已完成。数据库连接、存储根目录及 X Token 原本已通过 Spring Boot 配置占位符从环境变量或忽略的本地文件注入；A1 核对配置并完善本地启动说明，没有改变业务、Schema、事务或文件归档语义。完整 Java 测试 259/259、现有 JS 测试 11/11 通过。Codex 实现后，人工真实验收已通过：IDEA 启动、MySQL/Flyway、Gallery、Inbox、Detail、原图和缩略图读取正常，并成功执行一次真实 X recent sync。

V0.4-A2 Docker & Docker Compose 已完成真实人工验收。已添加多阶段 Dockerfile、application + MySQL Compose、数据库及图片命名 volume 和启动说明。实现阶段完整 Java 测试 259/259、现有 JS 测试 11/11 通过，本地 Maven 跳过测试打包成功；之后实际 Docker image build 与 `docker compose up -d` 成功。MySQL 8.4.11 Container 通过 healthcheck，Spring Boot application Container 成功启动并通过 Compose 网络连接 `db:3306`，Flyway 在全新 Docker MySQL 的 empty schema 上成功执行 V1-V5。Gallery / Detail 可访问，实际导入一张图片成功。

持久化人工验收中，`docker compose down` 删除 app/db Containers 与 network，保留两个 volume；再次 `docker compose up -d` 创建新 Containers 后，数据库中的 Illustration 记录仍存在，Gallery 图片与缩略图仍正常加载。本次记录未确认重建后 Detail 原图的读取结果。A2 验收时存储抽象等后续工作包尚未开始。

V0.4-A3 GitHub Actions CI 已完成，并已通过 GitHub Actions Linux runner 远程验收。单一 Linux job 使用 Java 17 和项目 Maven Wrapper 执行 `verify`（含完整 Java 测试），运行现有 3 个 JS 测试文件，并在两类测试成功后执行 Docker image build。CI 不启动 Compose/MySQL 或应用，不要求真实 X Token、本地配置、存储目录或 GitHub Secrets；X API 保持默认关闭。A3 实现阶段本地 `verify` 通过 259/259 个 Java 测试，Node 通过 11/11 个 JS 测试；本地 Windows 首次运行遇到临时目录访问限制，改用仓库内测试临时目录后通过。本地 Docker daemon 当时未能连接；Docker image build 已由 GitHub Actions Linux runner 远程验收。

V0.4-B1 Storage Abstraction 已实现。`FileStorage` 以文件名和 `InputStream` 保存原图，并提供按 `storage_key` 读取/删除及历史 SHA-256 计算；HTTP `MultipartFile` 由 `IllustrationImportService` 在存储边界前适配。`ThumbnailStorage` 提供现有 thumbnail 生成/读取/删除能力。业务服务仅依赖接口；现有 `FileStorageService`、`ThumbnailService` 仍为 Local filesystem 实现。`Path`、临时文件及 move 保留在 Local 实现内部；`storage_key`、数据库 Schema、REST API、导入与补偿顺序均未改动。本轮自动化验证为完整 Java 测试 263/263、现有 JS 测试 11/11，以及 `git diff --check`；Docker Compose 配置通过只读解析。尚未以真实 MySQL、HTTP 或浏览器重新验收本轮重构，也未实现 MinIO。

V0.4-B2 Object Storage / S3 学习评估已完成，属于技术评估，没有独立业务代码提交。结论是保留 Local Storage 默认实现，只在独立测试环境验证可选的 S3-compatible 第二实现；不迁移现有图库，也不把临时 LocalStack 环境作为持久存储。B3 的实现与真实验收记录如下。

V0.4-B3 S3-compatible Storage Validation 已完成。默认 `storage.type=local`，原有 Local implementations 继续使用；显式设为 `s3` 时注入 `S3FileStorage` 与 `S3ThumbnailStorage`，只新增 AWS SDK for Java 2.x 的 S3 模块。S3 client 的 endpoint、region、凭据、bucket 和 path-style 均由 runtime configuration 提供。原图 object key 沿用相对 `storage_key`，thumbnail object key 为 `thumbnails/<storage_key>`；Schema、REST API、导入及补偿顺序未改变。SDK 的 GET 返回一次性流，adapter 通过 `HEAD` 获得长度并包装为可重新打开流的 `Resource`，因此现有 `FileStorage` / `ThumbnailStorage` contract 无须演化。上传和 ImageIO 编码使用受控临时文件并在操作结束后清理，最终对象均位于 S3。

V0.4-C1 X OAuth Token Lifecycle 已实现，真实 X 人工验收已通过。当前 App 的 Client ID / Client Secret 由本机运行时配置提供；App owner 在 Developer Console 一次性生成初始 access token + refresh token，手工放入 Git 忽略的 `config/x-oauth-tokens.properties`。应用在 access token 即将到期或到期时间未知时通过 confidential-client Basic Authentication 向 X token endpoint 刷新；收到 401 后最多再刷新重试一次，403 不触发刷新。新 token 使用串行化、临时文件加原子替换写回；响应未返回新 refresh token 时保留旧值；写盘失败会显式报错，并在当前进程内保留新凭据以供重试。未增加浏览器 OAuth callback、数据库 Schema、scheduler，未改 Inbox UI、latest/history、分页及导入业务语义。实现阶段自动化验证：Java 276 tests、0 failures、0 errors、1 skipped（需外部 LocalStack）；现有 JS 11/11 通过，本次文档更新未重跑测试。

V0.4-D1 Inbox Large-Dataset Usability 已实现：`GET /api/x-import/inbox` 返回 0-based page DTO（默认 size 24，范围 1..100），仅计数和读取当页 `PENDING`，按 `discovered_at DESC, id DESC` 排序，并仅查询当页 item ID 对应的 media。V6 为新排序重建 Inbox 索引；`post_created_at` 和 `discovered_at` 均在卡片中展示。页面使用响应式紧凑 Grid、单图 `object-fit: contain`、多图 2 列预览；Select Page 只选择可见页，翻页清空选择，Sync latest 回第一页，Import/Skip 后重载当前页并在页码越界时回到最后有效页。逐项 import result 保留。实现阶段自动化验证：Java 278 tests、0 failures、0 errors、1 skipped（需外部 LocalStack）；JS 15/15；`git diff --check` 通过。

D1 final review 与用户本机真实人工验收已通过：V6 经 Flyway 在真实本地 MySQL 成功应用；约 313 条 `PENDING` 的 Inbox 在真实浏览器中显示响应式 Grid 和 `Page 1 / 14`，Previous / Next、Select Page、翻页清空选择及 Skip 后刷新均正常。用户重新 Like 的一条 5 月旧 Post 在同步后因 `discovered_at DESC` 出现在第一页顶部。

C1 用户本机真实验收：初始 credential file 的 `expires_at` 留空，启动应用后点击 `Sync latest Likes`，应用自动刷新 OAuth token 并成功完成真实 X Likes 同步。随后用户重新 Like 一条旧 X Post，重启 Spring Boot，没有重新生成 token，再次点击 Sync，成功新增 1 条记录；这验证了本机 token 持久化、跨重启读取及后续 X API 调用。当时该新 Like 因 Inbox 按原帖日期排序而显示在旧帖附近；此可发现性问题已在 V0.4-D1 改用 `discovered_at DESC, id DESC` 后解决，见上方 D1 真实验收。

B3 验证：完整 Java 测试 266/266（含显式启用的真实 LocalStack adapter 测试）、JS 11/11 通过；独立 Compose project 成功构建镜像，MySQL/Flyway 和 `storage.type=s3` 应用启动。独立数据库中导入 PNG 后，`asset.storage_key` 为相对 key，bucket 中原图与 thumbnail 均存在；原图与缩略图 HTTP 200，原图字节与测试源文件一致，浏览器 Gallery 与 Detail 均显示图片。重复导入返回 409，bucket key 集合不增加；删除 Illustration 返回 204，Illustration/Asset 行及两个对象均消失。此前在同一独立 project 的 Local 模式导入的测试 PNG，在停止 LocalStack 并切回 Local 后，原图与缩略图仍可读取，浏览器 Gallery 正常。测试没有访问或迁移真实图库。固定的 LocalStack Community 镜像只作临时实验；实测重启后 bucket 状态丢失，不能将其与保留的 MySQL volume 作为持久图库使用。

V0.4 Final Acceptance（2026-09-29）已通过：当前 HEAD `3f7943b` 的完整 Java 测试为 278 tests、0 failures、0 errors、1 skipped（需外部 LocalStack），JS 测试 15/15，`git diff --check` 通过。使用独立 Compose project、独立测试数据库和默认 Local Storage 构建并启动应用及 MySQL 8.4；空库上 Flyway V1-V6 全部成功。浏览器中的 Gallery、Detail、空 Inbox 正常，测试 PNG 导入后原图与缩略图均可读取，原图 SHA-256 与源文件一致，数据库保存相对 `storage_key`。保留两个 volume 重建容器后，Illustration 记录、原图、缩略图及六条成功 migration 记录仍在；隔离测试容器、volume 与临时文件已清理。本机现有实例也通过 Gallery / Detail / Inbox 浏览器与 HTTP 基本核对，本地 MySQL 的 V1-V6 均为成功。GitHub Actions 最新 CI #5 对同一 HEAD 显示 Success；对当前跟踪文件、历史敏感路径及忽略规则的检查未发现真实凭据提交。此次没有重新调用真实 X API 或 LocalStack。`v0.4.0` 尚未创建；文档变更待用户确认后提交、push，并以新提交的 CI 结果作为打 tag 前的最终远端证据。

V0.2 的 Final Acceptance 已完成，代码已 push，并已创建 `v0.2.0` tag。

### V0.3-A | X Likes → Import Inbox backend

- V0.3-A 最初只请求最近 Likes 的一页；V0.3-D1 增加单次手动同步的有限分页，见下方 D1 记录。
- 新增 `x_like_item` / `x_like_media`，用唯一 `x_post_id` 防止重复候选；同一个 Post 再次出现时保留已有状态和媒体。
- 只有直接 attachments 全为带 URL 的 photo 且至少一张时标记 `PENDING`；其余标记 `UNSUPPORTED`。
- `GET /api/x-import/inbox` 仅返回 `PENDING`，按 Post 创建时间倒序并按媒体顺序返回。
- `POST /api/x-import/sync/recent` 返回本次同步摘要；当前语义为从 Likes 第一页开始查找最新点赞。X Access Token 只从未跟踪的本地配置或环境变量读取；默认关闭。
- V0.3-A 的自动测试使用模拟 X HTTP 响应，不访问真实 X API；该阶段不下载或归档 X 媒体。后续真实环境验收状态见 C2、C3、C4 和 D1。

### V0.3-C1 | X photo full-resolution download

- 按本轮提供的验收记录，C1 已完成真实验收：`1269×1265 → 1269×1265`，`4096×2498 → 4096×2498`。

### V0.3-C2 | X Post → Illustration + Multi-Asset Archive

- `POST /api/x-import/inbox/import` 按 Post 返回 `SUCCESS` / `DUPLICATE` / `FAILED` 明细，批量允许部分成功。
- 单 Post 全部照片下载完成后，Author、Illustration、全部 Assets 和 Inbox 状态在一个数据库事务中写入；成功状态为 `IMPORTED`，记录 `imported_illustration_id`。
- Author 使用稳定 `x_user_id` 匹配，更新展示名和 username；任意照片 SHA-256 重复时整 Post 拒绝归档。
- 下载或数据库失败会补偿删除本轮原图；缩略图在核心归档提交后生成，失败仅记录。
- C2 已完成真实 MySQL migration、文件系统、HTTP 和浏览器验收。

### V0.3-C3 | Detail multi-Asset

- 已完成真实多 Asset Detail 浏览器验收。

### V0.3-C4 | Gallery multi-Asset preview

- 已完成真实 Gallery 多 Asset 左右循环切换浏览器验收。

### V0.3-D1 | X Likes Incremental Sync & Pagination

- Inbox 页面只在用户点击 Sync latest Likes 时请求 X Likes；独立的 history continuation 后端入口也可显式请求。两种同步都逐页使用 `meta.next_token` 作为下一次请求的 `pagination_token`，每页复用原有幂等持久化逻辑。
- `maxResults` 默认 5、范围 5..100；`maxPages` 默认 3、范围 1..10。远端无下一页、达到页数上限、游标缺失或重复时停止。
- 摘要汇总请求页数、各状态数量、远端是否仍有下一页，以及是否因页数上限或异常游标停止。自动化测试通过；V5 migration 已在真实 MySQL 成功执行。D3-1 的跨轮 continuation 真实 X API 验收见下方记录。
- D1 曾让后续手动 Sync 从保存的 token 继续；发布前语义修复已把此行为移到独立的 history continuation 入口（见下方），页面按钮始终同步最新 Likes。`x_like_sync_seen_token` 记录历史续扫的 token 摘要，防止跨次同步循环。
- 历史续扫的游标缺失、重复或被 X 以 HTTP 400 拒绝时标记为 `INVALID` 并停止；429 等暂时错误保留 continuation。只有显式调用 `POST /api/x-import/sync/continuation/reset` 才清除无效状态；重置后可能存在未补齐的 Likes 缺口。一次同步只请求一次 `/2/users/me`。

### V0.3-D2 | X API authentication diagnostics

- 对上游 HTTP 401 返回 `X_CREDENTIAL_REJECTED`，提示检查本地凭据并重启；对 403 返回 `X_ACCESS_DENIED`，提示凭据无效或不适用于当前接口、App/User 权限不足等可能原因，不据此判断凭据已被识别。响应不包含 Access Token 或 Authorization header，其他 X API 错误映射保持原有行为。
- D2 真实 X API 验收中，将本地 Access Token 改为无效测试值并重启后，X 实际返回 403；此观察仅验证该错误路径，不代表 401 或其他认证场景已完成真实验收。
- V0.3-D2 当时仍使用本地手动配置的 X Access Token，修改 `X_API_ACCESS_TOKEN` 后需重启 Spring Boot；当时尚未实现 OAuth token lifecycle、refresh token、callback、PKCE 或 token persistence。后续 V0.4-C1 已实现本地 token refresh 与 persistence，见上方 C1 记录。

### V0.3-D3-1 | Continuation persistence

- 已通过真实 X API 验收：`maxResults=5`、`maxPages=1` 时，每轮后的 `x_like_sync_state.next_token` 推进，Spring Boot 重启后继续使用保存的 token，`x_like_item` 总数随每轮增加，`stoppedByInvalidToken=false`。
- 该验收覆盖截断与重启后继续；未覆盖自然追到末页及完成后再次从最新 Likes 同步。

### V0.3-D3-2 | Sync End State & Idempotency

- D3-2 已完成当时的验收。真实 X API 已验证多页 Likes 分页正常；当时 `maxPages` 截断后 continuation 持久化到 MySQL，下一轮 Sync 从保存的位置继续。发布前已改为下方的两个独立入口。
- 2026-09-28 验收记录：真实数据库已有 255 条 `x_like_item`；`x_like_sync_state` 为 `ACTIVE` 且保存 `next_token`，因为尚未主动遍历全部历史 Likes。D3-2 验收不要求耗尽真实历史 Likes。
- 自动化回归测试覆盖自然末页（包括恰好达到 `maxPages` 时）返回 `hasMore=false`、两个停止标志为 `false`、清除 continuation，以及下次从最新 Likes 开始；还覆盖重复 `x_post_id` 计入 `existingCount`、不重复插入媒体、保持 `IMPORTED` / `SKIPPED` 状态和 `imported_illustration_id` 关联，以及后续新 Like 进入 `PENDING`。页数截断继续同步及无效 token 防护保留。
- 自然追到真实 X API 末页、末页后重复同步及新 Like 后再次同步未在真实历史 Likes 上执行；这些边界以自动化测试作为本阶段验收依据。

### V0.3 发布前修复 | Latest Likes 与 History continuation 分离

- 页面上的 Sync latest Likes 仍调用 `POST /api/x-import/sync/recent`，每次都从第一页开始，最多抓取指定页数；不读取或修改 `x_like_sync_state` 和历史 token 摘要。达到 `maxPages` 时本次停止，下次点击仍从第一页开始。
- `POST /api/x-import/sync/continuation` 是独立的后端历史回填入口，参数仍为可选的 `maxResults` 和 `maxPages`。已有 `ACTIVE` 状态时从保存的 `next_token` 继续，且 `maxResults` 必须与保存值相同；无保存状态时从第一页启动历史回填。达到页数上限时保存下一页 token，自然末页时清除状态与 token 摘要。`INVALID` 状态仍需显式调用 `/sync/continuation/reset` 重置。
- 两个入口都复用 `x_post_id` 唯一键与按页幂等保存，已有 `IMPORTED` / `SKIPPED` 等状态不被覆盖。当前页面没有 history continuation 按钮；该入口只供显式后端调用。此修复仅做代码与自动化测试验证，尚未重新执行真实 X API、MySQL 或浏览器验收。

### V0.3-D4 | Pending Inbox Import Workflow

- D4 已完成真实环境人工验收：X Import Inbox 的单项/批量处理页面工作正常。
- 真实浏览器中批量选择 3 个 `PENDING` 项执行 Import Selected，结果为 `SUCCESS 3`、`DUPLICATE 0`、`FAILED 0`；Inbox 的 pending 数量相应减少，每个成功项均返回 Illustration Detail 链接。
- MySQL 核验这 3 条 `x_like_item` 均变为 `IMPORTED`，`imported_illustration_id` 分别为 `30`、`31`、`32`，均非 `NULL`。
- 真实归档文件及 Gallery/Detail 链路正常；相关自动化测试此前已通过。

### V0.3 最终能力与验收边界

- Inbox 页面只在用户点击 Sync latest Likes 时调用 `POST /api/x-import/sync/recent`；该入口每次从 Likes 第一页开始，不读写历史续扫状态。`POST /api/x-import/sync/continuation` 是独立的显式后端历史续扫入口，持久化游标以支持跨轮和重启后继续；页面没有此按钮。同步按页幂等保存，已处理的 Post 状态和 `imported_illustration_id` 不会因重复同步被覆盖。
- `x_like_item` 的候选状态包括 `PENDING`、`UNSUPPORTED`、`SKIPPED`、`IMPORTED`。仅直接附件全部为带 URL 的 photo 且至少一张的 Post 可进入 Inbox；GIF、视频及混合媒体不进入 X 归档流程。本地文件导入对 GIF 的支持不变。
- Inbox 支持查看待处理项、单项/批量选择、Import Selected 和 Skip Selected。每个成功导入的 X Post 形成一个 Illustration 及按顺序保存的一个或多个 Asset；SHA-256 重复会拒绝整条 Post，批量导入返回逐项结果并允许部分成功。数据库事务覆盖 Author、Illustration、Assets 和 Inbox 状态；文件失败清理采用显式补偿，不能由数据库事务保证回滚。
- Gallery 卡片可循环预览多 Asset；Detail 按顺序纵向展示全部原图。JPEG/PNG Gallery 预览继续使用缩略图，GIF Gallery 预览继续使用原始 `/content`。
- 当前 Java 测试源码有 259 个 `@Test`，JS 测试源码有 11 个 `test(...)`。仓库留存的、与现有 Java 测试类匹配的 Surefire 报告记录 259 tests、0 failures、0 errors、0 skipped；本次文档收口没有重跑测试。上方 C1–D4 的真实环境验收记录保持原样。latest/history 双入口修复后的真实 X API、MySQL、浏览器重新验收仍未执行；真实历史 Likes 的自然末页及其后重复/新增 Likes 场景以自动化测试为依据。
- X Access Token 仍由本地配置或环境变量手动提供，X API 默认关闭；没有 OAuth、Token 自动刷新、后台自动同步或 X GIF/视频归档。

以下事项明确属于**后续版本范围**，不是 V0.3 完成的阻塞项：

1. Inbox 手机相册式紧凑网格。
2. 缩放预览、调整列数。
3. 更自然的 Select All / Deselect All 选择体验。
4. hover 轻微放大及动画体验。
5. Inbox 筛选。
6. history continuation 页面按钮。
7. X GIF、视频等非静态图片媒体导入。
8. OAuth、Token 自动刷新。

---

## Completed Versions

### V0.1

V0.1 has been completed, published to GitHub and tagged as `v0.1.0`.

Main capabilities:

- Spring Boot + MySQL + JdbcTemplate backend
- Flyway database migrations
- Local filesystem storage for original media
- Database stores metadata and relative `storage_key`
- JPG / JPEG / PNG / GIF support
- File extension + Magic Number validation
- Streaming file IO
- 50 MB file size limit
- Single-image import
- Batch import with partial success
- Database failure compensation for newly stored files
- Illustration / Asset / Author / Tag data model
- Author one-to-many relationship
- Tag many-to-many relationship
- Paginated gallery
- Real media content endpoint
- Illustration detail page
- PATCH partial update semantics
- Delete flow covering database records and physical files
- Lightweight HTML / CSS / JavaScript UI
- Automated tests
- README and screenshots
- Public GitHub repository
- Stable `v0.1.0` tag

---

## V0.2 Completed Work

### A. SHA-256 Deduplication

Status: ✅ COMPLETE

Completed:

- `asset.sha256` added to the database
- SHA-256 is based on file contents, not filename
- Hash is calculated during the existing streaming copy process
- No unnecessary full second read of the imported file
- Single import detects duplicate content
- Duplicate single import returns HTTP `409 Conflict`
- Duplicate response includes the existing Illustration ID
- Newly written duplicate physical file is cleaned up
- Database unique constraint acts as the final concurrency guard
- Batch import distinguishes:
    - `SUCCESS`
    - `DUPLICATE`
    - `FAILED`
- Batch import reuses the single-import business logic
- Historical Asset SHA-256 backfill implemented
- Backfill is explicitly enabled through maintenance configuration
- Existing historical assets were successfully backfilled

Database state reported during the earlier SHA-256 backfill (not rechecked in this documentation review):

- Existing Assets without SHA-256: 0
- Historical duplicate files discovered during backfill: 0

---

### B. Thumbnail & Gallery Optimization

#### B1. Static Thumbnail Generation

Status: ✅ COMPLETE

Implemented:

- `ThumbnailService`
- JPG / JPEG thumbnail generation
- PNG thumbnail generation
- Maximum thumbnail edge: 600 px
- Aspect ratio preserved
- Images smaller than the target size are not enlarged
- Small images can be reused/copied without unnecessary re-encoding
- Transparent PNG alpha channel is preserved
- JPEG thumbnail quality: 0.85
- Thumbnail files use temporary-file + move semantics
- Existing thumbnails are reused
- Thumbnail generation is idempotent
- Existing storage path safety logic is reused

Thumbnail storage key is derived from the original `storage_key`.

Example:

```text
2026-09/example.jpg
↓
thumbnails/2026-09/example.jpg
```

#### B2. Thumbnail Integration & Historical Backfill

Status: ✅ COMPLETE; REAL ENVIRONMENT ACCEPTANCE PASSED

- New JPEG/PNG imports call thumbnail generation after persistence succeeds; GIF is skipped.
- Thumbnail failure is logged without changing the successful core import result or deleting its original file.
- The opt-in historical backfill completed with `total=16, ready=13, skippedGif=3, failed=0`.
- Real import acceptance passed: JPEG/PNG generate thumbnails, while GIF imports successfully without a thumbnail.
- Real deletion acceptance passed: HTTP 204; the Illustration and Asset rows, original file, and thumbnail were removed.
- The temporary backfill startup parameter was removed after acceptance.
- The B2 implementation has been committed. The reviewed run on 2026-09-23 passed 174 automated tests.

---

## V0.2 版本定义

### 版本名称

V0.2｜归档质量与图库体验

### Version Goal

V0.2 的目标不是增加大量新业务功能，而是在 V0.1 已经跑通的
“导入 → 保存 → 浏览 → 查看详情 → 整理”主链路上，提高：

1. 归档数据质量
2. 重复图片控制
3. 图库加载效率
4. 图库浏览体验

完成 V0.2 后，Illustration Archive 应该从：

“一个能工作的插画归档 Demo”

进一步变成：

“一个可以持续导入真实收藏并较舒服地浏览的本地归档工具”。

---

### V0.2 Scope

#### A. SHA-256 去重

状态：✅ 已完成

包括：

- Asset 保存 SHA-256
- 导入时流式计算 Hash
- 单张重复检测
- HTTP 409 Duplicate 语义
- 重复物理文件清理
- 数据库唯一约束作为并发兜底
- 批量导入 SUCCESS / DUPLICATE / FAILED
- 历史数据 SHA-256 Backfill
- 所有现有 Asset SHA-256 补齐

---

#### B. Thumbnail 与图库体验

##### B1｜Thumbnail 底层生成能力

状态：✅ 已完成

包括：

- JPG / PNG thumbnail
- 最大边 600px
- 保持比例
- 小图不放大
- PNG Alpha 保留
- JPEG quality 0.85
- 临时文件 + move
- 已有 thumbnail 复用
- storage_key 推导 thumbnail 路径

GIF 当前继续使用原始动态图。

---

##### B2｜Thumbnail 接入与历史 Backfill

状态：✅ 已完成，真实环境验收通过；已提交

验收范围：

- 新 JPG / PNG 导入后生成 thumbnail
- GIF 不生成静态 thumbnail
- thumbnail 失败不破坏已经成功的核心归档
- thumbnail 失败具有可观察性
- 历史 JPG / PNG thumbnail backfill
- 已有 thumbnail 可安全跳过
- 单项失败不会阻止整个 backfill
- 自动化测试
- 真实 MySQL + 文件系统验收

真实环境验收结果：历史回填 `total=16, ready=13, skippedGif=3, failed=0`；新 JPG/PNG 导入生成 thumbnail，GIF 正常导入且不生成 thumbnail；删除验收返回 HTTP 204，并删除对应 Illustration / Asset 记录、原图和 thumbnail。回填临时启动参数已移除。

---

##### B3｜Thumbnail HTTP 与图库切换

状态：✅ 已完成；B3-1 真实 HTTP 验收与 B3-2 真实浏览器验收均通过

- `GET /api/assets/{assetId}/thumbnail` 返回已有 JPG/JPEG/PNG thumbnail。
- 首页 JPG/JPEG/PNG 卡片使用 thumbnail；GIF 卡片使用 `/content` 并保持动画。
- 详情页所有格式继续使用 `/content` 读取原始媒体。
- 浏览器 Network 已确认静态图片请求切换到 thumbnail。

---

##### B4｜图库布局与浏览体验

状态：✅ 已完成；真实浏览器验收通过

- Gallery 使用固定 1:1 预览区域和 `object-fit: cover`，允许适度裁切；作者名保持单行省略，响应式布局正常。
- Gallery 的 JPG/JPEG/PNG 继续使用 thumbnail；GIF 继续使用 `/content` 并保持动画。分页和点击进入详情保持正常。
- Detail 采用 Artwork-first 布局：原始 `/content` 图片是页面第一视觉主体。横图充分利用页面宽度，竖图受合理最大高度限制；所有图片保持原始比例，不裁切、不拉伸。
- Detail 的编辑、删除、作者、标签、备注和来源等既有功能保持不变。
- 已使用真实横图及其他图片比例完成浏览器验收。

---

### V0.2 Definition of Done

只有同时满足以下条件，V0.2 才算完成：

```text
SHA-256 去重完整闭环                       ✅
JPG/PNG thumbnail 生成                    ✅
新导入自动接入 thumbnail                    ✅
历史 thumbnail backfill                     ✅
图库实际使用 thumbnail                    ✅
GIF 保留合理的动画体验                    ✅
详情仍可访问原始图片                      ✅
自动化测试全部通过                         ✅
真实 MySQL 验收通过                         ✅
真实文件系统验收通过                       ✅
真实浏览器验收通过                         ✅
没有已知的数据一致性严重问题               ✅
PROJECT_STATE 更新完成                    ✅
README 根据最终效果进行必要更新            ✅
```

### V0.2 Final Acceptance

2026-09-24 最终验收完成：使用项目 Maven Wrapper 运行完整 `test`，结果为 183 tests、0 failures、0 errors、0 skipped，`BUILD SUCCESS`。此前真实 MySQL、文件系统、HTTP 和浏览器验收结果见上方各阶段记录；本轮没有重新执行真实 MySQL 验收。

根据上述验收、当前测试及 SHA-256 去重、导入失败文件补偿、缩略图生成和删除清理的现有设计，当前未发现已知的数据一致性严重问题。数据库事务不能回滚文件系统操作，相关清理失败会记录日志；这不是对所有故障情形的绝对保证。

README 已按 V0.2 最终能力收尾。V0.2 已完成 Final Acceptance，代码已 push，并已创建 `v0.2.0` tag。

### V0.2 Out of Scope

- 登录与权限系统。
- Redis、MQ、Elasticsearch、微服务、Docker 等新基础设施。
- AI、OCR、外部平台集成或对象存储。
- 为本版本目标之外的功能重写现有后端分层或引入大型前端框架。

以上边界遵循 `AGENTS.md` 中的 scope 控制；若未来确有新需求，应先由用户确认范围变化。

### V0.4 release status

V0.3 已正式封存，`v0.3.0` tag 已创建并 push。V0.4 的 A1/A2/A3、B1/B2/B3、C1、D1 计划工作包与 Final Acceptance 已按上文范围完成，并已正式发布 `v0.4.0`。上方发布前验收记录中的 Git 状态属于当时阶段记录。
