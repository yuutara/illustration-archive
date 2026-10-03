# Illustration Archive - Project State

> This file records the current project state and important design decisions.
> When summaries and the actual repository conflict, the repository is the source of truth.

## Current Version

V0.5 - 图库使用体验（当前工作包：F03 全库搜索 + Tag / Author 导航）

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

V0.3 已正式封存，`v0.3.0` tag 已创建并 push。V0.4 的 A1/A2/A3、B1/B2/B3、C1、D1 计划工作包与 Final Acceptance 已按上文范围完成，具备 `v0.4.0` 的技术发布条件。`v0.4.0` 尚未创建；本轮文档变更尚未 commit / push，发布 Git 操作由用户确认后执行。
