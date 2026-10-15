# X Likes 同步一致性改进

2026-10-15 更新，V0.5 maintenance 修复工作包。实现位于 `codex/x-likes-sync-consistency`，`v0.5.0` 不变。最终 Patch 已通过 Review，V7 真实迁移与首次受控真实 X Sync 已验收成功；首次验收时 Recent 追赶和 History 补齐均未完成，不代表全量 Likes 已同步。本次仅文档收尾，不重新执行验收，不 stage / commit / push。

## 同步与预算

Inbox 按钮调用 `POST /api/x-import/sync`，不再固定传入 `5×3`。仍在原有 `XLikeSyncService` 内执行，Recent / History 与按钮共用分页引擎、规范化、页面事务和原来的历史状态表。

| 配置 | 默认 | 含义 |
| --- | ---: | --- |
| `X_SYNC_RECENT_PAGE_SIZE` | 25 | 新一轮 Recent 遍历的页大小，5–100；未完成游标保持原页大小 |
| `X_SYNC_RECENT_MAX_PAGES` | 2 | 每次 Recent 续传的成功页上限；预算允许时另做一页头部探测，该页同样占共享预算 |
| `X_SYNC_MAX_PAGES` | 6 | 整个操作的 Likes HTTP 请求尝试上限，包括失败、401 重试 |
| `X_SYNC_MAX_SCANNED` | 100 | 请求前按 `max_results` 预留的最坏扫描额度，包括重试；与实际返回量分别统计 |
| `X_SYNC_MAX_NEW` | 50 | 新增 Inbox 记录上限，包含 Unsupported；与已有和重新恢复的记录分别计算 |

这些配置可通过真实本地配置文件或部署进程环境提供；后端校验预算。现有 `X_API_DEFAULT_*` 只用于单独的 Recent / History 入口。Docker 默认仍关闭 X API；本轮不修改部署结构或替用户启用外部集成。

先处理 Recent，再用剩余预算推进原有 History。History 继续使用保存的游标和对应页大小，Recent 的头部探测不会覆盖它。所有重复记录正常计数，不因 `newCount=0` 或某一条已存在帖子提前退出。新增目标是上限，不会为了凑满 50 无限扫描。

为了不丢弃页面尾部、不改变保存游标对应的页大小，预算不足一整页时提前停止，因此不保证凑满 50。扫描量是实际收到的 `data` 条数（含重复）；预留量是费用控制的保守上界，不是新增量。网络/格式失败无法确认返回量时，响应 `scanCountKnown=false`，界面显示“至少 N 条”。`/users/me` 和 OAuth 刷新沿用原有有限重试逻辑，不计入 Likes 页数，也不应把页数预算理解成金额或所有 HTTP 请求总数。

## Recent 如何避免与 History 留下空档

旧实现每次只重读头部 50 条，再跳回较旧的 History 游标。80 条新 Likes 中的后 30 条可能一直落在两者之间。本轮让 Recent 保存自己的未完成遍历，不再把预算截断视为近期扫描完成。

复用 `x_like_sync_state`：`id=1` 保留 History，`id=2` 保存 Recent 的游标、页大小、上一轮已验证的有序 ID 窗口、本轮首个页面的 ID、跨页匹配位置、顺序分配位置及是否观察到 API 末尾。游标摘要在原表中按 `sync_id` 隔离，避免相同 opaque token 被两种扫描互相误判为循环。

1. **首次没有可信 Recent 边界**：已有记录的存在不能证明中间没有缺口。Recent 从头分批扫描到 API 自然末尾；预算耗尽保存位置，下次继续。即使旧 History 已 COMPLETE，也执行这次保守建立边界的过程。有旧有效 History 时仍允许其在剩余预算内推进；无 History 时显示等待 Recent 建立边界，不从有限窗口跳接到未知历史。
2. **已有边界后的新增追赶**：从当前头部向保存的完整有序窗口扫描；必须连续匹配整个窗口（至少两个不同 Post ID），或者到达自然末尾，才认为本轮范围完成衔接。窗口变化、删除或无法匹配时退回自然末尾策略。不能只遇到一条 Pending / Imported / Skipped 就停止。
3. **预算截断或重启**：保留 `ACTIVE` 的尾部游标和跨页匹配位置。预算足以容纳头部与尾部整页时，先探测新头部，再继续原尾部；头部探测不能替换尾部游标。预算只够一页时优先尾部，避免每次都只读最新一页。
4. **遍历期间又有新 Likes**：跨页完成后先记 `VERIFY`，下次点击复核头部。头部相同才记 `CAUGHT_UP`；头部改变则启动向刚完成范围的头部窗口衔接的下一轮，仍受预算限制。这覆盖旧游标返回固定快照、排除追赶期间新增记录的模拟情况。若一个响应已同时覆盖头部和完整边界 / 自然末尾，可直接完成该次观察。
5. **History 的完成**：原 History 游标照常延续；Recent 已验证扫至自然末尾时也可标记 History COMPLETE。History COMPLETE 只表示旧范围到末尾，绝不阻止之后新的 Recent 追赶。Recent 与 History 分别报告进度。

| Recent 状态 | 含义与下一步 |
| --- | --- |
| UNKNOWN | 还未建立可信的 Recent 边界 |
| ACTIVE | 仍有待扫区间；下次 Sync 延续游标 |
| VERIFY | 已到衔接边界，等待下一次头部复核 |
| CAUGHT_UP | 本次 API 观察范围已衔接；不代表上游提供全量快照保证 |
| INVALID | 游标 / 检查点失效或循环；显式重置后从头重新衔接 |

稳定且可访问的输入模型中，80 / 150 条新 Likes 都能跨点击补齐完整 ID 集合，80 条的第二次已发现剩余 30 条，之后仍可能需要边界扫描与复核。每次最多新增 50 条的限制仍保留；既不要求一次结束，也不把请求数当新增数。

## X API 能力边界

检查的 [官方 Likes endpoint](https://docs.x.com/x-api/users/get-liked-posts) 只有 `pagination_token` / `max_results` 等参数，没有可用于 Likes 增量检查点的 `since_id` 或精确点赞时间。[官方分页说明](https://docs.x.com/x-api/fundamentals/pagination) 允许保存 opaque token 后续使用，也明确提示 token 可能过期；短页面不等于结束，应使用 `next_token`。

未找到该 endpoint 对跨请求快照、并发插入 / 删除、游标寿命或与网页版严格同序的保证。因此这是有持久化进度、可测试衔接的保守方案，不宣称任意上游变化下的绝对全量一致。若上游在保留相同头部的同时重排 / 删除中部、静默跳过记录、限制可访问历史，或者新增速度长期超过用户批准的预算，仍不能证明没有遗漏。完整窗口和复核是本地证据，不能替代上游契约。页面使用“按 API 观察已衔接”，并独立显示待扫描 / 待验证区间。

## 页面事务与失败

Recent 每页由 Spring 代理上的 `saveRecentPage` 在同一个事务中保存条目、媒体、顺序、Unsupported 恢复和 Recent 检查点；任一失败整页回滚，之前成功的页面保留。跨页不做长事务，也没有文件操作。头部探测使用已有页面保存方法；若后续建立检查点失败，已经保存的头部数据保留并报告部分完成，重新扫描安全。

History 保留原来的页面事务：页面提交后推进游标；游标持久化失败时已提交页面保留，旧游标不变，下一次显式点击重读。Post ID 唯一键保证幂等。

HTTP 200 携带 `errors[]`、或声明的 `result_count` 不吻合时：保存可完整规范化的记录，标记 `API_PARTIAL`，不推进该页面的 Recent / History 检查点，不宣称自然结束。缺作者 / 必需字段等规范化错误使整页不保存，仍尽可能统计已收到条数。上游返回超过请求大小的页面不保存。

重复、缺失或被上游 400 拒绝的分页游标标记 Invalid（400 本身不能证明一定是过期）。429 / 5xx / 网络 / 保存失败保留旧检查点，下一次点击重试，不自动无限重试。Recent 失败时本次不继续 History，以明确报告部分完成。

**手动恢复**：Inbox 初始只读 GET 返回 `syncProgress`，所以重开页面即可看到 Invalid 的重置按钮，不必先调用付费 Sync。重置 Recent 保留可信旧窗口，从头重新衔接；重置失效 History 则清除旧 History 进度、安排无锚点的 Recent 保守遍历至末尾，因为旧 Recent“已追上”状态无法证明缺失历史。两种重置都保留全部帖子 / 媒体 / 归档，不自动调用 X API；随后再点击 Sync 才扫描。顺序空间异常或损坏的 Recent 检查点也需要显式重置。

## 顺序与去重

V7 增加 `x_like_item.likes_order BIGINT NOT NULL DEFAULT 0` 并替换 Pending 索引，同时给既有状态 / token 表添加 Recent 检查点和作用域字段。Inbox 排序为 `likes_order DESC, discovered_at DESC, id DESC`：近期观察使用递减正排名，按 API 数组及分页次序放在最前；历史补齐使用递减负排名；旧记录保持 0，继续使用原来的稳定发现时间 / ID 回退。相同 discovered_at 不再颠倒有排名的记录。

Recent 将递减排名的位置随检查点持久化，跨点击 / 重启继续；新头部探测分配更高排名，旧尾部不得覆盖更早页面或更新的头部位置。重试的部分页面允许校正自己的排名。排序不改变首次发现时间、状态、媒体或归档关联；History 不下移已观察为 Recent 的帖子。未知旧数据不会伪造点赞时间或历史精确顺序。

排名只表示本地观察到的 API 顺序，不使用帖子发布时间或 Post ID 推断点赞顺序；`discovered_at` 也不是点赞时间。跨批次和网页一致性仍需真实验收。Gallery 继续按归档时间组织作品，作品内部 Asset 顺序沿用原有 `sort_order`。

唯一 Post ID 只产生一个 Inbox 记录。Imported / Skipped 不恢复 Pending；Unsupported 仅在现有恢复条件满足时恢复。同图不同 Post 允许进入 Pending，不下载文件做 Sync 去重。Archive 才使用 SHA-256 拒绝重复内容，保持原来的整帖多图事务、文件补偿、批次部分成功和 Duplicate 反馈；本轮没有修改 Gallery、下载器、存储或归档生产逻辑。

## 验证与迁移

`XLikeCombinedSyncTest` 使用真实 API 解析器和 Mock HttpClient，保留前轮第 16–30 条（含指定目标 ID）、状态 / 同图不同 Post、顺序、预算 / 401、部分成功与失败测试。新增 `XLikeCatchupTest` 模拟随插入移动的分页和固定快照分页，生产逻辑不解析 token。**稳定输入用期望完整 Post ID 集合断言，不能仅用数量代替。** 覆盖连续 5 条、80 / 150 条、多轮 170 条新增、History ACTIVE / COMPLETE、游标与匹配位置跨重启、跨页完整窗口、缺失边界退回末尾、Imported 不误停止、500 / 429 重试、400 / 循环手动恢复、History 重置后的全范围恢复及 API 顺序 / 帖子日期不一致。

JavaScript Mock 测试新增初始 GET 就显示 Invalid 重置、Recent 重置不触发 Sync、Recent ACTIVE / VERIFY 与 History COMPLETE 独立显示；保留分阶段计数、总 Pending 区分和部分完成刷新测试。UI 收尾新增完整结果独立保留、单条简短 Toast 替换 / 消失、归档反馈不覆盖同步结果测试。上述自动化测试不发送真实 X 请求；真实验收另见下文。

`XLikeSyncMigrationMySqlTest` 默认禁用，只允许 `X_SYNC_MYSQL_TEST_URL=jdbc:mysql://127.0.0.1:3306/illustration_archive_x_sync_test_<数字>`（或 localhost），另用进程环境提供测试账号 / 密码。测试创建不存在的专用 schema，构造 V6 的 351 Pending 及其他状态、媒体和旧 History 游标，验证调整后 V7 数据保留、索引、重复 migrate、同毫秒排序、归档 SHA-256 去重；新增同 token 双作用域、Recent 检查点重新加载、History 清理不影响 Recent、**真实 Spring 事务中检查点写入失败时条目 / 媒体 / 排名 / 检查点全部回滚**。最后只删除自己创建的 schema，没有复制真实用户帖子。

V7 已于 2026-10-15 在真实 MySQL 成功应用，SQL 备份也已实际恢复验证。该备份保存的是 **V7 状态**，不能当作迁移前 V6 恢复点。迁移不做数据回填 / 删除，但 MySQL ALTER / 索引创建可能持有元数据锁；DDL 不能靠普通数据库事务回滚。需要回退时先停用新 Sync，明确目标 schema 并制定独立回退方案，不修改已应用的 V1–V7，也不声称现有 V7 备份能直接恢复 V6。

## 自动化验证记录（实现阶段，文档收尾未重跑）

Focused Java：**69/69**；单独隔离 MySQL 迁移测试：**1/1**。最终全量 Java：**385 tests，382 passed，0 failures / errors，3 skipped，BUILD SUCCESS**。全量启用并通过了专用 schema 的 `XLikeSyncMigrationMySqlTest`；3 skipped 是未启用的旧 Gallery MySQL、animated persistence MySQL、LocalStack 测试。UI 修复后相关 JavaScript **32/32**，最终全量 JavaScript **133/133 passed，0 failed / skipped**。实现阶段的 `git diff --check` 及新增文件空白检查通过；文档收尾只重新检查文档差异与空白，不重跑上述测试。

实现阶段使用 JBR 21，测试进程设 `X_API_ENABLED=false`；仅为一次性隔离迁移测试提供 `X_SYNC_MYSQL_TEST_*` 环境变量，凭据没有写入测试源码、日志或命令参数。以下是从项目根目录执行的公开复现命令，已省略个人缓存路径；隔离 MySQL 测试需另行显式提供专用测试环境变量，默认不启用。

```powershell
New-Item -ItemType Directory -Force target/test-temp | Out-Null
$env:X_API_ENABLED = 'false'
.\mvnw.cmd '-DargLine=-Djava.io.tmpdir=target/test-temp' test
$taskTests = @(rg --files src/test/js -g '*.test.js')
node --test @taskTests
git diff --check
```

本地 ignored 日志：`.maven/x-likes-catchup-full-java.log` 记录 Java 结果；`.maven/x-likes-catchup-full-js.log` 是 UI 修复前的 JS 131/131，最终 JS 133/133 见 `.maven/x-likes-ui-full-js.log`。**开发阶段只读基线（2026-10-14，V7 应用前）：**390 条 Inbox、449 条媒体、1 条历史状态指纹不变，Pending 351、Flyway V6；这是历史记录，不是当前库状态。当前迁移与首次同步结果见下文。

## 工作包修改文件清单（29 个，含 UI 收尾）

后端：

```text
src/main/java/com/yuutara/illustrationarchive/controller/XImportController.java
src/main/java/com/yuutara/illustrationarchive/dto/XLikeInboxPage.java
src/main/java/com/yuutara/illustrationarchive/dto/XLikePage.java
src/main/java/com/yuutara/illustrationarchive/dto/XLikeSyncReport.java
src/main/java/com/yuutara/illustrationarchive/repository/XLikeRepository.java
src/main/java/com/yuutara/illustrationarchive/repository/XLikeSyncStateRepository.java
src/main/java/com/yuutara/illustrationarchive/service/XApiClient.java
src/main/java/com/yuutara/illustrationarchive/service/XApiException.java
src/main/java/com/yuutara/illustrationarchive/service/XLikePersistenceService.java
src/main/java/com/yuutara/illustrationarchive/service/XLikeSyncService.java
src/main/java/com/yuutara/illustrationarchive/service/XLikeSyncBudget.java
```

配置、迁移和页面：

```text
config/application-local.properties.example
src/main/resources/application.properties
src/main/resources/db/migration/V7__add_x_like_api_order.sql
src/main/resources/static/style.css
src/main/resources/static/x-import.html
src/main/resources/static/x-import.js
```

测试和文档：

```text
src/test/java/com/yuutara/illustrationarchive/controller/XImportControllerTest.java
src/test/java/com/yuutara/illustrationarchive/repository/XLikeRepositoryTest.java
src/test/java/com/yuutara/illustrationarchive/service/XAnimatedPersistenceMySqlTest.java
src/test/java/com/yuutara/illustrationarchive/service/XLikePersistenceServiceTest.java
src/test/java/com/yuutara/illustrationarchive/service/XLikeSyncServiceTest.java
src/test/java/com/yuutara/illustrationarchive/service/XLikeCatchupTest.java
src/test/java/com/yuutara/illustrationarchive/service/XLikeCombinedSyncTest.java
src/test/java/com/yuutara/illustrationarchive/service/XLikeSyncMigrationMySqlTest.java
src/test/js/x-import.test.js
README.md
docs/PROJECT_STATE.md
docs/X_LIKES_SYNC_CONSISTENCY.md
```

## 首次受控真实验收（2026-10-15，用户确认）

以下真实环境结果由用户验收确认；本次文档收尾未连接数据库、调用真实 X API，或重新执行迁移 / 备份 / 恢复。

- Flyway V7 已在真实 MySQL 成功迁移。SQL 备份成功导出，并在隔离 MySQL 容器中完成实际恢复验证。**导出时数据库已经是 V7，不能描述为迁移前的 V6 备份。**
- 原有 **351 Pending、14 Imported、4 Skipped、21 Unsupported** 均保留，Gallery 原有 **31 件作品**正常显示。
- 首次受控真实 X Sync 成功：最新发现 **35 条**，历史新发现 **0 条**；Inbox Pending **351 → 384**。此前遗漏的 X Post **`2109573113432399935`** 已真实入库，状态为 **PENDING**。新发现总数与 Pending 增量分别记录，不混为同一指标。
- 当时 **Recent 追赶与 History 补齐均未完成**，此次成功证明目标帖可找回及首轮同步入库正常，不能证明整个 X Likes 已完整同步。

同步后通知重叠问题已修复：完整结果与近期 / 历史状态在按钮下方的页面结果区显示，底部仅单条简短 Toast，6 秒后消失。Codex 使用独立 Mock 服务完成 **1280×720 桌面、390×844 手机宽度**的换行、滚动和按钮可达性检查；完整统计保留，无通知重叠、横向溢出或分页 / 归档按钮遮挡。该 UI 检查不调用真实 X API，不等同生产页面的追加真实 Sync 验收。

80 / 150 条新增的跨轮补齐、并发新增模型、重启续传、游标失效 / 错误恢复及完整 ID 集合证明仍属于 Mock / 隔离测试覆盖，不能全部表述为已完成真实 API 验证。尚未完成的 Recent / History 范围和 API 顺序限制继续保留；后续真实调用仍须控制费用与扫描预算，本次文档收尾不会自动继续同步。
