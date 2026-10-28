# Windows 本地图库 PoC

> 状态：PoC 已实现并完成首轮验收（2026-10-26），2026-10-27 完成收尾加固。这是**探索原型**，不是已批准的 V0.6 阶段；正式安装器、自动更新、独立 WebView 窗口均不在范围内。

## 目标与边界

- 让现有 Spring Boot 后端 + 现有前端以"双击即用"的方式在 Windows 上运行，验证桌面化的主要障碍（MySQL 依赖、相对路径配置、打包）是否可解。
- 原 MySQL 运行方式不变；PoC 使用完全独立的数据与配置，不连接、不读取、不迁移真实数据库。
- 关闭 X / OAuth、Gemini、S3 与一次性维护回填。

## 架构

```
IllustrationArchivePoC.exe (jpackage 启动器 + 自带 Java 17 运行时)
  └─ Boot jar，-Dillustration-archive.desktop=true
       └─ IllustrationArchiveApplication.main → DesktopLauncher（Swing 控制窗）
            ├─ instance.lock 单实例锁
            ├─ DesktopServer.start(home)  → Spring profile "desktop"
            │    ├─ 文件型 H2：<home>\data\archive.mv.db（MODE=MySQL, IGNORECASE）
            │    ├─ Flyway：classpath:db/migration-h2（V1–V7 的 H2 等价版本）
            │    ├─ 媒体：<home>\media（数据库仍只存相对 storage_key）
            │    ├─ Tomcat 只监听 127.0.0.1（默认 47391，被占用时随机端口）
            │    └─ LocalAccessFilter：启动令牌 → SameSite=Strict Cookie
            └─ 系统默认浏览器打开 http://127.0.0.1:<port>/desktop/session?token=…
```

`<home>` 默认 `%LOCALAPPDATA%\IllustrationArchivePoC`，可用环境变量 `ILLUSTRATION_ARCHIVE_DESKTOP_HOME` 或系统属性 `illustration-archive.desktop.home` 覆盖。日志在 `<home>\logs\desktop.log`。

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
- **退出释放**：控制窗"退出"或关闭窗口 → `context.close()`：Tomcat 优雅停机（最长 15 秒，等待进行中的导入）→ Hikari 关闭连接池 → H2 在最后一个连接关闭时关库（`DB_CLOSE_ON_EXIT=FALSE`，不依赖 H2 自己的关闭钩子）→ 释放单实例锁 → `System.exit`。
- **事务与文件补偿**：沿用现有服务逻辑，未改动。H2 在另一事务持有同 SHA-256 未提交行时，会在唯一索引上等待并在对方提交后报重复键，行为与 MySQL InnoDB 一致（已由测试验证）。

## 构建与运行

```powershell
.\scripts\build-desktop-poc.ps1 -JavaHome "$HOME\.jdks\ms-17.0.19" -MavenArgs '-Dmaven.repo.local=C:\Users\YuanYuChou\.m2\repository'
```

产物（`dist/` 已被 Git 忽略）：

- `dist\IllustrationArchivePoC\IllustrationArchivePoC.exe`：双击运行；整个文件夹约 171 MB（Boot jar 42 MB，其余为 jlink 运行时）。
- `dist\IllustrationArchivePoC-win64.zip`：约 85 MB，解压后即可运行，目标机无需安装 Java。

脚本不执行 `mvn clean`（运行中的本地实例可能占用 `target\` 下的文件），只删除 `target\classes` 和旧 jar；测试需单独运行。

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

用真实桌面启动器体验同一份验收图库：`mvnw package -DskipTests` 后，设置 `ILLUSTRATION_ARCHIVE_DESKTOP_HOME` 指向该目录，再 `java -Dillustration-archive.desktop=true -jar target\illustration-archive-0.0.1-SNAPSHOT.jar`，在控制窗“打开图库”后从“操作”菜单进入新版图库。

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

## 已知问题与后续候选

- Flyway 12.4 提示 H2 2.4.240 高于其验证版本，当前功能未受影响。
- 体积：运行时使用 jpackage 默认模块集；可用 `--add-modules` 精简，或移除桌面模式用不到的 AWS SDK 依赖。
- 未覆盖：正式安装器 / 签名、自动更新、独立 WebView 窗口、与 MySQL 数据之间的迁移工具、大图库下的 H2 性能。
