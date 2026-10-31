# Windows 桌面版

> 从 PoC 演进而来（2026-10-26 首轮验收），2026-10-30 起是正式的产品方向，路线见 [DESKTOP_ROADMAP.md](DESKTOP_ROADMAP.md)。本文前半部分保留 PoC 阶段的记录；**X、AI、窗口、数据目录的现状以[第一轮](#桌面产品化第一轮2026-10-30)为准**。

## 目标与边界

- 让现有 Spring Boot 后端 + 现有前端以"双击即用"的方式在 Windows 上运行，验证桌面化的主要障碍（MySQL 依赖、相对路径配置、打包）是否可解。
- 原 MySQL 运行方式不变；PoC 使用完全独立的数据与配置，不连接、不读取、不迁移真实数据库。
- 关闭 X / OAuth、Gemini、S3 与一次性维护回填。

## 架构

```
IllustrationArchive.exe (jpackage 启动器 + 自带 Java 21 运行时)
  └─ 解压后的应用 jar 与 lib\，-splash + -Dillustration-archive.desktop=true
       └─ IllustrationArchiveApplication.main → DesktopLauncher（主线程跑 SWT 事件循环）
            ├─ instance.lock 单实例锁
            ├─ DesktopServer.start(home)  → Spring profile "desktop"
            │    ├─ 文件型 H2：<home>\data\archive.mv.db（MODE=MySQL, IGNORECASE）
            │    ├─ Flyway：classpath:db/migration-h2（V1–V7 的 H2 等价版本）
            │    ├─ 媒体：<home>\media（数据库仍只存相对 storage_key）
            │    ├─ Tomcat 只监听 127.0.0.1（默认 47391，被占用时随机端口）
            │    └─ LocalAccessFilter：启动令牌 → SameSite=Strict Cookie
            └─ DesktopWindow：SWT Shell + WebView2 打开 http://127.0.0.1:<port>/desktop/session?token=…
                 └─ 写入会话 Cookie 后跳转到新版 C 图库 /c/（2026-10-28 起；此前为旧图库 /）
```

应用窗口和回退时的“打开图库”都走这个会话入口，所以都默认进入 `/c/`；旧图库仍在 `/`，C 图库顶栏的“旧图库”可以进入，二者共用同一个会话与访问保护。普通 MySQL 运行方式不加载这个过滤器，不受影响。

`<home>` 默认 `%LOCALAPPDATA%\IllustrationArchive`，可用环境变量 `ILLUSTRATION_ARCHIVE_DESKTOP_HOME` 或系统属性 `illustration-archive.desktop.home` 覆盖。日志在 `<home>\logs\desktop.log`。

### 关键设计

- **配置隔离**：所有隔离相关属性由 `DesktopServer` 以命令行参数传入（最高优先级）。原因是 `X_API_ENABLED`、`AI_ANALYSIS_ENABLED`、`SPRING_DATASOURCE_URL` 等环境变量经宽松绑定会覆盖 profile 文件。`spring.config.import` 改为 `${illustration-archive.local-config:./config/application-local.properties}`：MySQL 模式默认值不变，桌面模式指向 `<home>\config\desktop.properties`（可选，默认不存在），因此不会读取 `./config/application-local.properties`。
- **H2 迁移**：V2/V4/V6/V7 使用了 H2 不支持的 MySQL 多子句 `ALTER TABLE`，所以 `db/migration-h2` 提供语义等价的 V1–V7，未改动任何已应用的 MySQL migration。MySQL `TEXT` 映射为 `VARCHAR(65535)`，使 `IGNORECASE` 生效，保持与 `utf8mb4_unicode_ci` 一致的大小写不敏感搜索和标签唯一性。**代价：若保留桌面 PoC，后续每个 schema 变更都要同时写一份 H2 版本。**
- **Repository 唯一改动**：H2 对 `RETURN_GENERATED_KEYS` 会把带默认值的列（`created_at` 等）一起作为生成键返回，`KeyHolder.getKey()` 因此失败。Illustration / Asset / Author / Tag 的插入改为按列名 `{"id"}` 取键；MySQL Connector/J 同样支持该写法。X 相关 Repository 不在 PoC 范围内，未改动（其中仍有 `UTC_TIMESTAMP` 等 MySQL 专用 SQL）。
- **本机访问保护**（无用户鉴权的前提下）：
  - 只绑定 `127.0.0.1`，局域网不可达；
  - 每次启动生成 256 位令牌，入口 URL 换成 `HttpOnly; SameSite=Strict` Cookie，之后所有页面、API、原图都要求该 Cookie——其他网站无法跨站读取或写入；
  - 校验 Host 必须是 `127.0.0.1:<port>` / `localhost:<port>`，防 DNS rebinding；
  - 非安全方法若带 Origin 必须同源，防同机其他端口（浏览器视为同站）的写请求；
  - 上一次启动的 Cookie 在重启后失效。
- **退出释放**：关闭窗口 → `context.close()`：Tomcat 优雅停机（最长 15 秒，等待进行中的导入）→ Hikari 关闭连接池 → H2 在最后一个连接关闭时关库（`DB_CLOSE_ON_EXIT=FALSE`，不依赖 H2 自己的关闭钩子）→ 释放单实例锁 → `System.exit`。
- **事务与文件补偿**：沿用现有服务逻辑，未改动。H2 在另一事务持有同 SHA-256 未提交行时，会在唯一索引上等待并在对方提交后报重复键，行为与 MySQL InnoDB 一致（已由测试验证）。

## 构建与运行

```powershell
.\scripts\build-desktop.ps1 -JavaHome "$HOME\.jdks\ms-21.0.11" -MavenArgs '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository'
```

产物（`dist/` 已被 Git 忽略）：

- `dist\IllustrationArchive\IllustrationArchive.exe`：双击运行；整个文件夹约 195 MB（应用约 46 MB，其余为 jlink 运行时，含 `java.exe` 和 CDS 基础归档）。第一次退出时生成约 40 MB 的 `app\app.jsa`，之后启动更快。
- `dist\IllustrationArchive-win64.zip`：约 93 MB，解压后即可运行，目标机无需安装 Java。

脚本不执行 `mvn clean`（运行中的本地实例可能占用 `target\` 下的文件），只删除 `target\classes` 和旧 jar；测试需单独运行。`dist` 里的应用正在运行时，脚本会拒绝覆盖，可用 `-OutputDir` 输出到别的目录。

图标与启动图的源文件在 `packaging\icon\`：`app-icon.png` 是 1024 px 母版，用于 24 px 及以上；`app-icon-small.svg` 是手绘小图，用于 16–20 px 和网页标签图标；另有 `splash.html`。生成结果 `app.ico`、`splash*.png` 和 `src\main\resources\desktop\icon-*.png` 一并提交，正常构建不需要重新生成。改了源文件后运行 `node scripts\build-icons.mjs`（用本机 Edge 渲染，只读本地文件），它也会把小图同步到 `src\main\resources\static\c\app-icon.svg`。

## 自动化测试

`DesktopServerIntegrationTest` 通过与启动器相同的 `DesktopServer.start` 启动真实 Spring 上下文（Tomcat + 文件 H2 + Flyway + 本地文件存储），每个用例使用独立临时数据目录：

| 用例 | 验证内容 |
| --- | --- |
| `localWorkflowPersistsAcrossRestartAndReleasesDatabaseAndPort` | HTTP 批量导入（含重复）、标签/作者创建、元数据编辑、大小写不敏感与 `@handle` 搜索、原图/缩略图读取；不读取 MySQL 本地配置；关闭后端口可重新绑定、H2 可重新打开；重启后数据仍在、旧会话失效；删除后记录与文件都移除 |
| `localAccessGuardRejectsRequestsWithoutTheLaunchSession` | 无 Cookie / 错误令牌 / 伪造 Host / 跨源写入均 403；Cookie 带 HttpOnly 与 SameSite=Strict；局域网地址无法连接 |
| `springTransactionRollsBackIllustrationWhenAssetInsertFails` | 经 Spring 代理的 `@Transactional`：asset 插入失败时先插入的 illustration 被回滚 |
| `databaseDuplicateFoundAfterLockWaitDeletesTheStoredFile` | 另一事务持有同 SHA-256 未提交行时，导入在 H2 唯一索引上等待；对方提交后返回 `DuplicateIllustrationException`，已落盘文件被补偿删除 |
| `concurrentImportsOfTheSameImageKeepOneAssetAndOneFile` | 8 线程同时导入同一图片：1 成功、7 重复，库中 1 行、磁盘 1 个原图 |

运行方式（Windows 本机）：

```powershell
.\mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-Dtest=DesktopServerIntegrationTest' '-DargLine=-Djava.io.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp -Djdk.net.unixdomain.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp' test
```

`-Djdk.net.unixdomain.tmpdir` 是环境绕行：在 Claude Code 宿主派生的进程里，`%LOCALAPPDATA%` 下的 AF_UNIX socket 能创建但无法连接，JDK 的 Selector / HttpClient 会报 `Unable to establish loopback connection`。从资源管理器双击启动的应用不受影响。

2026-10-26 结果：`DesktopServerIntegrationTest` 5/5 通过。全量 `mvnw test`（JDK 17.0.19，同上 argLine）：390 tests，0 failures / errors，4 skipped（3 个未配置环境变量的 MySQL opt-in 测试 + 1 个 LocalStack 测试，与健康基线相同），BUILD SUCCESS。前端未改动，未重跑 JS 测试。

`{"id"}` 取键在 MySQL 上的行为：2026-10-26 在本机 MySQL 8.0.42 的专用测试库 `illustration_archive_f03_test_<时间戳>` 上运行 `IllustrationGalleryMySqlTest` 与三个 Repository 测试，25/25 通过、0 skipped，覆盖作者 / 标签创建、X Post 插图与 Asset 插入及其事务回滚；测试库随后已删除，未触碰真实图库。2026-10-27 由 Claude Code 在新的专用库 `illustration_archive_f03_test_20261027000214` 上独立复跑 `IllustrationGalleryMySqlTest`：1/1 通过、0 skipped，BUILD SUCCESS；随后用只接受该命名模式的程序删除测试库，服务器上仅剩原有 `illustration_archive`。

## 人工验收记录（2026-10-26）

环境：Windows 11 Home 22631，默认浏览器 ego (lite)（Chromium），从资源管理器双击 `IllustrationArchivePoC.exe`；数据目录为全新的 `%LOCALAPPDATA%\IllustrationArchivePoC`。测试图片为生成的 900×1200 PNG / JPEG / GIF。

| 项目 | 结果 |
| --- | --- |
| 双击启动 | 控制窗立即出现，Spring 启动 5.2 秒（重启 3.7 秒）；自动打开系统浏览器并进入空图库 |
| 控制窗 | 显示"运行中：仅本机可访问"、地址、数据目录；"打开数据文件夹 / 打开图库 / 退出"可用 |
| Flyway | 新库应用 H2 V1–V7 成功 |
| 批量导入 | 选 6 个文件（含一份重复副本）：成功 5，重复副本返回 DUPLICATE_IMAGE；原图与 JPEG/PNG 缩略图写入 `<home>\media`，GIF 无缩略图 |
| 浏览 | 瀑布流、查看器、详情页正常显示原图 |
| 编辑 | 中英文标题、备注、新建作者（含 X 用户名）、新建标签保存成功 |
| 搜索 | `SKY` 命中标签 "Blue Sky 天空"（大小写不敏感）；`暖色` 命中中文备注；`@alice_art` 命中作者 |
| 访问保护 | curl 无 Cookie 访问 `/`、API、原图均 403；错误令牌 403；伪造 Host 403；`netstat` 仅 `127.0.0.1:47391` 监听，本机 8 个其他网卡地址均无法连接 |
| X Inbox 页面 | 在 H2 下可打开并显示空列表，无错误日志（同步未点击） |
| 退出释放 | "退出"后进程消失、端口不再监听，日志依次为优雅停机 → Hikari 关闭 |
| 重启持久化 | 再次启动后 5 件作品、标题、作者、标签、备注都保留 |
| 删除 | 详情页删除并确认后图库剩 4 件，对应原图与缩略图从磁盘移除 |
| 单实例 | 运行中再次双击：提示"已在运行"，确认后第二个进程退出 |
| 关窗退出 | 点控制窗右上角 ×：启动器与 JVM 进程均退出，端口释放 |

验收数据保留在 `%LOCALAPPDATA%\IllustrationArchivePoC`（4 张测试图），与真实图库无关；需要干净环境时关闭应用后删除该目录即可。

## 收尾加固（2026-10-27）

首轮验收发现的三个问题已处理，MySQL 运行方式与真实数据不受影响：

- **可选集成按需创建 HttpClient**：`GeminiClient`、`XApiClient`、`XTokenManager`、`XPhotoDownloadService`、`XAnimatedDownloadService`、`XAnimatedPreviewService` 改为持有 `LazyHttpClient`，第一次真正发请求时才创建 JDK `HttpClient`。关闭状态的集成在启动时不再创建 selector / loopback 通道。两种模式都生效；已启用的 X / Gemini 行为不变，只是客户端创建推迟到首个请求。
- **桌面模式隐藏不可用入口**：新增 `GET /ui-capabilities.js`，同步设置 `window.UiCapabilities`；默认 `xImport` / `aiAnalysis` 均为 `true`（MySQL 模式界面不变），桌面模式由 `DesktopServer` 设为 `false`。桌面模式下：
  - 图库"操作"菜单不显示 X Inbox；
  - 详情页不显示"边看图边分析"；
  - Viewer 只有"作品信息"，没有 AI 标签页；
  - 直接打开 Inbox 时隐藏 Sync Likes 和游标重置按钮，并提示"当前版本未启用 X 同步"。
  
  后端的关闭状态不变，这只是界面层隐藏。
- **重复标签返回 409**：`TagService` 将唯一索引冲突转为 `DuplicateTagException`，`POST /api/tags` 返回 409 和 `{"code":"DUPLICATE_TAG"}`。数据库唯一约束仍是唯一来源，并发创建同名标签同样得到 409。详情页显示"已存在同名标签（不区分大小写），请在“选择标签”中搜索并选用。"，并保持表单打开。MySQL 模式下同样生效。
- **打包脚本**：发现 PoC 正在从 `dist` 运行时直接报错退出，不再删除被占用的文件，以免留下半删除的应用。

验证（2026-10-27）：
- 相关 Java 测试 95/95 通过；新增 `LazyHttpClientTest`、`UiCapabilitiesControllerTest`，`TagServiceTest` / `TagControllerTest` 各新增一例；`XApiClientSpringContextTest` 改为断言启动时不创建 HttpClient；`DesktopServerIntegrationTest` 改为断言真实 H2 下重复标签返回 409 + `DUPLICATE_TAG`，以及桌面能力脚本两项均为 `false`。
- 全量 `mvnw test`：394 tests，0 failures / errors，4 skipped（同前），BUILD SUCCESS。
- JS：新增 `ui-capabilities.test.js` 5 例，全量 `node --test` 138/138 通过。
- 未重做的人工验收沿用 2026-10-26 记录；本次需复验的只有界面入口隐藏、重复标签提示和重新打包后的启动。用户已于 2026-10-27 完成界面复验并通过。

## Review 后补强（2026-10-27）

Codex 只读 Review 指出两点，均只改 `DesktopServer` 的桌面启动参数，MySQL 模式不经过这条路径、行为不变：

- **Flyway / 连接池只连 PoC 的 H2**：Spring Boot 中 `spring.flyway.url/user/password` 和 `spring.datasource.hikari.jdbc-url/username/password` 会覆盖 `spring.datasource.*`，例如环境变量 `SPRING_FLYWAY_URL` 就能把迁移导向别的数据库。现在两组都显式固定为同一个 H2 URL 与账号，`spring.flyway.enabled=true`。
- **留空的数值配置不再阻止启动**：关闭状态的 X / Gemini 仍会在启动时绑定数值。像 `AI_ANALYSIS_TIMEOUT_SECONDS=`、`X_SYNC_MAX_PAGES=` 这样的空值，原来会让启动以 `NumberFormatException` 失败（已复现）。现在桌面模式把这 8 个数值固定为原有默认值（90 / 5 / 3 / 25 / 2 / 6 / 100 / 50）。MySQL 模式下空值仍会启动失败，与 PROJECT_STATE 中的已知限制一致。

验证：
- `DesktopServerIntegrationTest` 新增 `desktopIgnoresOutsideConnectionSettingsAndBlankIntegrationNumbers`：用系统属性（优先级同环境变量，高于配置文件）注入外部 Flyway / Hikari 连接和 8 个空数值，断言 Flyway 与业务数据源都连到 `<home>\data\archive`，7 个迁移落在 PoC 库，并且应用正常启动。
  - 修复前该测试复现启动失败；
  - 临时去掉 Flyway 固定项后，该测试按预期失败（Flyway 连到了注入的 `jdbc:h2:mem:elsewhere`）。
  - 修复后整个测试类 6/6 通过。
- 未重跑全量测试与 GUI 验收：改动仅限桌面启动参数，沿用前一节结果。
- 重新打包后从资源管理器启动：Flyway 在已有 PoC 库上校验 7 个迁移、无需迁移，5.1 秒启动，无错误日志。

## C 图库浏览（只读预览，2026-10-28）

第一个接入包：把原型 B 的 C 暖纸视觉与已验收交互接到现有只读 API，作为 `/c/` 页面由同一个 Spring Boot 提供，经过同一个 `LocalAccessFilter`（无启动会话一律 403）。旧图库 `/` 不变，其“操作”菜单增加“新版图库（预览，只读）”入口。

- 文件：`static/c/`（`archive-client.js` 数据读取、`c-gallery.js` 浏览、`fx.js` 牌堆与动效、样式与 `theme-mode.js`），`CGalleryPageController` 只负责 `/c` → `/c/` → `index.html`。
- 读取：`GET /api/illustrations?page&size=30` 分页（接近底部自动载入，失败可重试）；封面先载入缩略图量出比例与主色，缩略图缺失时退回原图，再失败用占位图；看图器先显示缩略图再换原图；详情（标签、备注、来源、文件名与大小）按需读取。
- 禁用：编辑、删除、导入、批量整理、“最近导入 / 待整理”、其他排序——入口保留但不可用，快捷键只提示“尚未接入”，页面只发 GET。视频页显示“暂不支持预览”并链接旧图库详情。（搜索与作者 / 标签筛选已在下一节接入。）
- 页面声明 `<meta name="darkreader-lock">`：页面自带浅 / 深色，避免 Dark Reader 之类扩展二次改色（验收时 ego 中的 Dark Reader 曾把浅色页面改暗）。
- 测试：`DesktopServerIntegrationTest` 新增 C 图库用例（访问保护、分页、多图顺序、GIF / 缺失缩略图的回退来源、重启后读取）；`src/test/js/c-archive-client.test.js` 覆盖分页、空图库、请求失败重试、会话失效、缩略图回退、多图 / GIF / 视频、详情重试。
- 隔离验收图库：`CGalleryAcceptanceServer` 是显式开启的测试工具（不加参数不运行），在指定目录（仓库外）建立独立 H2 图库：64 张生成图、55 件作品，含 2 / 3 / 4 页作品、GIF 与一张删掉缩略图的 JPEG；把一次性会话地址写入 `<目录>\acceptance-session.txt`，直到出现 `<目录>\acceptance-stop` 才退出；同一目录再次运行即读取已有图库。

```powershell
.\mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-Dtest=CGalleryAcceptanceServer' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Djava.io.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp -Djdk.net.unixdomain.tmpdir=D:\IdeaProjects\illustration-archive\target\test-temp -Dc.acceptance.home=D:\ClaudeProjects\2026-10-28\c-gallery-acceptance' test
```

用真实桌面启动器体验同一份验收图库：`mvnw package -DskipTests` 后，设置 `ILLUSTRATION_ARCHIVE_DESKTOP_HOME` 指向该目录，再 `java -Dillustration-archive.desktop=true -jar target\illustration-archive-0.0.1-SNAPSHOT.jar`。

验证（2026-10-28）：全量 `mvnw test` 396 tests，0 failures / errors，4 skipped（同前），BUILD SUCCESS；`node --test` 145/145。ego 浏览器在隔离图库上实测：分页（30 → 55，无重复）、空图库、首屏与翻页请求失败后重试、缺失缩略图回退、GIF、2 / 3 / 4 页循环牌堆与从当前页进入看图器、原图替换与翻页、详情、禁用入口只提示不操作、只发 GET、重启后读取、浅 / 深色。ego 窗口不刷新画面，动画与手感仍需人工验收。

### 搜索与作者 / 标签筛选（2026-10-28）

前端直接复用现有 API；后端只修了一处 H2 搜索大小写问题（见下），没有数据库结构或 migration 改动。

- 语义沿用后端契约：`GET /api/illustrations?q&authorId…&tagId…` 在全库上筛选，作者之间、标签之间为“或”，作者、标签、关键词三组之间为“且”；不在已载入的作品里过滤。候选来自 `GET /api/authors|tags?keyword&limit=30&offset`，弹层滚到底继续翻页，能找到前 20 条之外的项。
- 文件：`c-filters.js`（搜索框、作者 / 标签弹层、已选条件、地址同步）；`archive-client.js` 增加查询状态与候选分页；`c-gallery.js` 按查询结果铺墙、显示“无匹配”、作品上的作者名与看图器信息里的作者 / 标签可点（只看它，其他条件保留）。
- 换条件：结果、页码、总数清零，取消旧列表请求；迟到的响应、错误和封面测量按查询代次丢弃，不会混入新结果；看图器关闭，不再翻旧结果。旧作品墙变淡、不可点，等新结果第一页到达后整体替换。量过的封面按 asset id 复用。
- 搜索框：停顿 300ms 自动搜索、Enter 立即搜索、Esc 清空，`/` 聚焦；中文输入法拼写和选词期间不搜索，选词用的 Enter / Esc 不当作命令。条件写入页面地址（`/c/?q=…&authorId=…&tagId=…`，`replaceState`），刷新后保留。
- 已选条件显示为筛选条（同组之间写“或”），可逐个去掉或“清除全部”，旁边显示结果数；服务端已无此 id 时显示“已不存在的作者 / 标签 #id”。看图返回时条件与已载入进度不变。
- 弹层开着时按键只归弹层：输入框里的方向键、空格、Enter、Esc 不冒泡到作品墙；点弹层空白处焦点留在输入框；焦点在别处时 Esc 关弹层，其余按键不触发作品墙快捷键。
- 测试：`c-archive-client.test.js` 增加订阅者在 `loading` 时读到 `isLoading()` 为真、查询地址构造、重置分页、相同条件不重载、过期响应被取消 / 不取消也被丢弃（含测量中途）、首屏与翻页失败重试、封面复用、候选分页 / 过期关键词 / 失败重试；`c-filters.test.js` 用真实数据层验证输入法、防抖、Enter / Esc、地址恢复与筛选条移除，以及弹层用 45 个假作者翻过第一页（30）、按键不外泄、Esc 关闭并还原焦点；`DesktopServerIntegrationTest` 在隔离 H2 上以原始行独立计算期望，校验关键词、多作者、多标签及组合条件跨页（每页 4 件）完整无重复，并按弹层的每页 30 条翻完候选。`CGalleryTestLibrary` 另加 40 个作者和 40 个标签（无作品，共 43 作者 / 45 标签），验收图库的 `acceptance-library.txt` 列出排在第 30 条之后的候选。
- 修复 `@账号` 大小写（验收中发现）：H2 以 `IGNORECASE=TRUE` 建表时 `author.x_username` 是 `VARCHAR_IGNORECASE`，但 `CONCAT('@', a.x_username)` 的结果是普通 `VARCHAR`，这一条 `LIKE` 变成区分大小写，搜 `@AMAMIYA_YUKI` 得 0 件，而 `@amamiya_yuki` 和 `AMAMIYA_YUKI` 各得 14 件。在 `IllustrationRepository` 中改为只对这一比较的两侧调用 `LOWER`：`LOWER(CONCAT('@', a.x_username)) LIKE LOWER(?) ESCAPE '!'`。参数绑定和 `%` / `_` / `!` 的转义都不变；MySQL 的 `utf8mb4_unicode_ci` 本来就不区分大小写，结果应当不变。根因已在隔离 H2 库上用只读查询确认：列上的 `LIKE` 为 TRUE，`CONCAT` 后为 FALSE，两侧 `LOWER` 后为 TRUE。

#### 验收记录（2026-10-28）

- 自动化测试：`node --test` 160/160。Maven `IllustrationRepositoryTest`、`IllustrationGalleryServiceTest`、`IllustrationGalleryControllerTest`、`DesktopServerIntegrationTest` 共 33 个测试全部通过。其中 H2 HTTP 回归用例覆盖 `@amamiya_yuki`、`@AMAMIYA_YUKI`、`@Amamiya_Yuki`、`AMAMIYA_YUKI`、`@amamiya`，每页 4 件跨页取完，结果与按原始行算出的同一非空集合一致。修复前该用例失败（`@AMAMIYA_YUKI` page 0 期望 10，实际 0），修复后通过。没有重跑 Java 全量测试。
- 浏览器验收：Codex 先用 ego-browser 完成主要验收并发现上述问题。修复后，Claude 用最新构建的 JAR，通过真实桌面启动器在隔离验收图库上用 ego-browser 复验：
  - 顶部输入 `@AMAMIYA_YUKI` 得 14 件，作者都是雨宫ゆき，显示筛选条和“找到 14 件”，地址同步；
  - 点 ✕ 清除后回到 30 / 55；
  - 输入 `@Amamiya_Yuki` 按 Enter 同样得 14 件，Esc 清空；
  - 作者弹层首屏 30 条，搜“千秋”后用 Enter 选中；标签弹层用鼠标选“海”，组合后得 9 件；
  - 看图器显示“01 / 9”，按 Esc 返回后条件和结果不变；
  - 深 / 浅色切换正常，“清除全部”后复原。
- 未验证：真实 MySQL 上的这条修复（MySQL 测试为显式开启，本次没有连接）；旧 `dist` 安装包尚未用新代码重新打包；真实中文输入法的手动输入。

### 单件编辑（2026-10-28）

复用现有的详情接口、作者 / 标签候选接口和 `PATCH /api/illustrations/{id}`。后端代码、数据库与 migration 都没有改动。

- **入口**：卡片上的“编辑”、看图器里的“编辑”、E 键（看图器中，或图库中先选中一件）走同一流程，在看图器右侧打开编辑面板（原型 B 已验收的布局，C 暖纸浅 / 深色）。可以修改标题、作者、标签、备注和来源链接，面板上有明确的保存与取消。
- **先读后改**：打开面板时强制重读完整详情，读完之前字段锁定，失败时显示原因并可重试，不会把没载入的字段当成空值保存。
- **只发改动**：只发送改动过的字段。文本去掉首尾空白，空白存为 `null`（与旧图库一致）；清空作者发 `null`，清空标签发 `[]`；标签比较不计顺序。成功响应为 204，不读取响应体。
- **作者与标签**：作者从已有的里单选，可在弹层的“不设置作者”或面板的“清空”中清空；标签从已有的里多选，可逐个移除。候选复用筛选的弹层，由服务端搜索、翻页。新建与管理作者 / 标签仍在旧图库。
- **保存与失败**：保存中字段和按钮都锁定，重复提交被忽略。失败时草稿原样保留，并显示原因（无法连接、会话失效、作品不存在、内容不符合要求、服务端错误），可以直接重试。
- **离开**：取消、Esc、关闭看图器、切换作品、点作者 / 标签离开，都走同一个“离开”检查。没有改动直接离开；有改动弹出“放弃未保存的修改？”（默认焦点在“继续编辑”，Esc 等同继续编辑）；保存中不放行。刷新或关闭页面时有浏览器离开提示。面板、确认框和候选弹层里的按键都不会触发图库快捷键；单行输入框里按 Enter 不会保存，Ctrl + Enter 才保存。
- **保存后同步**：
  - 重读这件作品的详情，卡片、看图器标题行与信息面板随之更新。
  - 有搜索或筛选时，按原条件从服务端重读已载入的各页：仍符合条件的作品沿用原对象（封面尺寸、详情不丢）；不再符合的移出结果，总数随之更新，条件保留，提示“已不符合当前的搜索或筛选条件”。看图器序号显示“—”，上一件 / 下一件从原位置接着翻。重读时正在路上的“载入更多”会作废，分页不会重复。
  - 同一件作品的多次详情读取只采用最后发出的那次；面板关掉后才到的详情不会写进面板。
- **措辞**：页面说明与旧图库入口不再标为“只读”；删除、导入、批量整理、排序仍未接入。
- **焦点与查询保护（Codex 提交前审核后补）**：
  - 看图器打开期间，顶栏、筛选行和作品墙设为 `inert`，Tab / Shift+Tab 不会进入背景，也无法在编辑时从背后改动搜索或筛选。候选弹层、确认框和提示条挂在 `body` 下，不受影响；按钮的键盘焦点提示保留。
  - 搜索、筛选换查询前先经过 `prepareQueryChange`：看图器开着就先关闭它，有未保存的编辑先确认，保存中不放行。被拒绝时不调用 `setQuery`，列表状态不动，搜索框回到当前条件。
  - 数据层另设兜底：编辑器登记 `setQueryGuard`，有未保存的修改或正在保存时，任何 `setQuery` 都被拒绝（发出 `blocked`），列表不清空。
  - 打开看图器时，作废还在停顿等待中的那次搜索。

#### 验收记录

- **自动化测试**：
  - `node --test`：179/179 通过（含焦点与查询保护的回归）。新增的测试覆盖：
    - PATCH 请求体（只发改动、`null` / `[]`、去空白、标签不计顺序）；
    - 读完详情才能编辑、204 不解析响应体、重复提交只发一次；
    - 失败保留草稿并可重试（400 / 404 / 500 / 断网）、取消不写入；
    - 筛选下保存后重读列表（作品移出、计数下降、无重复）、重读时作废进行中的翻页、详情读取后发先至；
    - `c-editor.test.js` 的界面层：有改动时的确认、Esc、按键不外泄、保存中锁定与拒绝离开、失败提示、作者 / 标签的选择与清空、离开后到达的详情不写入面板。
  - `DesktopServerIntegrationTest` 9 个测试全部通过。新增的 H2 HTTP 用例覆盖：
    - 只改所发字段、204 无响应体；
    - 清空作者和标签后该作品不再出现在原标签筛选中；
    - 未知作者、未知标签或 `tagIds: null` 时整个请求失败、数据不变；
    - 无会话和跨源的 PATCH 返回 403；
    - 重启后修改仍在。
- **浏览器验收（ego-browser）**：在隔离图库 `D:\ClaudeProjects\2026-10-28\c-gallery-edit-acceptance` 上，用本次构建的 JAR 经真实桌面启动器验收：
  - 卡片“编辑” → 有改动时按 Esc 弹出确认 → “继续编辑”保留草稿 → “取消” → “放弃修改”，服务端标题不变；
  - 按 E 进入编辑 → 改标题、清空作者、移除 暖色、通过弹层添加 夜空、改来源和备注 → Ctrl + Enter 保存 → 提示“已保存” → 看图器、卡片与服务端一致 → 重新打开读回保存值 → 刷新页面后仍在；
  - 选中卡片按 E 进入编辑 → 关闭启动器后保存，显示“无法连接…修改没有保存”且草稿保留 → 重新启动后重试成功；
  - 在“标签 = 夜空”筛选下移除该标签并保存 → 提示已不符合条件，序号“—/ 14” → 回到图库后为 14 件、无重复，条件保留；
  - 深色主题下的编辑面板与确认框正常，“放弃修改”的按钮颜色在浅色 / 深色下都正确。验收中发现暖纸主题的 `.btn` 盖掉了 `.btn-danger` 的底色，已在 `c.css` 中修复，并用重新构建的 JAR 复验。
  - 焦点与查询保护（重新构建 JAR 后复验）：
    - 编辑中各按 30 次 Shift+Tab 和 Tab，焦点只落在看图器、编辑面板或页面本身，从不进入背景；按钮有焦点提示，草稿不变。
    - 点信息面板里的标签离开时出现确认，“继续编辑”保留草稿，查询不变。
    - 搜索框输入后立刻从卡片进入编辑，排队的搜索没有触发，编辑器与草稿保留，搜索框回到当前条件。
    - 关闭看图器后背景恢复可用；放弃修改后服务端没有写入。
  - 回归测试：`setQueryGuard` 拒绝时列表、页码、总数、查询都不动；有草稿或保存中时换查询被拒绝，草稿保留；排队的搜索在打开看图器时作废；图库拒绝离开时（“继续编辑”或保存中），搜索和去掉筛选条都不改查询。JS 共 179 项通过。
- **未验证**：
  - 真实 MySQL；
  - 旧 `dist` 安装包（仍是 2026-10-27 的旧包，未重新打包）；
  - 真实中文输入法在编辑面板里的手动输入；
  - 循环牌堆、拖动浏览在本包之后的手感（代码路径未改，留给人工验收）。
- **已知**：未知作者 / 标签、`tagIds: null` 这类请求由现有 PATCH 接口返回 500（没有专门的错误映射），界面显示为“服务没有保存这次修改（500）……请刷新后重试”。本包不改后端。

### 本地导入（2026-10-28）

复用现有的 `POST /api/illustrations/import` 及其逐项结果。后端的格式校验、SHA-256 去重、事务与文件补偿都没有改动，也没有新 migration。

- **入口**：顶栏“导入作品”、Ctrl + O、空图库里的按钮，或者直接把外部 JPEG / PNG / GIF 文件拖进图库，都打开同一个导入面板（原型 B 已有的设计，C 暖纸浅 / 深色）。面板打开时背景为 `inert`。
- **队列**：
  - 每个文件单独发一次请求，按顺序逐个处理，同一时间只有一个请求；处理中再加入的文件排到队尾。不会因一次请求太大而超出上传限制，进度只按服务端的逐项结果推进。
  - 结果按请求对应到文件，不按文件名匹配：同名不同内容的文件各自导入，内容相同、名字不同的显示“图库中已存在”（不算错误）。
  - 部分失败不影响其他文件。服务端的英文原因翻译成中文显示（扩展名与内容不符、不是可识别的图片、超过 50 MB 等）。
  - 本地只预先拦下文件夹和超过 50 MB 的文件（与服务端上限一致），格式判断仍以服务端为准。
- **断网与重试**：
  - 连接中断或回应读不出来时，这一张标为“无法确认”（不说“未导入”），队列暂停，后面没发出的保持“未开始”，由用户选择“继续导入剩下的 N 张”。
  - “重试 N 张”只重新提交失败（可重试的）和无法确认的。已导入、已存在的永不重发；伪造图片这类内容不合格的不重试。重试一张其实已落库的，服务端按内容去重，会显示为“图库中已存在”。
  - 会话失效（403）同样暂停。导入中不能关闭面板，刷新或关闭页面时有离开提示。
  - 暂停时追加的文件进入队列、不自动发出；“继续”一并发出所有没发过的，“重试”的范围不变。
  - “无法确认”的文件重试后得到“图库中已存在”，说明上一次其实已经落库：统计仍算“已存在”，行内注明“之前那次其实已经导入”，同时作为这一轮新进图库的作品，同步列表、总数和“打开”入口。
  - Codex 复审后修复（2026-10-28）：队列没有可发的文件时（全部被本地拒绝或已暂停），处理循环会同步结束，结束标记随即被覆盖，队列卡在“处理中”，面板关不掉，后加的文件也不处理。现在处理循环先让出一拍再开始。
- **完成后**：
  - 按服务端结果重读已载入的列表（与编辑保存后同一机制），新作品在作品墙上高亮出现；面板里每个成功项都有“打开”，可直接进入看图器继续编辑。
  - 有搜索或筛选时条件保留。新作品不符合条件时说明“新导入的 N 件不符合当前的搜索或筛选条件”，并提供“清除条件查看”；“全部”旁的数字另读一次整库数量。
- **拖入的边界**：
  - 只接受带 `Files` 且不是本页发起的拖动。卡片拖动、循环换牌、拖动浏览都是指针事件，不是原生拖放；页面内发起的原生拖动另加标记排除。
  - 看图器、编辑确认框或快捷键说明开着时不导入，但仍拦下浏览器“直接打开这个文件”的默认行为，页面不会被换掉、草稿不会丢；放下时提示“看图或编辑时不能导入”。
  - 只是拖过、没有放下时，由拖动打开的空面板会自动收回。
- **措辞**：顶栏、空图库、快捷键说明不再说“导入请到旧图库”；删除、批量整理仍未接入。

#### 验收记录

- **自动化测试**：
  - `node --test`：194/194 通过。新增 15 项（含复审后补的 5 项回归：全部本地拒绝后队列空闲、面板可关闭并可继续添加；暂停后追加不卡死，继续 / 重试范围不变；“无法确认”重试得到“已存在”时同步列表、总数与“打开”。其中 3 项已确认在修复前会失败）：
    - 数据层：每个文件单独一次请求、不并发、按顺序；同名不按文件名去重；本地拒绝文件夹和超限文件；断网标为“无法确认”并暂停，“继续”只发未发出的，“重试”只重发失败与无法确认的；403 / 413 / 500 / 回应读不出来的处理；处理中追加文件；导入后列表刷新及筛选下的辨别。
    - 界面层（`c-import.test.js`）：导入中不能关闭；完成后刷新列表、高亮、“打开”进入看图器；筛选下的说明与“清除条件查看”；“无法确认”的提示与重试、继续的按钮；只接受外部文件拖入，看图器开着时拦下放下并提示。
  - `DesktopServerIntegrationTest` 10 个测试全部通过。新增的隔离 H2 HTTP 用例：
    - 逐个文件请求：同名不同内容各自成功；相同内容改名为重复，并指向已有作品；伪造图片、PNG 改名为 .jpg 失败；
    - 一次请求混合多个文件时，结果按文件顺序返回，失败不影响其他；
    - 作品、资源记录与原图、缩略图文件数一致，失败的没有留下记录或文件；
    - 无会话和跨源的导入请求返回 403，且没有写入。
  - 持久化与重启沿用已有用例。
- **独立 Review 与浏览器验收**：Codex 完成独立 Review 和浏览器复验，结论为通过（含复审后修复的三点：全部本地拒绝后可关闭并继续导入、暂停后追加文件不卡住、“无法确认”重试得到“已存在”时同步列表）。
- **未验证**：真实 MySQL；`dist` 仍是旧包；大文件（接近 50 MB）的上传耗时与界面表现。
- **已知（现有后端行为，本包未改）**：格式校验只核对文件头签名与扩展名是否一致，不检查图片能否完整解码。以 GIF 文件头开头的残缺 `.gif` 会被接受，之后在图库里显示为“图片无法载入”的占位。集成测试中已用断言记录这一现状。

### 单件删除（2026-10-28）

复用现有的 `DELETE /api/illustrations/{id}`：数据库记录在事务里删除（资源、标签关联级联），提交之后才清理原图与缩略图，顺序不变，没有 migration。

- **后端唯一改动**：`IllustrationNotFoundException` 加 `@ResponseStatus(NOT_FOUND)`。此前作品不存在时，读取详情、编辑、删除都返回 500，回执丢失后的“重新读取核实”永远得不到 404，再删一件已删的也只能算服务故障。经用户确认改为 404；其他服务故障仍是 500，访问保护仍先返回 403。
- **入口**：看图器“更多 → 删除作品…”、看图器里的 Delete、作品墙上键盘焦点所在卡片的 Delete，都打开同一个确认框（`c-delete.js`）。焦点不在卡片上时 Delete 只提示先选中；输入框、编辑面板、导入面板、快捷键说明、作者 / 标签弹层里的按键不触发删除。
- **确认框**：封面（多图作品叠两张纸）、作品名称、作者、“N 页，共 N 张图片”，写明记录和图片都会永久删除、无法恢复；编辑面板里有未保存的修改时另外说明会一并丢弃。默认焦点在“取消”，Tab 只在两个按钮之间切换，背后的看图器或图库设为 `inert`。取消、Esc、点背景都不发请求，当前作品与编辑草稿原样保留，焦点回到“更多”或原来的卡片。编辑正在保存时不能删除。
- **删除中**：两个按钮都锁定，焦点留在确认框上；重复点击、Esc、点背景都无效，同一件作品只发一次 DELETE。刷新或关闭页面时有离开提示。
- **结果**（只按服务端回应判断）：
  - 204：关闭确认框，从列表移除，关闭看图器回到图库（大图找不到原卡片，原地淡出；其余卡片按原有动效补位，可被下一次重排打断，减少动效时不位移）。焦点落在原位置的下一件，没有就上一件；图库因此变空时落在空状态的“导入作品”，筛选结果变空时落在“清除全部条件”。
  - 404：作品在这之前已不存在（例如在旧图库删掉了），同样移除，提示说明原因。
  - 403：本机访问保护在进入删除逻辑之前就拒绝了，确定没有删除，可重试。
  - 连接中断、5xx：不直接下结论，先重新读取详情核实。404 即已删除（提示注明“没有收到确切回应，已重新读取确认”）；200 即没有删除，说明原因并可重试；重新读取也失败时显示“无法确认是否已删除”，作品暂时留在列表里，只提供“重新核实”（只读取，不再删除）和“关闭”。
- **列表同步**：搜索与筛选条件保留，总数减一；有筛选时“全部”旁的数字另读一次整库数量。分页按偏移计算，前面少一件后面整体前移，所以下一次载入退回到已载入数量所在的那一页重读、按 id 去重，不漏项也不重复；移除前先等正在载入的那一页落地，迟到的结果不会把已删作品带回来。编辑后已移出筛选结果、仍停在看图器里的作品也能删除，序号与“下一件”从它原来的位置接着算。

#### 验收记录

- **自动化测试**：
  - `node --test src/test/js/*.test.js`：211/211 通过（新增 17 项）。
    - 数据层（`c-archive-client.test.js`，8 项）：204 不解析响应体也不重读；移除后下一页补上前移的作品，顺序与服务端一致、无重复；进行中的删除只发一次；删光后为空且全部载入；404 / 403 不重读；回执丢失时按重新读取的 404 / 200 / 失败 / 500 分别得到已删除 / 未删除 / 无法确认，且核实不会再发 DELETE；5xx 同样核实；移除等待正在载入的那一页；已移出筛选结果的作品移除时总数不变。
    - 界面层（`c-delete.test.js`，9 项）：确认框内容、默认焦点与背景 `inert`；取消 / Esc / 点背景不删除、保留草稿并还原焦点；保存中不能删除；删除中按钮锁定、重复点击与 Esc 无效、按键不外泄，成功后移除并提示；403 后可重试；无法确认时“重新核实”只读取、确认后移除；关闭“无法确认”保留作品；404 的说明；Tab 循环。
  - Java（本轮只跑相关类）：`DesktopServerIntegrationTest` 11、`IllustrationDeleteControllerTest` 3、`IllustrationDeleteServiceTest` 6、`IllustrationDetailControllerTest` 1、`IllustrationUpdateControllerTest` 6，共 27 项全部通过。新增的隔离 H2 HTTP 用例：无会话与跨源的 DELETE 返回 403 且记录、文件都不变；删除一件 3 页作品后，它的作品、资源、标签关联记录全部消失，磁盘上恰好少了这 3 张原图和 3 张缩略图，作者与标签本身保留；再删除与读取详情、编辑都返回 404，从未存在的 id 同样 404；再删一件单图作品；剩下的作品每页 4 件翻完、无重复，所有剩余资源的原图都能读取。控制器测试补上作品不存在为 404、其他异常不被改成 404。
  - 后端事务逻辑没有改动，未补回滚测试（已有的 `IllustrationDeleteServiceTest` 覆盖文件清理失败不影响结果）。
- **Codex 独立 Review**：数据、安全、核实结果与分页主流程通过；指出一个 P2 焦点问题——从作品墙按 Delete 删除成功后，焦点停在 `body`，没有落到下一件 / 上一件、“清除全部条件”或“导入作品”。根因：确认框先隐藏再通知图库移除作品，而浏览器不会在隐藏时立刻移走焦点，图库读到的焦点仍在已隐藏的确认框上，三项判断都没命中（看图器路径另有判断，所以正常）。修复（2026-10-28）：`c-delete.js` 成功关闭时先让确认框交出焦点（`blur`），图库照原逻辑安排；取消仍还原到原处，删除中的锁定不变。新增 `c-gallery-delete.test.js`（5 项）：在假 DOM 上同时运行真实的 `archive-client.js`、`c-gallery.js`、`c-delete.js`，覆盖作品墙删除后焦点落到下一件 / 上一件、唯一搜索结果删除后落到“清除全部条件”、删光后落到“导入作品”、取消回到原卡片、从看图器删除回到图库并聚焦补位的卡片；前 3 项已确认在修复前失败。`node --test src/test/js/*.test.js`：216/216 通过。
- **Codex 浏览器复验通过（2026-10-28）**：使用保存后最新 `target/classes`（未重建 JAR / `dist`）、全新隔离 H2 测试库与 ego-browser 任务 Space 12。真实 Delete / Tab / Enter 按键与页面点击验证：普通作品墙删除后焦点落到下一件，删除末尾项后落到上一件；删除唯一搜索结果后落到 `no-match-clear`，输入框和地址里的搜索条件保留；删除最后一件后落到 `empty-import`。取消按钮 / Esc 都回到原卡片且不发 DELETE，从看图器删除后回到图库并聚焦补位卡片。本轮独立相关 JS 测试 `node --test src/test/js/c-delete.test.js src/test/js/c-gallery-delete.test.js`：14/14 通过。其余已通过证据沿用，P2 已关闭，本包独立 Review 与浏览器验收通过；测试服务已正常退出，Space 12 已释放。
- **未验证**：真实 MySQL；`dist` 仍是旧包。

## 默认进入 C 图库与桌面包更新（2026-10-28）

- `LocalAccessFilter` 的会话入口写入 Cookie 后跳转 `/c/`（`LANDING_PATH`），首次启动与“打开图库”都直接进入 C 图库；旧图库 `/`、访问保护与 MySQL 运行方式不变。
- 测试：`DesktopServerIntegrationTest` 新增入口用例（隔离 H2）：像浏览器一样保留 Cookie、跟随跳转打开启动器的会话地址后落在 `/c/`；同一会话下旧图库可进入；缺失或错误令牌为 403 且不发 Cookie；无会话访问 `/c/`、`/` 和 API 都是 403。整个类 12/12 通过。
- 重新打包：确认旧包未运行后，旧目录与 ZIP 移到 `dist\previous-2026-10-27\` 作为回退副本。当时 IntelliJ 中有实例直接使用 `target\classes`，而脚本会删除它，所以把工作树复制到仓库外（不含 `config\`、`.env`、`target`、`dist`）运行同一脚本（JDK 17.0.19），再把产物放回 `dist`：目录约 170 MB，ZIP 约 84 MB；已核对包内 JAR 含本次跳转、单件删除与 404 改动。
- 回退：退出新包后删除 `dist\IllustrationArchivePoC` 与 ZIP，把 `dist\previous-2026-10-27\` 里的两项移回 `dist\`。数据目录不随包变化。
- 验收通过：Codex 独立 Review、包内核对与隔离浏览器入口验收通过；用户已确认双击 EXE 的实际体验通过。

## 视觉重做：静展（2026-10-30）

用户对 C 暖纸的精致度不满意，在可交互预览（[prototypes/desktop-ui/c-directions.html](../prototypes/desktop-ui/c-directions.html)，需要本机 8080 实例提供缩略图）里比较 4 个方向后选定“1+2 融合”：静展的克制层次 + 暗房的沉浸。`theme-quiet.css` 取代 `theme-paper.css`，HTML 上的 `data-look` 改为 `quiet`。

- **字**：去掉中文衬线（本机没有 Noto Serif SC，回退成 SimSun 加伪粗体，是发虚发重的主因）。只用 Segoe UI Variable + 微软雅黑，字号 12 / 13 / 14 / 16 / 20，字重 400 / 600。
- **色**：中性灰阶，唯一色相是群青（焦点、选中、已选条件、链接），红色只给删除。Node 核对对比度：muted 在所有底色上不低于 4.5:1。
- **说明**：数据里标题基本为空。作品墙有标题写标题、有作者写作者、都没有就不写；看图器标题依次取标题、作者、“未命名作品”，文件名只在信息栏。`displayTitle` 仍用于 aria-label 和删除提示。
- **看图器**：信息从底部宽条改为右侧栏（`--info` 由高度改为列宽），默认收起，画布用 `max(--editor, --info)` 让位；背景铺一点作品主色（`--tone`）。
- **玻璃**：保留，用于浮在内容上的控件，提饱和降到 1.08；折射保持关闭（`fx.js` 的 `LOCKED_CFG` 未改）。去掉纸纹和漂移光斑。
- **顶栏**：滚动后变玻璃，向下浏览时收起，向上滚动或有焦点时回来；累计移动超过 8px 才判断方向。
- **作品墙**：间距 10 / 6px，列宽目标 230 / 165。紧凑密度的说明悬停才出现。间距变小后牌堆改为绕中心小角度展开（同步改了 `fx.js` 的 `LAYER_BASE`、`SLOTS`），不压到相邻列的说明。交互逻辑未改。
- **隐藏未接入入口**：“选择”加 `hidden`，DOM 和 id 保留，接入时去掉即可。“最近导入 / 待整理”和排序框已由下一节取代。

## 未打标签与排序（2026-10-30）

后端在现有列表接口上加 `untagged`、`sort` 两个参数，SQL 仍在 `IllustrationGalleryMapper.xml`，没有新表、新列或 migration。

- **未打标签**：`NOT EXISTS` 查 `illustration_tag`，放在 count 和 page 共用的筛选片段里，两边结果一致；与关键词、作者是“且”。与选定标签互斥：页面上选标签退出“未打标签”，进入“未打标签”清掉已选标签；接口同传时结果为空。
- **排序**：`newest` / `oldest` 用 `illustration.created_at`（进入图库的时间），`title`、`author` 用现有字段；ORDER BY 是 XML 里的固定分支，参数只选分支，不拼 SQL。每种都以 `i.id` 收尾。没有标题（含空白标题）、没有作者的作品排在最后，组内按最新归档。同名的不同作者各成一组。
- **两库差异**：标题和作者排序跟随数据库排序规则，两边都不分大小写；中日文的先后在 MySQL 与 H2 上可能不同，但各自都稳定。
- **界面**：范围改为“全部 / 未打标签”，后者旁边是全库待打标签数（独立请求，编辑、导入、删除后更新）。排序是沿用弹层材质的菜单，非默认排序用群青标出。条件和排序都写进地址；清除条件保留排序。
- **列表一致性**：按标题或作者排序时，保存编辑会重读已载入的列表，作品移到新位置。导入后新作品可能排在未载入的部分，提示会写明，并提供“查看最新归档”，不再误报成“不符合条件”。
- **测试**：契约测试 13 项在 H2 和一次性 MySQL schema 上用同一份期望通过，覆盖组合、各排序跨页、同名与空白标题。`DesktopServerIntegrationTest` 经真实 HTTP 与隔离 H2 校验跨页和 400。JS 229 项。

## 批量整理（2026-10-30）

选择多件作品，统一改作者、增减标签。不提供批量删除：删除无法恢复，仍是一次一件。

- **后端**：`POST /api/illustrations/batch/metadata` 在一个事务里完成，全部生效或全部不生效；`/batch/tags` 读取所选作品上已有的标签及件数。用 JdbcTemplate（`IllustrationBatchRepository`），没有 schema / migration 改动。选择上限 500 件。
- **为什么不循环 PATCH**：单件 PATCH 的 `tagIds` 是整组替换，循环调用要先读每件的标签，中途失败还会留下改了一半的状态。批量接口按“添加 / 移除”提交，只有部分作品带着的标签不动它就不会被改掉。
- **可重试**：添加跳过已有的关联，移除跳过已没有的关联，改作者重复执行结果相同。连接中断时页面如实说明“无法确认”，可以直接重试。
- **选择模式**（`c-gallery.js`）：点“选择”、悬停卡片上的圆点、Ctrl / Shift + 点击都能进入；Ctrl + A 全选已载入，Shift + 方向键连续选择，选择时 Enter 切换当前作品、E 打开面板、Esc 退出。换查询时退出选择，看不见的作品不会留在选择里；移出结果的作品自动从选择里去掉。选择模式下不牵引、不拖动浏览（`fx.js` 已有）。
- **整理面板**（`c-batch.js`）：改动先写成草稿，看清再应用。已有标签显示件数（如 2/3），可以“补齐”或移除；没动的标签是中性色，要改的才是群青。失败时草稿保留。
- **测试**：契约测试 14 项在 H2 和一次性 MySQL schema 上通过，覆盖重复执行与只影响所选作品。`DesktopServerIntegrationTest` 经真实 HTTP 与隔离 H2 校验访问保护、三态作者、404 / 400，并用 CHECK 约束让标签写入在作者更新之后失败，证明 Spring 事务确实回滚。JS 247 项。

## 桌面产品化第一轮（2026-10-30）

方案与取舍见 [DESKTOP_ROADMAP.md](DESKTOP_ROADMAP.md)，这里记录实现要点和验证。

### 启动与退出

- 第一轮的 Edge `--app` 启动、SSE 关窗判断已被下文的 [SWT + WebView2 窗口](#swt--webview2-窗口2026-10-31) 取代，Edge 只作回退。
- 数据目录：新库用 `%LOCALAPPDATA%\IllustrationArchive`。已有 `IllustrationArchivePoC` 且新目录不存在时继续用旧目录，不移动任何文件。

### X

- 流程：设置页提交 Client ID → `XAuthorizationService` 绑定回调端口、生成 `state` 与 PKCE S256、用默认浏览器打开授权页 → 回调校验 `state` → `XTokenManager.connect` 换 token 并写入 `<home>\config\x-oauth-tokens.properties`。
- 伪造或无关的回调返回 400，不结束正在进行的登录。五分钟超时、取消、端口被占用、用户拒绝、X 拒绝、网络失败都有各自的说明。
- 公开客户端刷新：凭据文件带 `client_id` 时，刷新只发 `client_id`、不带 Authorization 头。文件里没有 `client_id` 时走原来的机密客户端逻辑，MySQL 方式不变。
- 断开：先删本机 token，再通知 X 撤销；通知失败时如实提示，可到 X 的“已连接的应用”撤销。
- `x.api.base-url` 由 `DesktopServer` 固定为官方地址，环境变量改不了；测试经 `start(..., overrides)` 指向假 X。token 地址由它派生。
- 代理：启动器设 `java.net.useSystemProxies=true`，`DesktopProxySelector` 在其上叠加设置页的手动代理，改动立即生效（JDK HttpClient 只在创建时读一次默认选择器，所以选择器本身是可变的）。回环地址永不走代理。

### MySQL 数据迁移（2026-10-30）

设计见 [路线文档](DESKTOP_ROADMAP.md#数据迁移只读旧库写新目录)。用包内运行时执行，在仓库根目录：

```powershell
# 试跑：内存数据库 + 只读媒体，一个文件都不写
& dist\IllustrationArchive\runtime\bin\java.exe -jar dist\IllustrationArchive\app\illustration-archive-0.0.1-SNAPSHOT.jar --migrate-from-mysql --config=config\application-local.properties
```

```powershell
# 真正复制到 %LOCALAPPDATA%\IllustrationArchive（已存在且非空时拒绝）
& dist\IllustrationArchive\runtime\bin\java.exe -jar dist\IllustrationArchive\app\illustration-archive-0.0.1-SNAPSHOT.jar --migrate-from-mysql --config=config\application-local.properties --execute
```

退出码：0 成功，2 有媒体问题（用 `--allow-problems` 才会继续），1 失败。`--target=<目录>` 可换目标。密码不会打印。

**要在普通终端里运行，不要从 Claude 桌面版里运行。** Claude 桌面版是 MSIX 应用，它启动的进程写 `%LOCALAPPDATA%` 时会被重定向到 `%LOCALAPPDATA%\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Local\`，这个进程自己看得到，从资源管理器启动的应用看不到。

### 新界面

- 外壳：`shell.js` 控制导航和待处理数量；原 MySQL 方式没有桌面接口，仍显示“旧图库”。带 `data-external` 的链接交给默认浏览器（应用窗口的配置里没有登录 X），`POST /api/desktop/open-external` 只放行 X 与 AI Studio 的 HTTPS 地址。
- X 收藏：选中后批量归档 / 跳过，或打开预览按 A / S 逐件处理；归档失败留在这一件并显示原因。**一次请求只归档一帖**，进度真实；断线时这一件标为无法确认，后面的不再发送。第一次同步前说明费用。
- 设置：X 账号、AI、网络、数据。API Key 只写不读。
- AI 分区在看图器信息栏，点击才上传，结果只在本次运行期间按作品记住。
- MP4：看图器用原生播放器，作品墙用静音 `<video>` 显示首帧。视频页的比例是占位的 4:5。

### 验证

| 范围 | 结果 |
| --- | --- |
| `DesktopXIntegrationTest` | 10/10。真实 Spring、Tomcat、文件 H2 + 本地假 X：连接不触发读取、同步幂等、Unsupported、跳过、归档事务；过期 token 无 Secret 刷新与轮换持久化；伪造 `state`、拒绝授权；断开时 X 不可达；429；Gemini 密钥即时生效且不回传；代理校验与回环；外部链接白名单；换页不退出、关页退出；事件流不拖慢停机；无会话与跨源 403 |
| `LegacyMigrationTest` | 7/7。ID、时间、BINARY、布尔、媒体；试跑不写文件；拒绝已有目录；哈希不符与缺失；路径穿越；版本不一致；不打印密码 |
| 一次性 MySQL schema | `LegacyMigrationMySqlTest` 1/1、`XLikeSyncMigrationMySqlTest` 1/1、`IllustrationGalleryReadMySqlTest` 14/14，无残留 schema |
| 全量 | `mvnw test` 459 tests，0 failures / errors，3 skipped；JS 256/256 |
| Edge 实测 | Edge 156：启动进程立刻退出，真实关窗（WM_CLOSE）2 秒内配置目录下进程清零 |
| ego-browser 自查 | 隔离 H2 + `-Dc.acceptance.inbox=14`：导航、X 收藏、预览、跳过、归档失败、批量条、授权等待与取消、无密钥提示、新建标签入口。不是完整验收 |
| 打包 | 只检查包内容：新类与页面在 JAR 内，运行时含 `jdk.httpserver`。**未启动** |
| 真实 MySQL 试跑（用户批准，2026-10-30） | 用包内 JAR 运行，4 秒，退出码 0，无问题项。全部行写入内存 H2：作者 18、标签 10、作品 31、资源 36、作品标签 31、X 条目 425、X 媒体 490、同步状态 2、已见游标 65。36 个原图（73.1 MB）全部有 SHA-256 记录且核对一致；磁盘上另有 32 张缩略图，正好对应 32 张 JPEG / PNG，没有未被引用的文件。前后媒体文件数与总字节不变，没有创建 `%LOCALAPPDATA%\IllustrationArchive`。这次只是试跑 |
| 真实 MySQL 复制（用户批准，2026-10-30） | `--execute` 写入 `%LOCALAPPDATA%\IllustrationArchive`，3 秒，退出码 0。另用独立的只读程序核对：9 张表逐行比较全部字段（含时间到毫秒、BINARY、布尔），都与 MySQL 完全一致；68 个文件（36 个原图 + 32 张缩略图，82,176,624 字节）逐个 SHA-256 一致。源媒体文件数与总字节不变。新目录没有 `config`，X 需要重新授权。这是当时的快照。**当时从 Claude 桌面版内运行，结果落在了 MSIX 私有目录**，用户的 EXE 看不到，回退到了 PoC 目录；随后经 `explorer.exe` 在容器外复制到真实的 `%USERPROFILE%\AppData\Local\IllustrationArchive`，69 个文件（媒体 + 数据库）SHA-256 全部一致。私有目录里的副本保留未删 |

用户验收（2026-10-30）：从资源管理器双击新包，独立窗口、导航、真实图库 31 件、X 收藏 384 件待整理都正常显示。

未验证：真实 X、真实 Gemini、完整启动器的其余路径（窗口、关窗退出、再次双击、20 秒回退）、代理实际连通、物理拖放、MP4 实际播放。

## X 收藏修复与启动图（2026-10-30）

- 启动：jpackage 加 `--icon` 和 `-splash:$APPDIR/splash.png`，启动图由 java 启动器在加载类之前显示，`DesktopSplash` 只写真实阶段。
- 静态资源：桌面模式 `Cache-Control: no-cache`。窗口配置跨版本保留，否则新 EXE 可能继续用旧脚本和页面。
- 页面：`no-referrer`、`small` / `large` 图片尺寸、读不到时的占位与重试、按 ID 复用卡片、预载下一件，设计见 [路线文档](DESKTOP_ROADMAP.md#x-图片cdn-不接受本机-referer)。

| 范围 | 结果 |
| --- | --- |
| 接口（隔离 H2，真实 384 条） | `inbox?page=0&size=40` P50 3.9 ms、P95 5.8 ms，响应 17 KB。后端不是瓶颈 |
| 第 1 页图片（CDN） | 总量 7.2 MB → 2.8 MB；解码 39.8 MPx → 13.1 MPx |
| 干净配置的 Edge | 旧页面 0/39 张加载（`ERR_BLOCKED_BY_ORB`）；新页面首屏范围内 29 张全部加载，其余待滚动懒加载 |
| 逐件跳过 | 卡片 `<img>` 保留 0/39 → 35/39（其余 4 件已跳过），下一件 21–26 ms 出现 |
| 真实窗口滚动 | Edge `--app` 180 fps，P50 5.6 ms，最差 11 ms。ego 里约 1000 ms 的帧在无图时也出现，是工具噪声 |
| 读不到的图 | 第 1–3 页 3 张 404、另 1 张 403，GET 与 `<img>` 结论一致，页面显示占位。结论只到“CDN 对匿名请求不可用” |
| 打包（隔离 home） | 图标、启动图、再次双击 0.7 s 交回运行中的实例、关窗 14 s 退出、无残留 |
| 测试 | Java 定向 18/18（含 `DesktopServerIntegrationTest` 14/14）；JS 259/259。未跑全量 `mvnw test` |

用户验收（2026-10-30，round2）：X 收藏能正常加载大量图片，翻页和浏览正常，GIF 能打开播放；仍读不到的媒体对应 X 上已删除、隐藏的帖子或被冻结的账号。

## SWT + WebView2 窗口（2026-10-31）

### 实现要点

- **启动**：`DesktopLauncher` 的主线程跑 SWT 事件循环。`DesktopWindow` 先以全透明打开 Shell 并创建 WebView2，服务就绪后加载会话地址，页面报告首屏就绪后才显现（加载完成后最多再等 700 ms，15 秒兜底），这样没有白屏闪烁，也不会先看到空墙；这段时间由 `-splash` 启动图覆盖。
- **关闭**：`SWT.Close` 保存窗口状态，事件循环结束后关闭 Spring 上下文、释放单实例锁、`System.exit`。
- **窗口状态**：`config\window.properties` 保存位置、大小、最大化和主题；保存的位置已不在任何显示器上时回到主显示器。主题由页面告知，所以下次启动在页面出现前就能用对标题栏颜色。
- **页面桥**：`BrowserFunction` 注入 `window.iaDesktop(action, value)`，目前有 `theme`、`open` 和 `ready`。`LinkPolicy` 让窗口只显示应用自己的页面：外部 http(s) 交给默认浏览器，其他（例如文件拖到空白处产生的 `file:`）直接拦下。
- **页面行为**（`shell.js`，仅在有 `iaDesktop` 时生效）：外部链接交给默认浏览器；拦下刷新、查找、打印、开发者工具等快捷键，保留 Ctrl+A / C / V / X / Z；输入框和选中文字保留右键菜单，其他地方不显示。“在新标签页打开原图”在窗口里隐藏，因为窗口没有标签页。
- **再次双击**：`instance.lock` 被占用时，用 `<home>\window\entry.txt` 里的令牌 `POST /api/desktop/show`，运行中的实例把窗口带到前台。请求明确不走代理。还在启动中时不做任何事，窗口会自己出现。
- **代理**：不传任何参数时 WebView2 继承 Windows 代理设置。设置里填了手动代理时，启动前经 `EdgeArgs` 传入，改动在下次打开应用时生效。
- **回退**：`new Browser(SWT.EDGE)` 失败时，用 `DesktopControls`（SWT 小窗）加 `BrowserWindow`（Edge / Chrome `--app`）或默认浏览器。
- **运行时**：`jlink` 自建（含 `java.exe` 和 CDS 基础归档，去掉 JDK 开发工具模块），再用 jpackage `--runtime-image` 打包。`jdeps` 列出的 `jdk.attach`、`jdk.jdi`、`jdk.jfr` 来自可选诊断路径，未打入。

### 验证

全部在隔离图库（真实 MySQL 只读复制）和干净的 WebView2 配置上进行，经调试端口读取页面状态，未操作键鼠。全程系统代理开启且设置未变，未打开 x.com，未调用 X API 或 Gemini。

| 范围 | 结果 |
| --- | --- |
| 全量 | `mvnw test`（JDK 21）462 tests，0 failures / errors，19 skipped（未配置的 MySQL 可选测试与 LocalStack）；JS 264/264 |
| 启动到页面就绪 | 3.0–3.4 s（原位、整体移动、zip 解压三种情况；之前约 8 s） |
| X 收藏 | 39 张里 37 张加载，2 张显示占位、重试和打开原帖；MP4 `readyState 4`；滚动 180 fps，P50 5.6 ms |
| 代理 | WebView2 的连接只有系统代理和本机回环地址；`127.0.0.1` 主服务直连 |
| 内存（工作集） | JVM 约 297 MB + WebView2 约 423 MB，6 个进程 |
| 其他 | 深色标题栏生效；数据文件夹能打开（AWT `Desktop` 与 SWT 同进程）；再次双击 0.6 s 交回运行中的实例；关窗后约 2 s 退出，无残留进程，H2 锁与 `entry.txt` 已清理 |
| 打包 | 文件夹 252 MB，zip 109 MB，`runtime\bin\java.exe` 可运行迁移工具 |

未验证：真实图库目录下的完整路径、物理拖放、文件选择框、右键与快捷键的实际手感、窗口位置恢复、缺少 WebView2 的回退、启动失败提示、真实 X 登录与同步、真实 Gemini。

## 品牌与动效打磨（2026-10-31）

### 改动

- **启动图**：换成不随主题变化的深墨卡片，因为启动图在任何代码运行前显示，读不到用户的深浅色。PNG 四周留 24 px 透明边放投影，右侧是压暗的牌堆。
- **启动进度**：`DesktopSplash` 改由守护线程绘制，字体加载不再占主线程。细线只朝当前阶段移动（服务启动趋近 70%，页面加载趋近 92%），到不了头；窗口出现时结束。
- **首屏就绪**：`load` 事件早于作品数据，所以 `c-gallery.js` 在第一批结果（含空图库和失败）之后调用 `AppShell.ready()`，经 `iaDesktop('ready', '')` 通知窗口。页面没发信号时，加载完成后最多再等 700 ms。作品区只在本次启动的第一屏淡入一次。没有做窗口整体淡入：WebView2 在分层窗口半透明时的渲染未经验证。
- **图标**：ICO 与窗口图标补 30、36 等尺寸（100% / 125% / 150% / 200% 缩放的任务栏是 24 / 30 / 36 / 48 px）。图案由用户从生成稿中选定：蓝色卡片叠留白装裱的作品，下角一枚朱砂印。原图抠图边缘有残渣，已重做透明边后作为母版；16–20 px 单独手绘。
- **共用动效**：新增 `c/motion.css`，三个页面都加载，放切页与主题过渡、弹层进出场、基础反馈、X 收藏动效和系统“减少动效”规则。弹簧曲线也从 `fx.css` 移到这里，图库特有的动效仍在 `fx.css`。
- **退场**：`hidden` 用 `transition-behavior: allow-discrete` 过渡，退场不用改脚本，时长写在 `[hidden]` 状态上。
- **主题切换**：`startViewTransition` 的回调下一帧才执行，所以 `CTheme.get()` 读单独记录的当前模式，页面代码不要直接读 `data-mode`。

### 验证

| 范围 | 结果 |
| --- | --- |
| JS | `node --test src/test/js/*.test.js` 272/272 |
| Java | `DesktopShellTest`、`DesktopServerIntegrationTest`（JDK 21）23/23；未跑全量 |
| 启动耗时 | 隔离的合成图库（55 件），从资源管理器交替启动 round3 与 round4 各 6 轮：到窗口显现的中位数 4266 ms → 4405 ms。服务就绪时间相同，服务就绪后到显现约 335 ms → 540 ms，低于 700 ms 上限路径，说明首屏信号生效 |
| 实机 | 启动图文字和进度线在 125% 缩放下位置正确，窗口显示完整的第一屏，关窗 0.3–1.6 s 退出，无残留进程 |

此前另有 4 轮测量，round4 出现过两次 5.8–6.1 s，日志和之后 6 轮都没有再现，原因未查明。

未验证：切页、弹层退场、X 收藏与主题切换的实际动效；深色主题下的启动观感；真实图库。

## 已知问题与后续候选

- Flyway 12.4 提示 H2 2.4.240 高于其验证版本，当前功能未受影响。
- 体积：CDS 归档约占 56 MB；可移除桌面模式用不到的 AWS SDK 依赖。
- 未覆盖：正式安装器 / 签名、自动更新、大图库下的 H2 性能。
- 导入校验只读图片头：文件头完好但内容截断的图片仍会通过。
- 手动代理改动后，X 收藏里的图片要重新打开应用才走新代理（图片由浏览器窗口直接加载）。
- 回调端口 47392 被占用时无法授权，页面会说明；没有备用端口，因为每多一个都要用户多登记一个回调地址。
