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
            │    ├─ Flyway：classpath:db/migration-h2（V1–V8 的 H2 等价版本）
            │    ├─ 媒体：<home>\media（数据库仍只存相对 storage_key）
            │    ├─ Tomcat 只监听 127.0.0.1（默认 47391，被占用时随机端口）
            │    └─ LocalAccessFilter：启动令牌 → SameSite=Strict Cookie
            └─ DesktopWindow：SWT Shell + WebView2 打开 http://127.0.0.1:<port>/desktop/session?token=…
                 └─ 写入会话 Cookie 后跳转到新版 C 图库 /c/（2026-10-28 起；此前为旧图库 /）
```

应用窗口和回退时的“打开图库”都走这个会话入口，所以都默认进入 `/c/`；旧图库仍在 `/`，二者共用同一个会话与访问保护；2026-10-31 起桌面版不再提供进入旧图库的入口。普通 MySQL 运行方式不加载这个过滤器，不受影响。

`<home>` 默认 `%LOCALAPPDATA%\IllustrationArchive`，可用环境变量 `ILLUSTRATION_ARCHIVE_DESKTOP_HOME` 或系统属性 `illustration-archive.desktop.home` 覆盖。日志在 `<home>\logs\desktop.log`。

### 关键设计

- **配置隔离**：所有隔离相关属性由 `DesktopServer` 以命令行参数传入（最高优先级）。原因是 `X_API_ENABLED`、`AI_ANALYSIS_ENABLED`、`SPRING_DATASOURCE_URL` 等环境变量经宽松绑定会覆盖 profile 文件。`spring.config.import` 改为 `${illustration-archive.local-config:./config/application-local.properties}`：MySQL 模式默认值不变，桌面模式指向 `<home>\config\desktop.properties`（可选，默认不存在），因此不会读取 `./config/application-local.properties`。
- **H2 迁移**：V2/V4/V6/V7 使用了 H2 不支持的 MySQL 多子句 `ALTER TABLE`，所以 `db/migration-h2` 提供语义等价的版本（现为 V1–V8），未改动任何已应用的 MySQL migration。MySQL `TEXT` 映射为 `VARCHAR(65535)`，使 `IGNORECASE` 生效，保持与 `utf8mb4_unicode_ci` 一致的大小写不敏感搜索和标签唯一性。**代价：若保留桌面 PoC，后续每个 schema 变更都要同时写一份 H2 版本。**
- **Repository 唯一改动**：H2 对 `RETURN_GENERATED_KEYS` 会把带默认值的列（`created_at` 等）一起作为生成键返回，`KeyHolder.getKey()` 因此失败。Illustration / Asset / Author / Tag 的插入改为按列名 `{"id"}` 取键；MySQL Connector/J 同样支持该写法。X 相关 Repository 不在 PoC 范围内，未改动（其中仍有 `UTC_TIMESTAMP` 等 MySQL 专用 SQL）。
- **本机访问保护**（无用户鉴权的前提下）：
  - 只绑定 `127.0.0.1`，局域网不可达；
  - 每次启动生成 256 位令牌，入口 URL 换成 `HttpOnly; SameSite=Strict` Cookie，之后所有页面、API、原图都要求该 Cookie——其他网站无法跨站读取或写入；
  - 校验 Host 必须是 `127.0.0.1:<port>` / `localhost:<port>`，防 DNS rebinding；
  - 非安全方法若带 Origin 必须同源，防同机其他端口（浏览器视为同站）的写请求；
  - 上一次启动的 Cookie 在重启后失效。
- **退出释放**：退出（询问框、托盘菜单或 Windows 结束会话，见[系统托盘](#系统托盘与弹窗收尾2026-10-31)）→ 托盘图标与 WebView2 随 `display.dispose()` 释放 → `context.close()`：Tomcat 优雅停机（最长 15 秒，等待进行中的导入）→ Hikari 关闭连接池 → H2 在最后一个连接关闭时关库（`DB_CLOSE_ON_EXIT=FALSE`，不依赖 H2 自己的关闭钩子）→ 释放单实例锁 → `System.exit`。
- **事务与文件补偿**：沿用现有服务逻辑，未改动。H2 在另一事务持有同 SHA-256 未提交行时，会在唯一索引上等待并在对方提交后报重复键，行为与 MySQL InnoDB 一致（已由测试验证）。

## 构建与运行

```powershell
.\scripts\build-desktop.ps1 -JavaHome "$HOME\.jdks\ms-21.0.11" -MavenArgs '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository'
```

产物（`dist/` 已被 Git 忽略）：

- `dist\IllustrationArchive\IllustrationArchive.exe`：双击运行；整个文件夹约 195 MB（应用约 46 MB，其余为 jlink 运行时，含 `java.exe` 和 CDS 基础归档）。
- `dist\IllustrationArchive-win64.zip`：约 93 MB，解压后即可运行，目标机无需安装 Java。

脚本不执行 `mvn clean`（运行中的本地实例可能占用 `target\` 下的文件），只删除 `target\classes` 和旧 jar；测试需单独运行。`dist` 里的应用正在运行时，脚本会拒绝覆盖，可用 `-OutputDir` 输出到别的目录。`-AppVersion` 写入 EXE 和镜像元数据，默认 `0.0.1`；要打安装器时必须显式传入，见[安装器](#安装器r312026-11-02)。

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

`-Djdk.net.unixdomain.tmpdir` 是环境绕行：在 Claude 或 Codex 桌面版宿主派生的进程里（包括 Windows-MCP 启动的程序），`AppData` 下的 AF_UNIX socket 能创建但无法连接，JDK 的 Selector / HttpClient 会报 `Unable to establish loopback connection`。JDK 把这个 socket 放在 `TEMP` 下，所以打包应用从宿主直接启动也会失败。从资源管理器启动的应用不受影响，GUI 验收要经 `explorer.exe <脚本>` 启动，实测见[安装器](#安装器r312026-11-02)的 R31-C2。

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

再加 `-Dc.acceptance.inbox=14` 会造假 Inbox；加 `-Dc.acceptance.syncState=recent-invalid`（或 `history-invalid`、`both-invalid`、`account-changed`、`unattributed`）会写入对应的同步进度，用于验收 X 收藏页的恢复入口。目录里没有 X 凭据，这些入口也只改本地数据库，不会访问 X。换一种状态要换一个新目录。

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

- 流程：设置页提交 Client ID → `XAuthorizationService` 绑定回调端口、生成 `state` 与 PKCE S256、用默认浏览器打开授权页 → 回调校验 `state` → `XTokenManager.exchange` 换 token → 这次登录仍是当前尝试时才 `install` 写入 `<home>\config\x-oauth-tokens.properties`，否则撤销（状态关系见 [DESKTOP_ROADMAP.md](DESKTOP_ROADMAP.md#x-登录用户自己的开发者应用--pkce)）。
- 回调在虚拟线程上处理：放在 `HttpServer` 的调度线程上时，换 token 期间端口释放不掉，重新开始会报端口被占用。
- 伪造或无关的回调返回 400，不结束正在进行的登录。五分钟超时、取消、端口被占用、用户拒绝、X 拒绝、网络失败都有各自的说明。
- 公开客户端刷新：凭据文件带 `client_id` 时，刷新只发 `client_id`、不带 Authorization 头。文件里没有 `client_id` 时走原来的机密客户端逻辑，MySQL 方式不变。
- 断开：先删本机 token，再通知 X 撤销；通知失败时如实提示，可到 X 的“已连接的应用”撤销。
- `x.api.base-url`、`illustration-archive.desktop.x-authorize-url`、`gemini.base-url` 由 `DesktopServer` 固定为官方地址，环境变量和本地配置都改不了；只有测试能经 `start(..., overrides)` 指向假服务。token 地址由 `x.api.base-url` 派生。
- 代理：启动器设 `java.net.useSystemProxies=true`，`DesktopProxySelector` 在其上叠加设置页的手动代理，改动立即生效（JDK HttpClient 只在创建时读一次默认选择器，所以选择器本身是可变的）。回环地址永不走代理。

### MySQL 数据迁移（2026-10-30）

设计见 [路线文档](DESKTOP_ROADMAP.md#数据迁移只读旧库写到旁边再替换)。用包内运行时执行，在仓库根目录：

```powershell
# 试跑：内存数据库 + 只读媒体，一个文件都不写
& dist\IllustrationArchive\runtime\bin\java.exe -jar dist\IllustrationArchive\app\illustration-archive-0.0.1-SNAPSHOT.jar --migrate-from-mysql --config=config\application-local.properties
```

```powershell
# 真正复制到 %LOCALAPPDATA%\IllustrationArchive（已存在且非空时拒绝）
& dist\IllustrationArchive\runtime\bin\java.exe -jar dist\IllustrationArchive\app\illustration-archive-0.0.1-SNAPSHOT.jar --migrate-from-mysql --config=config\application-local.properties --execute
```

退出码：0 成功，2 有媒体问题（用 `--allow-problems` 才会继续），1 失败。`--target=<目录>` 可换目标。自 R3.4 起支持 V7 来源（只在副本里升级），输出多一行 `schema`；相对的原图文件夹仍按运行目录解析（设置页则让用户选）。普通用户用设置页的“从旧版导入”。数据库 URL 只收不带凭据的普通地址与允许的参数，具体边界和签收记录见下文 [R3.4](#从旧版导入r342026-11-03)。

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
- **页面行为**（`shell.js`，仅在有 `iaDesktop` 时生效）：外部链接交给默认浏览器；拦下刷新、查找、打印、开发者工具等快捷键，保留 Ctrl+A / C / V / X / Z；输入框和选中文字保留右键菜单，其他地方不显示；作品图片的右键菜单见 R4.0。“在新标签页打开原图”在窗口里隐藏，因为窗口没有标签页。
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

## 系统托盘与弹窗收尾（2026-10-31）

### 关闭与托盘

- **选择**：`CloseAction`（ASK / TRAY / EXIT）存在 `settings.properties` 的 `window.close-action`，默认 ASK，读不懂的值也按 ASK。设置页“关闭窗口时”经 `PUT /api/desktop/settings/close-action` 修改。
- **询问**：`DesktopWindow` 执行 `CClose.ask()`，页面（`c-close.js`）先回 `asking`，选定后回 `tray` 或 `exit`；勾选“记住”时先保存再执行，保存失败照常关闭，下次再问。600 ms 内没有回应（旧图库、页面卡死）时用系统提示框，关闭按钮始终有反应。窗口最小化时先恢复，询问才看得见。
- **隐藏**：`shell.setVisible(false)` 不够。SWT 从不把 WebView2 设为不可见，隐藏后页面仍是 `visible`、180 fps。控制器没有公开接口，只能反射取 `Edge.controller` 调 `put_IsVisible`；失败时记录堆栈，功能照常。
- **恢复**：托盘左键、菜单“打开插画档案”、再次打开程序都走 `activate()`，先恢复页面再显示窗口；最大化状态保留。
- **退出**：询问框、托盘菜单走同一个 `DesktopLauncher.stop()`；Windows 注销或关机时 SWT 发 Display 的 `SWT.Close`，也接到这里，否则留在托盘的应用会被直接杀掉。进托盘时也保存窗口状态。
- **退出提醒**：询问框只提醒退出才会丢的内容：正在导入、编辑面板有未保存修改。记住“退出”后不再提醒。

### 导航与弹窗

- `<head>` 同步给 `<html>` 加 `is-desktop`，CSS 决定显示什么，导航不闪。桌面版只留“图库 / X 收藏 / 设置”，MySQL 方式保持应用名与“旧图库”。设置页去掉“打开旧版界面”：旧界面没有新版之外的功能，在应用窗口里也是死路。
- 弹窗：宽度只有 420 / 520 / 620，内边距 24，始终不透明，放不下时在内部滚动（960×600 下实测全部放得下）。玻璃只留给提示条和批量条，两者在 `prefers-reduced-transparency` 下回退实色。
- 费用确认：点背景取消、Tab 限制在两个按钮之间、归还焦点。快捷键：Tab 不跑到背后、归还焦点。编辑面板错误条与内边距对齐；设置页输入框与编辑面板同为 40 高、圆角 8；X 收藏残留的 6 圆角改为 8。

### 验证

| 项目 | 结果 |
| --- | --- |
| JS | `node --test` 全部 285/285，含新增 `c-close.test.js`、`c-settings.test.js` |
| Java（JDK 21） | `DesktopShellTest` 11、`DesktopXIntegrationTest` 11、`DesktopServerIntegrationTest` 14，共 36/36；未跑全量 |
| 打包程序（隔离图库，Explorer 启动） | 询问框显示；记住托盘后再关闭直接隐藏；`visibilityState` 为 hidden、rAF 停止；托盘左键与菜单恢复；再次打开恢复并到前台；最大化保持；最小化时先恢复再询问；旧图库上出现系统提示框，选“是”进托盘 |
| 退出 | 从询问框和隐藏状态退出，进程在等待循环的首次检查时已消失，WebView2 无残留，`entry.txt` 删除，实例锁可打开，`window.properties` 已写入 |
| 设置页（真实窗口） | “关闭窗口时”可见，切换后写入 `settings.properties` |
| ego-browser | 各弹窗在 960×600 与大窗口下的显示，Tab 与焦点归还 |

**未实测**：点击托盘菜单的“退出”（代码上与询问框的退出是同一个 `stop()`，图标移除由 `display.dispose()` 完成），Windows 注销 / 关机，Explorer 重启后图标恢复（SWT 处理），物理 Alt+F4，125% 以外缩放下的托盘图标。

调试技巧：设 `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=<端口>` 再启动，可以用 CDP 读页面状态，不用操作桌面。

## 对话框内部视觉统一（2026-10-31）

- **token**（`theme-quiet.css`，浅深各一份）：`--well` / `--well-hover` / `--well-edge` 是对话框里唯一的面，半透明，所以放在卡片和页面上都自然；`--dash` 表示可以放东西；`--accent-wash`、`--focus-ring` 表示正在起作用；`--danger-wash` 只给错误；`--scrim`、`--sheet-shadow` 给遮罩和对话框。
- **导入**：拖放区分成 `.dz-icon`、`.dz-title`、`.dz-hint` 和 `.btn`。悬停或按钮有键盘焦点时加深虚线；拖入时实线群青加焦点环，图标上浮 2 px；有文件后 `is-compact` 收成一行，说明在左、按钮在右。空状态隐藏没有内容的页脚。`#dropzone`、`#import-pick` 不变，导入逻辑未动。拖放区本身不可点击，这是原有行为，所以悬停不改光标和底色。
- **同类分叉**：输入框、作者选择、作品摘要、导入提示、编辑状态、关闭选项、设置页输入框改用凹面；添加标签改用 `--dash`；错误条统一为 `--danger-wash`；图标按钮悬停统一；对话框内 `kbd` 与小字不再用过淡的 `--faint`；缩略图圆角改为 8；设置页两个“保存”都是主按钮。`.btn` 显式用 `--ink`，不再继承外层的灰色。
- **玻璃**：对话框和编辑面板用 `--sheet-bg` 加 `--gl-blur-l`，遮罩只压暗。遮罩不能有 `backdrop-filter`，淡入淡出也不能用 `opacity`，否则里面的玻璃取不到页面，所以改走背景色。浅色 78%、深色 82% 不透明，通透程度一致；可读性不靠加厚玻璃，而由玻璃内的 `--sheet-muted` 保证，它在最坏背景上对比度不低于 4.5，由 `c-dialog-style.test.js` 计算核对。`--hover-edge` 让悬停边框不随小字加深。`prefers-reduced-transparency` 下回退到实色 `--card`。
- **验证**：JS 全量 290/290，新增 `c-dialog-style.test.js`，`c-import.test.js` 补 `is-compact` 进出；ego-browser 隔离图库上看过浅深色的导入空状态、拖入态（加状态类模拟）、列表态，以及快捷键、编辑、删除、设置。**物理拖放的实际手感未验证**。

## 多图滑动与升级缓存修复（2026-10-31）

- **升级后仍是旧页面**：`build-desktop.ps1` 把 jar 时间固定为常量（CDS 归档要校验它），Spring 把这个时间作为静态资源的 `Last-Modified`，WebView2 带 `If-Modified-Since` 校验时总得到 304。设 `spring.web.resources.cache.use-last-modified=false` 后没有校验依据，每次完整返回；本机回环上的代价可以忽略。旧缓存在下一次请求时被替换。`DesktopServerIntegrationTest` 校验没有 `Last-Modified`，且带 `If-Modified-Since` 仍返回 200。
- **多图闪烁**有三个来源：拖过原点就换方向，底下露出的牌在下一页和上一页之间来回换；落定时立刻露出 `.piece-open img`，新图还没解码，先显示上一页；叠卡用 `decoding=async`，第一次露出是空的。
- **处理**：`settleDeck` 先换图并等 `decode()`，最多 120 ms，等待期间再次按下时继续用这叠牌；`clearDeck` 是同步收尾，给打开看图器等必须马上结束的路径；叠卡改 `decoding=sync`。当时的方向锁（`chooseSide`）用限制操作掩盖问题，用户验收不通过，已由下一节取代。
- **验证**：`DesktopServerIntegrationTest` 14、`DesktopShellTest` 11 通过。旧缓存的隔离配置上启动新包：新导航、新导入框、`c-close.js` 都已生效。

## 多图牌堆连续可逆（2026-10-31）

- **目标**：用户提供的视频里，按住一叠牌可以左右来回拨，两张牌分开、交换前后、再合拢，全程能倒回去。
- **根因**：旧模型由 `side / step / dir` 决定牌序，方向一变底牌就换组，所以越过起点要么闪，要么只能锁方向。另外 `html:not(.fx-reduce).fx-stack .fx-layers img` 的 `.45s` 过渡优先级高于 `.piece.fx-decking .fx-layers img` 的 `transition: none`，拖动时每帧都被平滑，实测手和牌差约 100 px。
- **模型**（`c-deck-model.js`，纯函数）：连续位置 `t` 是“看到第几页”。每张牌的姿态只由它和 `t` 的差 `r` 决定，没有方向状态，往回拖就是倒放同一段动画。`r = ±1` 是右边的下一页和左边的上一页，所以换方向不用换底牌；`|r| = 0.5` 时两张牌分开超过一个卡宽，此时交换前后看不到穿插；其余页收在中间最底下，从这里转到另一侧。两张图时只有一个露出位置，`r = 1` 与 `r = -1` 姿态相同，绕圈时不跳。顶牌走到顶点的 80% 前 1:1 跟手，之后柔和减速。
- **手势**（`fx.js`）：`dragDeck` 只是 `t = t0 - dx / 每页像素`，长拖可以连续翻多页。`restingPage` 只在松手时按位置和速度选页：甩动顺着速度走，反向甩就是撤销，连着甩一下一页，否则过 28% 卡宽（至少 56 px）才翻页。牌在动时按下立即接管，不等手移动，从当前的 `t` 接着算；不移动就松手，继续去原来的终点。
- **验证**：JS 全量通过，新增 `c-deck-model.test.js`，覆盖同一 `t` 同一画面、任意相邻位置都不跳（2 / 3 / 4 / 5 / 8 张，跨起点和绕圈）、1:1 跟手、换方向底牌不变、不重叠时才换前后、角标与最前的牌一致、松手规则。`dist\round9` 上用真实 WebView2 和 CDP 可信鼠标事件：往返 98 步手与牌偏差 0 px、没有卡住的步数；2 / 3 / 4 张图各 9 条路径共 27 条全过（左拖回起点再右拖、不松手连续四圈、慢拖反悔、快甩、松手前反向甩、动画中抓住后拖回、动画中抓住后直接松手、连续快甩），逐帧记录的错页帧、空白帧、角标不一致都是 0，最终页码全部符合预期。**真实鼠标的手感和真实图库上的观感待用户验收。**
- **旋转后的实际范围（2026-11-01 修正）**：上面“分开超过一个卡宽”只比了牌心距离，没算旋转。竖图 H/W = 1.4 时两张牌的底角在交换时还重叠约 25 px，越细长越多；4 张及以上时，第 3、4 张也恰好在两张牌分开时互换前后，从中间的楔形缺口能看到。现在分开距离按转过之后的外框算（`(W cos θ + H sin θ) / 2 + 2 px`，θ 固定 6.25°），所以每页手要走的距离随卡片高度增加（240 × 336 的卡片从 278 px 变为约 310 px），手和牌仍是 1:1。`|r|` 从 1 到 1.5 时下层的牌淡出，到互换时已看不见；交换点附近抬起的影子收进牌内，否则影子会翻到另一张牌上。整页时上一页和下一页互换前后，这时只有顶边一道不到 3 px 高的窄条看得出，是静止位置决定的，保留。
- **最右列横向溢出（2026-11-01）**：Codex 复审发现，最右列的牌拖到最开时页面变宽（2245 → 2373 px），横向滚动条随拖动出现，可视高度少 17 px。旧版也有（溢出 116 px），分开距离变大后是 128 px。`.wall-wrap` 改为 `overflow-x: clip`：牌伸到窗口边就被裁掉，不再撑宽页面；不用 `hidden`，因为它会让纵向也变成滚动容器。

## 配置与连接收尾（2026-11-02）

本地图库开箱即用，X 与 AI 按需配置。状态怎么分、为什么没有“测试连接”，见 [路线文档](DESKTOP_ROADMAP.md#配置的三种状态)。

### 配置从哪来

| | 开发版（MySQL） | 桌面版 |
| --- | --- | --- |
| 本地配置文件 | `./config/application-local.properties`（可选，相对启动目录），环境变量优先 | `<home>\config\desktop.properties`（可选），但 X、Gemini、数据库、存储都被命令行参数固定，这个文件改不了它们 |
| X 凭据 | `X_OAUTH_CLIENT_ID` + `X_OAUTH_CLIENT_SECRET`，机密客户端 | 设置页的 Client ID，存 `<home>\config\settings.properties`；固定不用 Secret |
| X token | `./config/x-oauth-tokens.properties`（`x.oauth.credential-file` 可改），没有 `client_id` | `<home>\config\x-oauth-tokens.properties`，带 `client_id` 和 `login_id`，刷新时不用 Secret |
| Gemini Key | `GEMINI_API_KEY`，`AI_ANALYSIS_ENABLED` 控制开关 | 设置页保存到 `<home>\config\gemini-key.txt`，环境变量和配置文件都不读，无需重启 |

命令行参数是最高优先级的属性来源，所以环境变量进不来。`<home>` 默认是 `%LOCALAPPDATA%\IllustrationArchive`。两边互不读取，也不自动导入。`config` 里的文件没有加密。

### 旧配置能否复用

| 项目 | 结论 | 条件 |
| --- | --- | --- |
| Gemini Key | 可以，到设置页粘贴一次 | Key 没被停用，且符合 Google 现行的 Key 限制；所在地区、项目额度也要满足。第一次分析才知道 |
| X Client ID | 不一定 | 只有应用类型是 Native App、回调地址 `http://127.0.0.1:47392/x/callback` 已加入时才兼容。只有 Client ID 判断不了 |
| X Client Secret | 不用 | 桌面版不用；旧配置同时有 Secret，说明旧应用很可能是机密客户端，不能走 PKCE 公开客户端流程 |
| 旧 X token | 不能 | 属于机密客户端，桌面版没有 Secret，刷新不了；也不要手工复制，刷新时可能轮换，影响开发版 |

沿用同一个 X 账号时，换应用不影响同步进度：进度按 X 用户 ID 归属，不按 Client ID。

### 改动

- **状态拆开**：`Status` 增加 `savedLogin`（`NONE` / `USABLE` / `UNUSABLE`）。`state` 说这次登录，`savedLogin` 说磁盘上有什么。原来“重新授权等待中 / 失败”时 X 收藏把已有连接当成未连接，并隐藏“同步”。
- **不能用的 token 不再显示已连接**：以前只看文件是否存在。现在损坏的文件和没有 `client_id` 的开发版 token 都算 `UNUSABLE`，移除时不联系 X。
- **验证记录**：`IntegrationEvidence`，桌面版由 `DesktopEvidence` 写入 `settings.properties`，每条记录写明证明的是哪份凭据（`x.verified-login`、`ai.verified-key`）。只有完整成功的同步和分析才写，设置页的账号名只来自这里。
- **验证记录绑定凭据（Codex 复审 P2）**：原来靠换凭据后删除记录，`settings.properties` 单独写不进去时旧记录留了下来。现在读取时比对：`XTokenManager.install` 给每次登录生成 `login_id`，和 token 同一次原子写入，刷新时保留；Key 用 SHA-256 前 16 字节的指纹，不保存 Key 本身。对不上就不返回，不依赖删除成功，重启读到旧记录也一样。没有绑定信息的旧记录不显示。操作开始时取当前身份，结束时身份变了就不记，既不能为新凭据作证，也不能覆盖新凭据已有的记录。两种写入失败分开处理：记录成功写不进去，只记日志，操作本身仍算成功，下次真实成功再记；删除旧记录失败，也只记日志，它已经不算数，下次写入时覆盖。新凭据的保存不受设置文件影响。
- **保存失败**：`UncheckedIOException` 转成明确的 500 提示，输入保留可重试；`DesktopSettings` 写失败时内存回滚，读回的就是磁盘上的值。Client ID 先保存再结束旧尝试，保存失败不影响进行中的授权。X 已签发但本机存不下的 token 报 `SAVE_FAILED` 并撤销，原来误报为网络问题，且 token 不撤销。
- **设置页**：三行状态；开发者应用说明收进折叠区，只有从 X 收藏过来且没有 Client ID 时才展开；已连接时有“回到 X 收藏”“重新授权”；浏览器没打开时明说；一次只跑一个授权操作，防止连点开两个授权页。
- **X 收藏页**：提示卡按状态显示；等待授权时自己轮询，完成后出现“同步”，不会自动同步。
- **AI**：保存 / 替换 / 移除各有说法，保存只说“已保存在本机，尚未验证”。Gemini 拒绝请求时提示到设置检查，看图器给出入口。服务端提示去掉了“免费”和“后端配置”。
- **隔离**：授权页和 Gemini 地址也由 `DesktopServer` 固定，测试才能换。`FakeIntegrations` 是本机模拟的 X 和 Gemini。
- **打包**：`build-desktop.ps1` 打完包后运行 `check-desktop-package.ps1`，发现本地配置、凭据、图库、日志或内置凭据值就失败。

没有 schema / migration 改动，OAuth 与同步状态机未重写。

### 验证

| 层 | 结果 |
| --- | --- |
| Java 全量 | `mvnw test`（JDK 21）502 tests，0 failures / errors，19 skipped（默认关闭的 MySQL / S3 测试） |
| JS 全量 | `node --test` 389/389 |
| 绑定修复 | 新增设置文件单独不可写时的换 Key、重新授权、解锁重试、重启和移除凭据测试。撤掉绑定比较（X / AI）、迟到结果检查（X / AI）、刷新时保留登录 ID，这 5 处撤掉任一处，相关测试都失败。迟到结果检查第一次撤掉时测试没有失败，补了“不能覆盖新凭据已有记录”的断言后才会失败。用真实文件占用重放 Codex 的探针：换 Key 204、回调 200、状态 CONNECTED，旧 `verifiedAt` / 账号都不再返回；解锁后新的同步和分析正常记录 |
| 打包 | 用仓库副本构建，副本的 `config`、`data`、`media`、`storage`、`logs` 放了带标记的假凭据和假图库。包体和解压后的 zip 共 347 个文件、28654 个 jar 条目都没有这些文件，标记值也没有出现（含 `app.jsa`）。故意放入违规文件时检查会失败 |

覆盖：无配置时导入、浏览、搜索、编辑且不写配置、不外连；重新授权等待 / 失败时原登录可用；开发版 token 不算已连接；保存失败与重试；重启后读取配置与验证记录；密钥不进响应、`settings.properties` 和日志；保存 Key、授权都不触发同步或分析；环境变量和 `desktop.properties` 不能提供凭据或改地址。

全程只用隔离目录、假凭据和本机模拟服务。**未验证**：真实 X 授权、真实同步、真实 Gemini、原图库。

### 隔离验收

浏览器路径用模拟服务。服务不会自己开浏览器，授权链接从设置页复制，打开的是本机模拟页。在 Claude 的宿主里还要给 `-DargLine` 加 `-Djdk.net.unixdomain.tmpdir=<D: 路径>`。

```powershell
$h = 'D:\Temp\ia-config-20261102\acceptance-home'
.\mvnw.cmd '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository' '-Dtest=CGalleryAcceptanceServer' '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=-Dc.acceptance.home=$h -Dc.acceptance.images=24 -Dc.acceptance.inbox=8 -Dc.acceptance.fakeIntegrations=true" test
```

- 入口：`<home>\acceptance-session.txt` 是一次性地址；`<home>\acceptance-fakes.txt` 列出可用的假值。
- 假值：任意 Client ID 走通授权，`fake-rejected-client` 被 X 拒绝；`fake-good-key` 分析成功，`fake-quota-key` 触发限额，其他 Key 被拒绝。
- 退出：在 `<home>` 下新建 `acceptance-stop`，服务关闭并释放端口。

真实窗口用打包版：`dist\acceptance-20261102-config-r2\IllustrationArchive\IllustrationArchive.exe`，启动前设 `ILLUSTRATION_ARCHIVE_DESKTOP_HOME` 指向隔离目录，从资源管理器或 `.cmd` 启动。**打包版的地址是官方的，在 EXE 里不要点“在浏览器中授权”，也不要分析作品**：前者会打开真实 x.com，后者会把图片发给 Google。

## 安装器（R3.1，2026-11-02）

计划与验收出口见 [DESKTOP_ROADMAP](DESKTOP_ROADMAP.md#r31-安装器计划2026-11-02开发中)。A 级自动检查已通过；Codex C 级复验通过并签收 `f5ea537`（2026-11-02）；B 级待用户验收。

```powershell
.\scripts\build-desktop.ps1 -AppVersion <版本> -OutputDir <输出目录> ...
.\scripts\build-installer.ps1 -Image <输出目录>\IllustrationArchive -Version <版本> -Compiler <ISCC.exe> -Bootstrapper <MicrosoftEdgeWebview2Setup.exe> -OutputDir <目录>
.\scripts\test-installer.ps1 -WorkDir <新目录> -Compiler <ISCC.exe> -Bootstrapper <MicrosoftEdgeWebview2Setup.exe>
```

需要 Inno Setup 7（简体中文语言包从这一版起是官方自带）和微软官方的 Evergreen Bootstrapper。`build-installer.ps1` 在编译前拒绝：版本格式不对、与镜像或 EXE 不一致、Bootstrapper 不是有效的微软签名或不是 Edge Update 产品、镜像含隐藏文件（Inno 通配符会静默漏掉）、包内扫描不通过。

- **运行中阻断**：用 WMI 查 `IllustrationArchive.exe` 的实际路径，覆盖前台和托盘。安装时在改文件前查两次（WebView2 安装前后），卸载前查一次；读不到路径或查询失败都算“无法确认”并停止。启动器是两个同路径进程，没有命名互斥量，所以不用 `AppMutex`。
- **文件校验**：Inno 遇到替换失败的文件时提供“跳过”，之后仍报告成功。镜像的逐文件 SHA-256 清单嵌在安装器里，`ssPostInstall` 核对实际落盘的内容，不一致就报错、完成页改为未完成、退出码 9，不提供启动。检查放在这里而不是 `AfterInstall`，因为 Inno 会吞掉后者抛出的异常。
- **旧文件清理**：只按上一版记录的清单删除新版不再有的文件，路径带 `..`、盘符或绝对路径的行一律忽略，手动放进去的文件不动。清单只在校验通过后才写入 `{app}`，所以升级失败后重跑仍知道该删什么。
- **WebView2**：看 `pv`，缺失时先说明会联网下载，再以非提权方式运行 Bootstrapper，结果以复查 `pv` 为准，不看它的退出码。失败时程序文件未动，可重试或取消。
- **版本比较**：用 Inno 的 `StrToVersion` 按段比较，`1.10` 比 `1.9` 新。已安装版本读不出时不覆盖。
- **退出码**：0 成功；1 版本被拒绝；7 运行中、目录重叠或 WebView2 失败；9 文件未落盘。这几个都由 `test-installer.ps1` 断言。
- **只读属性**：镜像里的 `app.jsa` 和启动器是只读的，但 Inno 不带过来，安装后都不是，所以脚本不需要 `overwritereadonly`（有测试钉住这一点）。
- **测试隔离**：`test-installer.ps1` 用小 C# 假应用和假 Bootstrapper，有自己的 AppId、名称和注册表键，结束时删除，不启动真实应用。假 Bootstrapper 只证明分支，不能关闭 R3.5 的真实缺失环境验收。

已验证（2026-11-02，Inno Setup 7.1.1，编译 0 警告）：

| 项 | 结果 |
| --- | --- |
| 回归 | `test-installer.ps1` 66/66，约 1 分钟：输入检查 15 项；安装、同版重装、`1.10` 覆盖 `1.9`、拒绝降级、运行中升级 / 卸载阻断、其他路径不误拦、文件被占用、未落盘报 9、目录重叠、WebView2 各分支、卸载保留图库 |
| 变异测试 | 逐个破坏 11 处保护（`ignoreversion`、退出码 9、运行检查、卸载阻断、按文本比版本、允许降级、`pv` 为零、目录包含关系、清单路径越界、查询失败当未运行、信 Bootstrapper 退出码），全部被回归抓到 |
| 版本贯通 | 源码副本实际构建 `0.5.101`、`0.5.102`：EXE、`.jpackage.xml` 和安装器版本一致 |
| 真实包自查（隔离 home，D 盘目录，不算 C 级） | 347 个文件字节一致，102 个 JAR 时间戳未变；启动后 8863 个类来自动态 CDS 归档（只因构建目录还在，见 R32-C1）；前台和托盘态升级都退出 7、卸载都退出 1；正常退出后卸载干净，home 未变 |
| AUMID | SWT 3.135 字节码确认直接用 `Display.setAppName` 的值，与快捷方式的 `IllustrationArchive` 一致 |

**Codex C 级首轮（2026-11-02，目标 `ffd1983`）：未签收。** 证据与命令：`D:\Temp\ia-r31-codex-20261102\result.md`。交接副本的安装脚本与提交存在差异，后续从当前提交重新编译安装器（复用既有镜像，不重建 Java）；源码及候选哈希见证据目录 `candidate-provenance.json`。

- 已通过：D 盘 GUI 首装、同版重装、真实 101→102 覆盖、降级拒绝、运行中升级 / 卸载的“重试 / 取消”、HKCU 卸载项和实际快捷方式目标 / AUMID、卸载后重装。两个版本的程序文件全部同字节，102 个应用 JAR 时间戳匹配；102 动态 CDS 实际加载 8825 类（同样因为构建目录还在，见 R32-C1）。合成 2 作品的业务字段、媒体和假配置保留；停机时重装 / 升级前后整个 home 321 文件一致，卸载 / 再装前后 327 文件一致。
- **R31-C1 / P1（已修复并复验关闭）**：`build-installer.ps1` 原来无条件删除 `<OutputDir>\installer-stage`；输入镜像正好位于该目录时，会被删除后才报错。现在每次新建唯一目录，只清理自己建的内容。新增 3 项回归共 66/66，旧代码变异能抓到；Codex 同情形复验 7 个输入文件及调用方预存文件均保留，构建成功无临时残留，普通真实镜像构建也通过。
- **R31-C2 / P1（正常用户启动复验通过，移除阻塞）**：首轮宿主内默认 TEMP 下启动报 `Invalid argument: connect`。同一 JRE、同一 TEMP 路径对照：
  - 从 Claude 或 Codex 宿主进程树启动：socket 目录在 `AppData`（Local、Roaming、Temp）下失败，在 `%USERPROFILE%` 或 D 盘通过。
  - 从 Explorer 启动：以上位置全部通过，含带中文和空格的路径。
  - 真实打包应用从 Explorer 用默认 C 盘 TEMP 启动：窗口打开、接口响应、日志 0 条回环错误、关窗后进程归零。

  结论收窄为启动环境差异：当前 Claude/Codex 宿主内的失败不能直接归因于 C 盘 TEMP 或正常用户启动。Codex 在已安装 D 盘程序上，经 Explorer 和默认 C 盘 TEMP 独立复验：Selector、SWT/WebView2 窗口、两作品图库、HTTP 接口均通过，本次启动日志无回环错误，正常退出后进程归零。没有改 Java、全局 TEMP 或网络设置。底层机制仍未查明，不能直接归因于 MSIX 或文件重定向，也不能推广成所有 MSIX 应用的行为。

**Codex C 级复验签收（目标 `f5ea537`）**：只补上述两条受影响路径，其他首轮通过项复用；证据与命令 `D:\Temp\ia-r31-codex-r2-20261102\result.md`。测试安装已卸载，合成 home 与证据保留；未改变阶段或发布版本。
- GUI 已按规则委派 `ui_acceptance`。程序实际安装按用户要求仅用 D 盘；默认 C 盘路径只观察不安装。测试完已正常退出并卸载，保留合成 home / 候选 / 证据，未动原图库、已登录 X 副本或原有用户验收快捷方式。

未验证：别的用户运行同名进程时读不到路径的真实权限分支（“查询失败”自动分支已测）；R3.5 的无 Java、真实缺 WebView2、离线失败与 SmartScreen。默认 C 盘实际安装按用户指示排除；B 级体验仍由用户验收。

## 交付包（R3.2，2026-11-02）

计划与验收出口见 [DESKTOP_ROADMAP](DESKTOP_ROADMAP.md#r32-交付包计划2026-11-02版本与协议已定)。开发方 A 级通过；Codex C 级复验已签收 `3755d29` 的 `0.6.0` 候选（2026-11-03），R32-C1 已关闭。

```powershell
.\scripts\build-release.ps1 -Version 0.6.0 -Commit <提交> -JavaHome "$HOME\.jdks\ms-21.0.11" -Compiler <ISCC.exe> -Bootstrapper <MicrosoftEdgeWebview2Setup.exe> -WorkDir <仓库外的新目录> -MavenArgs '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository'
.\scripts\test-delivery.ps1 -WorkDir <新目录>
```

从 Claude 或 Codex 宿主里构建时，给这次进程把 `TEMP` / `TMP` 设到 D 盘，原因见[构建与运行](#构建与运行)里的 AF_UNIX 说明。产物：`<WorkDir>\delivery\IllustrationArchive-<版本>\` 是交付文件夹；`<WorkDir>\build-record.txt` 记录提交号、JDK、Inno 和 Bootstrapper 哈希，含本机信息，不交付。发新版要先改 `packaging/delivery/使用说明.txt` 第一行的版本号并提交。

- **版本来源**：设置页读 `jpackage.app-version` 系统属性，由 jpackage 写进 `.cfg`，读不到时显示“开发版”。
- **从提交构建**：`git archive` 导出到 `WorkDir\source`，构建脚本都在副本里运行，原仓库的 `target\classes` 不受影响。`build-release.ps1` 用的是工作区里这一份，其余脚本来自被导出的提交。
- **`.gitattributes`**：`packaging/delivery/**` 固定 CRLF，所以交付的文本和构建机的 `core.autocrlf` 无关。
- **第三方名单**：`license-maven-plugin` 的结果不等于实际随包内容。Boot 打包时去掉 8 个空 starter，又加入 POM 里没有的 `spring-boot-jarmode-tools`。所以 `build-third-party-notice.ps1` 以 `app\lib` 为准：JAR 没被描述或版本不符就拒绝，没随包的不列，jarmode 单独登记。新的许可证名称必须先加进脚本的映射表，这样新许可在打包前一定有人读过。
- **许可证全文**：27 个 JAR 内没有全文（Netty、SWT、Logback、H2 等），只列名字不够。Apache-2.0、EPL-2.0、MPL-2.0 官方全文存在 `packaging/delivery/licenses/`；SLF4J 的 MIT 和 MySQL 的 GPLv2 + FOSS Exception 构建时从 JAR 读取，读不到就拒绝。Logback、H2、Jakarta Annotations 的多重许可在文件里写明选用的一项。
- **组装**：`assemble-delivery.ps1` 先全部检查，再在临时目录组装并改名，失败不留半成品；同名文件夹已存在就拒绝。Inno 写入 EXE 的版本字符串带尾部空格，比较前要去掉，这一点是完整构建才暴露的，`test-delivery.ps1` 已复现。
- **微软声明**：`使用说明.txt` 和 `第三方许可.txt` 都带 SmartScreen 声明，这是 WebView2 Runtime 条款第 9 条的要求；`test-delivery.ps1` 钉住了这一点。

已验证（2026-11-02，Inno Setup 7.1.1，JDK 21.0.11）：

| 项 | 结果 |
| --- | --- |
| 设置页版本 | `DesktopXIntegrationTest#settingsShowTheVersionThePackagedAppWasBuiltWith` 通过：无属性显示“开发版”，有属性显示属性值。宿主内运行要加 `-DargLine=-Djdk.net.unixdomain.tmpdir=<D 盘目录>` |
| 回归 | `test-delivery.ps1` 70/70，无 Java 构建，几十秒：输入检查、校验值（含真实 `sha256sum -c`）、说明文件、第三方名单、`build-release.ps1` 的入口检查 |
| 完整试构建 | 一次性克隆里的提交，`build-release.ps1 -Version 0.6.0` 退出 0；`.cfg`、`.jpackage.xml`、程序 EXE、Setup 都是 `0.6.0`；347 个文件，包内扫描通过；101 个 JAR 全部覆盖；`sha256sum -c` 3 项 OK。这是试运行，不是候选，已删除；证据 `D:\Temp\ia-r32-20261102\result.md` |
| 未重跑 | `.iss` 与 `build-installer.ps1` 未改，不重跑 66 项安装回归；只改一处读取版本和一个测试，不重跑 Java / JS 全量 |

**Codex C 级首轮（2026-11-02，目标 `2bbfe9f`）：未签收，交回 Claude 修复一项 P2。** 结果与命令：`D:\Temp\ia-r32-codex-20261102\result.md`。

- 已通过：380 个导出源码文件与提交一致，264 个打包类/资源与编译输出一致；三项校验值、101 个库的许可清单覆盖通过；EXE、Setup、卸载项和真实设置窗口均显示 `0.6.0`。合成 home 从 `0.5.102` 覆盖升级后，2 作品业务字段、媒体哈希和假配置一致，安装前后停机 home 13 文件及目录不变。使用说明已逐节核对；真实新版窗口、数据目录及 2 作品显示通过。
- **R32-C1 / P2（已修复并复验关闭）**：首轮 `app/app.jsa` 带有 102 条构建目录绝对路径，原包扫描未检出。用户选定不再交付应用归档，新增二进制路径扫描；复验条件相应改为无应用归档、基础归档正常加载，见下文。
- GUI 已委派 `ui_acceptance`；中途按用户要求暂停，恢复后补完新版真实窗口。正常退出并卸载，程序目录/HKCU 项消失，最终停机 home 224 文件及目录不变，合成数据与证据保留。未重跑全量测试或 R3.1 安装矩阵，未动原图库或真实外部集成。Console/Network 未采集。

**R32-C1 修复（2026-11-03，用户选定去掉应用归档）**：证据 `D:\Temp\ia-r32-c1\result.md`。

- **根因比报告的更大**：JDK 21 校验动态归档时，要求训练时记录的每个 JAR 绝对路径在运行时仍然存在，不做目录重定位。用真实启动器实测：原地训练原地运行，8944 类来自归档；训练目录改名后从新位置运行，0 类，日志 `Required classpath entry does not exist`。所以打包时生成的 `app.jsa` 只在构建机、且构建目录还在时有效。R3.1 的“移动目录、解压后都有效”和两轮 C 级在安装目录看到的 8825–8863 类，都是因为构建目录恰好还在，用户电脑上一直是普通启动。
- **走不通的替代**：在 `app` 目录用相对路径训练，归档里没有本机路径，但 JVM 按当前工作目录解析相对路径，启动器的工作目录不固定，归档整体作废；用 `subst` 映射成中性盘符训练，映射撤掉后同样作废。
- **修复**：`build-desktop.ps1` 不再训练 `app.jsa`，jpackage 也不再传 `-XX:SharedArchiveFile`。Windows 包中 jlink 生成的 JDK 基础归档 `runtime\bin\server\classes.jsa` 保留，移到安装目录仍然加载。新用户电脑上本来无法使用构建机的应用归档；本机旧构建目录仍在时获得的动态加速则不再提供，程序目录小约 56 MB。jar 固定时间戳保留，静态资源不用 `Last-Modified` 的处理照旧。`DesktopClassDataTraining` 和它的测试保留，供后续安装后训练方案使用，本轮未实现该功能。
- **包内扫描**：`check-desktop-package.ps1` 读每个文件的字节，查镜像目录、源码目录和用户目录，覆盖 UTF-8、ANSI 代码页、UTF-16（含奇数偏移）、正反斜杠和大小写。真实候选只报出 `app\app.jsa`，全镜像约 2 秒。`test-delivery.ps1` 加 10 项，共 80/80。

**Codex C 级复验签收（2026-11-03，目标 `3755d29`）**：只补 C1 及新候选路径，其他首轮通过项复用；证据与实际命令 `D:\Temp\ia-r32-codex-r2-20261103\result.md`。

- 新候选 380 源文件与提交一致，264 编译类/资源与输出一致；三项校验值、101 库许可覆盖通过。新扫描通过 346 文件，旧候选按预期只报 `app.jsa`；镜像及实际安装无应用归档，配置无 `SharedArchiveFile`。安装路径的 JDK 基础归档实测加载 1311 类，无 CDS 警告。
- EXE、Setup、HKCU 卸载项、真实设置窗口均为 `0.6.0`；GUI 已委派 `ui_acceptance`，两作品及隔离目录正常，本次启动日志无相关错误。正常退出并卸载，停机 home 243 文件及目录未变，数据/候选/证据保留。未重跑全量测试或完整安装矩阵；Console/Network 未采集。

微软条款正文没有写明 Bootstrapper 是否算在“the software”内，分发依据是分发文档的“package it with your WebView2 app”。这些是公开发布前的判断，不在本包；B/D 与 R3.5 保留项未关闭。

## 备份与恢复（R3.3，2026-11-03）

计划见 [DESKTOP_ROADMAP](DESKTOP_ROADMAP.md#r33-备份恢复计划)。设置 → 数据 → “创建备份…” / “从备份恢复…”；图库打不开时，启动失败窗口也有“从备份恢复…”。没有新 migration，Repository 未改。

| 类 | 作用 |
| --- | --- |
| `LibraryBackup` | 格式 1：`data/archive.mv.db` + 数据库引用的原图 + 已有缩略图 + `manifest.json`（大小、SHA-256、Flyway 历史、数量，无本机路径）。白名单收集，`config`、日志、`webview2`、`window`、孤立文件都不进。目的地按真实路径判断不在 home 内（junction 也算），不覆盖已有文件；先写同目录临时文件，完整读回校验后才改名。原图缺失或与入库 SHA-256 不符就失败。`read()` 是唯一入口：路径白名单（拒绝 `..`、绝对 / 盘符 / ADS、设备名、尾点、大小写冲突、重复、清单外条目），按声明大小截断读取，逐项比对哈希，条目与清单大小有上限，解包前查剩余空间 |
| `LibraryRestore` | `prepare` 在 `<home>/recovery/<id>/incoming` 解包校验，图库照常可用：H2 主次版本一致、清单与真实 Flyway 历史一致、无未知（更新）或失败的 migration；旧 schema 只在暂存副本上 `migrate()`（同时校验 checksum）；数量与原图引用再核一次。`switchTo` 是四次同卷改名（旧 `data`/`media` → `previous`，暂存 → 原位），每步前后落盘 `journal.properties`（整文件替换）；改名失败按已知步数当场回退。`recover` 在启动器取得实例锁后、打开 H2 前运行：未用的暂存删除，未完成或未确认的切换按记录 + 实际目录回退，两者对不上就拒绝启动并给出路径。`previous` 永不自动删除 |
| `MaintenanceGate` | 过滤器：关闭后所有请求 503，并等已进入的请求返回（导入、删除、整理、X 同步及其文件补偿都在请求内完成）。超时 60 秒或取消就重新打开，图库没被动过。不依赖 Spring graceful shutdown 的超时 |
| `LibraryMaintenance` | 启动器持有，跨 Spring 重启：一次一项；备份 = 排空 → `DesktopServer.stop`（确认 Hikari 已关）→ 写 ZIP → 重启；恢复 = 检查（后台，页面轮询）→ 用户确认操作号 → 排空 → 停 → 切换 → 启动 → 核对作品数 → `DONE`；新库打不开就停、回退、重开旧库；回退未完成（`MaintenanceException.isStuck`）就什么都不打开。恢复后的服务以关闭的闸门启动，核对与 `DONE` 落盘后才放行。X 授权进行中拒绝：接口入口查一次，关闸排空后、停服前再查一次 |
| `MaintenanceController` | `/api/desktop/backup`、`/api/desktop/restore{,/check,/cancel,/apply}`；文件只在原生对话框里选，页面拿不到路径 |
| `MaintenanceWindow` | 服务关闭期间的原生进度 / 结果窗口，对应用窗口模态；切换期间关闭按钮无效，只在能保持原样的阶段给“取消” |

- **会话**：启动器整个进程用一个访问令牌，重启后窗口的 Cookie 仍有效，结束后直接回到 `/c/settings.html#data`。退出（托盘、关机）在维护进行中会等结果；崩溃由下次启动的 `recover` 处理。
- **启动失败**：原来只弹错误框然后退出；现在是原生窗口，默认“退出”，另有“从备份恢复…”。恢复引擎不依赖 Spring，不触发 X / Gemini；新库也打不开时退回原库并如实说明。
- **取舍**：停机快照，期间图库暂停使用；每次完整备份、整库替换；只接受本工具的 ZIP 和同主次版本的 H2。备份不加密，含全部作品，说明里不称其可安全分享。

验证（2026-11-03，JDK 21.0.11）：

| 项 | 结果 |
| --- | --- |
| 全量 | Java 549 tests，0 failures，19 skipped（默认关闭的 MySQL / S3）；JS 397/397。宿主内要加 `-DargLine=-Djava.io.tmpdir=<D 盘> -Djdk.net.unixdomain.tmpdir=<D 盘>` 并关闭沙箱 |
| `LibraryBackupTest` 38 | 包内只有白名单条目、无测试凭据 / 日志 / 浏览器资料、清单无本机路径；home 内（含 junction）与同名文件拒绝；原图缺失 / 被改、数据库被占用、取消都不留文件；往返后行、ID、关系、同步归属、原图哈希一致，`config` 字节不变，旧库保留；17 种危险或损坏 ZIP 拒绝且不留暂存；更新的 schema、checksum 冲突拒绝；V7 备份只在暂存副本升到 V8；中断在 4 次改名任一步（改名前后）启动时回退；记录与目录冲突时停止；改名失败当场回退 |
| `LibraryMaintenanceTest` 6 | 真实 Spring / HTTP / H2：页面接口备份 → 改库 → 检查 → 只接受检查返回的 id → 恢复，同一 Cookie 跨重启可用；一次一项；真正打不开的合成库从备份恢复；新库启动失败退回旧库并重开；备份失败仍重开；闸门关闭时 503；X 授权进行中拒绝 |
| `MaintenanceGateTest` 2 | 等待在途请求、期间新请求 503；超时和取消返回 false，重新打开后放行 |
| 隔离真实窗口自查 | `D:\Temp\ia-r33-20261103\result.md`：经资源管理器启动 jar，Windows-MCP 走完设置页备份、恢复和库损坏后的启动失败恢复。发现并修复 3 处：恢复后页面不刷新（只差 `#data`）、恢复完成窗被主窗口盖住、失败原因只有异常类名。自查不算验收 |

### R3.3 C 级审核（2026-11-03，`e60d609`）

**首轮发现 4 项，现已在 `22184f0` 复验关闭并签收。** 首轮独立复现、命令和修复出口见 `D:\Temp\ia-r33-c-20261103\result.md`；复验证据见下节。Codex 未改产品源码，所有图库均为本轮新建合成数据，未动真实数据、开发/B 级副本或网络配置，未调用真实 X / Gemini。

| 编号 | 问题与实际结果 | 修复出口 |
| --- | --- | --- |
| R33-C1 / P1 | `LibraryMaintenance.runRestore` 对 `switchTo` 的异常无条件重开；注入正向改名失败且回退也失败后，previous 有 3 件作品，当前却创建空库并返回 `libraryOpen=true` | 回退不完整时停止启动并保留材料；只在确认原库完整就位后重开 |
| R33-C2 / P1 | 新 Spring 上下文的闸门默认开放；在恢复数量检查及 `DONE` 前，真实 HTTP 删除返回 204，随后数量不符触发回退，已确认的删除消失 | 维护状态跨重启，确认落盘前继续拒绝业务请求 |
| R33-C3 / P2 | 原生保存框期间由另一 HTTP 请求启动本地假 OAuth，达到 `WAITING`；备份仍成功停机，重启后新授权变为 `DISCONNECTED` | 关闸排空后、停机前协调授权状态，不能静默丢弃后来开始的授权 |
| R33-C4 / P2 | ZIP 清单尺寸接近 `Long.MAX_VALUE` 时，`bytes + SPACE_MARGIN` 溢出，绕过剩余空间检查并开始解包 | 安全比较/检查加法溢出，写文件前拒绝异常尺寸；只用 64 KiB 内容复现，未做磁盘耗尽实验 |

- 独立自动验证：提交导出副本的 `LibraryBackupTest,LibraryMaintenanceTest,MaintenanceGateTest,DesktopShellTest` **57/57**；复用开发方全量结果。4 个补充探针加载打包内未修改的产品类，确认上述缺陷，不计作产品通过。
- 打包：从 `git archive e60d609` 的仓库外副本构建 jpackage `0.6.0` 测试镜像，构建与包检查 **346 文件 / 28693 JAR 条目通过**；未安装，复用已有安装验收。
- GUI：委派命名角色 `ui_acceptance`，Windows-MCP 验证原生备份、修改标题后整库恢复、真实损坏合成库的启动失败恢复，**3/3 PASS**。同窗口会话可用，previous 保留修改和损坏库；损坏文件哈希与 config 合成标记哈希核对通过。见 `evidence\gui-result.md`（同一证据根目录）。应用正常退出、控制权已释放；Console/Network 未采集，不构成整包签收。

**R33-C1–C4 修复（2026-11-03，Claude）**：

| 编号 | 修复 | 回归 |
| --- | --- | --- |
| C1 | 回退失败、记录冲突和读不了记录都抛 `MaintenanceException.stuck`；`runRestore` 遇到就返回 `stuck` 结果，不再重开；启动器只给“退出”，不给“从备份恢复”。下次启动由 `recover` 按记录续做回退。切换的改名经 `LibraryRestore.Mover` 传入（应用固定 `LibraryRestore::rename`，不覆盖目标并重试短暂占用），以便确定性注入故障 | `LibraryBackupTest#anUndoThatAlsoFails…`、`LibraryMaintenanceTest#aSwitchThatCannotBeUndone…`：第 2 次改名与回退都失败 → 不开服务、不建空库、旧库在 previous，`recover` 后 1 件作品回来 |
| C2 | `MaintenanceGate` 可按 `illustration-archive.desktop.start-closed` 以关闭状态启动；恢复后的服务用它启动，核对数量、写 `DONE` 之后才 `open()`；核对失败时停服回退，期间没有请求进入 | `aRestoredLibraryAnswersNoRequestUntilItIsConfirmed`（启动后立即真实 DELETE → 503，确认后 DELETE 生效）、`aRestoredLibraryThatDoesNotCheckOut…`（503 后回退，旧库 2 件和标签都在）、`MaintenanceGateTest#aGateThatStartsClosed…` |
| C3 | `quiesce` 在关闸排空后、停服前查 `loginInProgress()`，有则重新开闸并拒绝；关闸后不会有新授权开始，所以这个回答到停服前都成立。备份和恢复共用这条路径 | `aLoginStartedWhileTheSaveDialogIsOpen…`（保存框期间真实 HTTP 开始授权 → 备份拒绝、授权仍 WAITING、无文件）、`aLoginStartedBeforeARestoreIsApplied…`（恢复拒绝、备份仍待确认） |
| C4 | `requireSpace` 改用 `bytes > usable - SPACE_MARGIN`，负数同样拒绝；提示里的所需空间也不再溢出 | `aDeclaredSizeNearTheLimitCannotWrap…`（`MAX-边距+1`、`MAX`、`-1`）、危险 ZIP 新增 `hugeSize`：解包前拒绝、不留暂存 |
| 修复中新发现 | 一次全量里，刚写的 `journal.properties` 被替换时 Windows 报 `AccessDeniedException`（多半是杀毒或索引短暂占用），回退也写不了记录 → C1 的新保护正确拒绝打开。日志和切换改名现在遇到 `AccessDeniedException` 会重试约 2 秒（`LibraryRestore.retrying`），之后仍失败才算这一步失败 | 该测试（`aLibraryThatDoesNotOpen…`）之后的全量与两轮单跑都通过；偶发问题无法确定性复现 |

验证（修复后）：针对性 `LibraryBackupTest` 41、`LibraryMaintenanceTest` 11、`MaintenanceGateTest` 3、`DesktopShellTest` 11、`DesktopServerIntegrationTest` 15、`DesktopXIntegrationTest` 45 通过；全量 Java 558 tests，19 skipped，1 error 为下方已知的 `LegacyMigrationTest` 偶发改名 `AccessDenied`，与 R3.3 无关，单跑两轮均通过；JS 未改。

### R3.3 C 级复验签收（2026-11-03，`22184f0`）

**签收，R33-C1–C4 全部关闭。** 只复验修复和受影响生命周期，未扩大包范围；命令、探针源码和日志见 `D:\Temp\ia-r33-recheck-20261103\result.md`。

- 从指定提交导出到仓库外测试，以上 6 类相关测试 **126/126、0 failures / errors / skipped，BUILD SUCCESS**；未重跑全量 Java / JS。
- 独立探针 **7/7 PASS**，加载提交编译出的未修改产品类，使用真实 Spring / HTTP / H2 和合成媒体：C1 正向与回退改名各持续拒绝 10 次，服务保持关闭、不建空库，下一次 `recover` 后 3 件作品及备份后标签保留；C2 确认前 DELETE=503、`DONE` 后 DELETE=204；另注入 `DONE` 替换持续失败，完整回退且旧库可用。
- C3 经真实备份 HTTP 请求在保存框期间开始本地假授权，维护拒绝、同一上下文仍为 WAITING；另让授权请求在闸门内阻塞，维护等待排空，后来的授权=503，首个授权完成后仍拒绝停服。C4 溢出清单 ZIP 在写入任何解包文件前拒绝。
- 重试边界：仅 `AccessDeniedException` 重试；短暂失败可恢复，持续失败 10 次约 1.8 秒后抛出；其他 IOException 不重试；线程中断保留并终止等待；改名不覆盖既有目标。
- 复用首轮包检查、安装与正常 GUI 3/3 证据；本轮没有重打包或操作 GUI。新增 stuck 窗口“仅退出”分支已静态核对，视觉未另验。既有 B 级项不变，原图库、真实备份与外部调用未授权事项不变。相关进程均正常退出；只更新验收文档，未提交或推送。

## 从旧版导入（R3.4，2026-11-03）

开发提交 `e0a477c`，修复提交 `806f7a5`；**Codex C 级已签收，R34-C1 / C2 全部关闭。** 设置页选旧版配置，后台把源版本的表复制到 `recovery/<id>/incoming`，只在副本升级至 V8，确认后复用 R3.3 切换与回退；当前图库保留在 `previous`，不导入旧 X token。命令行目的地改名也改用 R3.3 的短暂重试。规划基线见 [路线](DESKTOP_ROADMAP.md#r34-旧版导入计划)。

- 开发方：Java 全量 569 tests / 20 skipped、JS 402/402；一次性 MySQL 8.4.11 的迁移 2/2、图库读取 14/14，证据 `D:\Temp\ia-r34-mysql-20261103-174712\result.md`。
- Codex 自动测试：相关 6 类 **98/98、零失败 / 错误 / 跳过**，不重复开发方全量。独立 MySQL V7 / V8 逐表、ID、关系、时间、二进制和媒体哈希一致；四步正向改名各注入一次失败，均完整回退。
- 打包与真实 GUI：从指定提交导出后构建内部 0.6.0 镜像，扫描 346 files / 28707 jar entries 通过。命名 `ui_acceptance` 用 Windows-MCP 验证媒体问题拒绝、V7 取消、V7 应用并退出重启、V8 再次替换与正常退出通过；独立核对当前 V8、两次 previous 和原有 config 字节一致，journal 均 DONE。DOM / Console / Network 未采集。
- 源库只读：V7 / V8 的行、迁移历史与媒体前后不变；SELECT-only 用户，服务端记录 12 次 READ ONLY、12 次 REPEATABLE READ、24 次 rollback、无变更 SQL。R3.3 已签收的关闸、确认落盘、双重改名失败和授权竞争证据复用。

| 已关闭问题 | 修复与独立复验 |
| --- | --- |
| **R34-C1 [P1] URL 密码进入预览** | 普通地址白名单拒绝 user-info、address 语法、编码及凭据参数；原来的三种实际密码写法在打包类的真实 HTTP 上均 409，无源库连接、暂存或密码回显 |
| **R34-C2 [P2] 单独参数值未脱敏** | 仅接受 12 个固定格式参数，原 defaultFetchSize / passwordCharacterEncoding 及任意驱动选项在连接前拒绝；错误密码真实认证失败时 FAILED 和日志安全，异常类型 / 堆栈保留 |

**R34-C1 / C2 修复（2026-11-03，Claude）**：不再从任意 URL 里找出并遮掉凭据，改为只接受一种写法，其余在连接前拒绝。`jdbc:mysql://主机[:端口][,主机[:端口]…]/库名[?参数=值&…]`：主机只能是普通名称、IPv4 或 `[IPv6]`，库名为字母数字与 `_ $ -`；地址里不能有用户信息、`address=(…)`、空白、`%` 编码或 `;`。参数只认 12 个（`useUnicode`、`characterEncoding`、`serverTimezone`、`connectionTimeZone`、`useSSL`、`requireSSL`、`verifyServerCertificate`、`sslMode`、`allowPublicKeyRetrieval`、`zeroDateTimeBehavior`、`connectTimeout`、`socketTimeout`，名称不分大小写），每个值有固定格式（布尔、数字、枚举、时区 / 字符集名）。所以 URL 里没有任何凭据，驱动回显的参数值也都不是机密；用户名和密码只来自各自的配置键。拒绝消息不回显地址，只说第几个参数不对。预览只有主机与库名；密码仍从消息与日志中遮掉，`scrub` 保留异常类型、堆栈和整条 cause 链。顺带关掉了交接里注明的 `allowLoadLocalInfile` 等任意驱动参数的风险。命令行也受同样限制。

- 回归：`LegacyMigrationTest` 12/12（20 种被拒写法、两种可用写法、cause 链）、`LibraryMaintenanceTest` 18/18（真实 Spring / HTTP：驱动连接失败时密码不进响应；Codex 的 5 种写法都 409、不建暂存；之后整个工作区含 `desktop.log` 扫不到任何秘密）；临时 MySQL 8.4.11 上 `LegacyMigrationMySqlTest` 3/3（真实驱动下错误密码的完整报告不含密码；URL 带正确密码的 3 种写法和 2 种回显参数在连接前被拒），证据 `D:\Temp\ia-r34-c1c2-mysql-20261103-182628\result.md`。

**Codex 复验（`806f7a5`）**：相关测试 **30/30**，打包扫描 346 files / 28707 jar entries；镜像 JAR / lib 的真实 Spring / HTTP 探针 **13/13** 危险 URL 拒绝、无新源库连接、不建暂存。真实错误密码安全反馈；正常 V7 / V8 check→apply 再次通过，全部业务行 / ID / 关系 / 时间 / 二进制及媒体哈希一致，config 不变，源行 / Flyway / 媒体未改。停服后全 home 文件扫描无合成凭据或被拒参数标记。GUI、四步回退与 R3.3 路径复用首轮，不重复全量 Java / JS 或 GUI；DOM / Console / Network 未采集。

完整命令与证据：首轮 `D:\Temp\ia-r34-c-20261103\result.md`，本轮 `D:\Temp\ia-r34-recheck-20261103\result.md`。仅合成库 / 临时 Docker，容器及匿名卷均已删除；homes、previous、镜像与证据保留。验收未改产品源码，未暂存 / 提交 / 推送；不扩大真实数据授权，MySQL V8 专项和 R3.5 不由本包关闭。

## 小版本 0.6.1（R4.0，2026-11-06）

- **升级前自动备份**：`DesktopLauncher` 在启动线程里先调 `UpgradeBackup.ifNeeded`，再启动服务（Flyway 在那时升级）。它用 Flyway `info()` 只读判断有没有待执行的迁移；没有图库或不需要升级时什么都不做。需要时走 R3.3 的 `LibraryBackup.createAt`（与手动备份同一份写入、回读校验、临时名改名），写到 `<home>ackups\插画档案备份-升级前-<时间>.zip`，启动图显示进度。备份没完成（缺原图、哈希不符、磁盘不够等）就不启动服务，窗口说明原因，默认退出，也可“不备份，直接打开”。`backups` 不在备份与恢复的范围内（两者只按名字取 `datarchive.mv.db` 和 `media`），应用不删除它。`createAt` 开始写之前按原图总量检查剩余空间，手动备份也因此会提前说“磁盘空间不够”。
- **图片右键**（`c-image-menu.js`，仅应用窗口的图库页）：作品墙、牌堆、看图器上的作品图片右键出现“复制图片 / 另存为…”，一律取原图（`/api/assets/{id}/content`），作品墙按牌堆当前页。复制：`ClipboardItem` 在点击时收下一个 Promise，非 PNG 先在页面转 PNG（GIF 取第一帧），视频不能复制。另存为：`showSaveFilePicker`，文件名用导入时的原文件名，内容是原图字节；没有这个接口时退回下载。X 收藏页的图来自 X 的 CDN，不在范围内。
- **玻璃透明度**：设置页“外观”滑块 0–100，默认 50 即原样；`theme-mode.js` 在 `<head>` 里把它换成 `--glass-k`（0 → 1.4 倍不透明度，100 → 0.5 倍），存在本机浏览器存储，与浅色 / 深色同一处。只乘到浮动玻璃（`--gl-bg*`、`--masthead-glass`、看图器工具与菜单的 `--gd-bg*`）；对话框和编辑面板的 `--sheet-bg`、看图器信息栏的 `--gd-bg-info` 是成段文字，不变，`c-dialog-style.test.js` 守住这一点。右键菜单挂在 `body` 上，和看图器共用同一组玻璃变量，浅色下也是浅玻璃。
- **原帖文字进备注**：`XPostPersistenceService.noteFrom` 把同步时已存的 `post_text` 写入新作品的 `note`：还原 X 转义的 `&amp; &lt; &gt;`，去掉末尾指向媒体本身的 t.co 链接，其余链接保留，没有文字时为空。只影响新归档，旧作品不补，无 schema 改动。
- **依赖**：Spring Boot 4.1.1 → 4.1.2（Spring 7.0.10、Tomcat 11.0.27、Jackson 3.1.7；H2 2.4.240 与 Flyway 12.4.0 不变，图库文件格式不受影响），AWS SDK 2.55.6 → 2.57.10；SWT 3.135.0、MyBatis starter 4.1.0 已是最新。
- **验证**：`mvnw test`（JDK 21）579 tests，0 failures / errors，21 skipped（未配置的 MySQL 可选测试与 LocalStack）；JS 410/410；`git diff --check` 通过。自查（不算验收）：隔离 home `D:\Temp\ia-r40-selfcheck`、无自动开页的验收服务、ego 浏览器加一个替身 `iaDesktop`：作品墙与看图器右键都出现菜单并取原图，复制后提示“已复制图片”，Esc 关闭且不关看图器；浅色下菜单原本是深底深字，已改为共用看图器玻璃；滑块 0 / 100 时样本底色 0.98 / 0.35，恢复默认回到 50。保存对话框、真实剪贴板内容、真实 WebView2 窗口、升级前备份的启动画面与失败窗口未看，归 B 级 / C 级。
- **V0.5 遗留 P1 核对**：新界面里都不存在。选择器只有一个输入框，没有搜索按钮；X 收藏归档后只有一条摘要提示，没有链接；来源只在看图器信息栏显示一次（“更多”里的“打开来源链接”是操作）。旧界面（`/`，服务端模式）未改。

## 已知问题与后续候选

- Flyway 12.4 提示 H2 2.4.240 高于其验证版本，当前功能未受影响。
- 启动：应用 CDS 归档只能在用户电脑上生成（见 R32-C1），可在安装后训练，约需 1–3 分钟，换来约 3 秒启动；体积上还可移除桌面模式用不到的 AWS SDK 依赖。
- 由其他 MSIX 打包应用启动本应用时，可能遇到与 R31-C2 相同的启动失败，目前只在 Claude、Codex 宿主上实测过。JDK 21 没有关闭 AF_UNIX 管道的开关，只能把 socket 放到 `AppData` 以外，但这要在用户目录多放一个文件夹，换来的只是这个非默认启动路径，暂不做。
- 未覆盖：签名、自动更新、大图库下的 H2 性能。
- 导入校验只读图片头：文件头完好但内容截断的图片仍会通过。
- 手动代理改动后，X 收藏里的图片要重新打开应用才走新代理（图片由浏览器窗口直接加载）。
- 回调端口 47392 被占用时无法授权，页面会说明；没有备用端口，因为每多一个都要用户多登记一个回调地址。
