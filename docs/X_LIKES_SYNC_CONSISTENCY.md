# X Likes 同步一致性改进

V0.5 maintenance 修复工作包。2026-10-14 至 10-15 在独立分支 `codex/x-likes-sync-consistency` 实施（修复提交 `5800c64`），2026-10-15 经 PR #1 合并到 `v0.5-ai-analysis`（合并提交 `4645b22`），`v0.5.0` tag 未改变。当时最终 Patch 通过 Review，V7 真实迁移与首次受控真实 X Sync 均验收成功；**首次验收时 Recent 追赶和 History 补齐都没有完成，首次同步成功不等于全量 Likes 已同步。**

实施阶段的完整测试流水与 29 个修改文件清单已移入 [PROJECT_HISTORY.md](PROJECT_HISTORY.md#x-likes-同步一致性改进)；当前项目阶段见 [PROJECT_STATE.md](PROJECT_STATE.md)。

## 同步与预算

Inbox 按钮调用 `POST /api/x-import/sync`，不再固定传入 `5×3`。仍在原有 `XLikeSyncService` 内执行，Recent / History 与按钮共用分页引擎、规范化、页面事务和原来的历史状态表。

| 配置 | 默认 | 含义 |
| --- | ---: | --- |
| `X_SYNC_RECENT_PAGE_SIZE` | 25 | 新一轮 Recent 遍历的页大小，5–100；未完成游标保持原页大小 |
| `X_SYNC_RECENT_MAX_PAGES` | 2 | 每次 Recent 续传的成功页上限；预算允许时另做一页头部探测，该页同样占共享预算 |
| `X_SYNC_MAX_PAGES` | 6 | 整个操作的 Likes HTTP 请求尝试上限，包括失败、401 重试 |
| `X_SYNC_MAX_SCANNED` | 100 | 请求前按 `max_results` 预留的最坏扫描额度，包括重试；与实际返回量分别统计 |
| `X_SYNC_MAX_NEW` | 50 | 新增 Inbox 记录上限，包含 Unsupported；与已有和重新恢复的记录分别计算 |

这些配置可通过本地配置文件或进程环境提供，后端启动时校验预算范围；整数配置不能写成空值（空字符串会覆盖默认值并导致启动失败）。`X_API_DEFAULT_*` 只用于单独的 Recent / History 后端入口。Docker 默认仍关闭 X API；该修复没有修改部署结构，也没有替用户启用外部集成。

先处理 Recent，再用剩余预算推进原有 History。History 继续使用保存的游标和对应页大小，Recent 的头部探测不会覆盖它。所有重复记录正常计数，不因 `newCount=0` 或某一条已存在帖子提前退出。新增目标是上限，不会为了凑满 50 无限扫描。

为了不丢弃页面尾部、不改变保存游标对应的页大小，预算不足一整页时提前停止，因此不保证凑满 50。扫描量是实际收到的 `data` 条数（含重复）；预留量是费用控制的保守上界，不是新增量。网络 / 格式失败无法确认返回量时，响应 `scanCountKnown=false`，界面显示"至少 N 条"。`/users/me` 和 OAuth 刷新沿用原有有限重试逻辑，不计入 Likes 页数；页数预算不等于金额或全部 HTTP 请求总数。

## Recent 如何避免与 History 留下空档

旧实现每次只重读头部 50 条，再跳回较旧的 History 游标，80 条新 Likes 中的后 30 条可能一直落在两者之间。修复后 Recent 保存自己的未完成遍历，不再把预算截断视为近期扫描完成。

复用 `x_like_sync_state`：`id=1` 保留 History，`id=2` 保存 Recent 的游标、页大小、上一轮已验证的有序 ID 窗口、本轮首个页面的 ID、跨页匹配位置、顺序分配位置及是否观察到 API 末尾。游标摘要在原表中按 `sync_id` 隔离，避免相同 opaque token 被两种扫描互相误判为循环。

1. **首次没有可信 Recent 边界**：已有记录的存在不能证明中间没有缺口。Recent 从头分批扫描到 API 自然末尾；预算耗尽时保存位置，下次继续。即使旧 History 已 COMPLETE，也执行这次保守建立边界的过程。有有效的旧 History 时仍允许它在剩余预算内推进；没有 History 时显示等待 Recent 建立边界，不从有限窗口跳接到未知历史。
2. **已有边界后的新增追赶**：从当前头部向保存的完整有序窗口扫描；必须连续匹配整个窗口（至少两个不同 Post ID），或者到达自然末尾，才认为本轮范围完成衔接。窗口变化、删除或无法匹配时退回自然末尾策略。不能只遇到一条 Pending / Imported / Skipped 就停止。
3. **预算截断或重启**：保留 `ACTIVE` 的尾部游标和跨页匹配位置。预算足以容纳头部与尾部整页时，先探测新头部，再继续原尾部；头部探测不能替换尾部游标。预算只够一页时优先尾部，避免每次都只读最新一页。
4. **遍历期间又有新 Likes**：跨页完成后先记 `VERIFY`，下次点击复核头部。头部相同才记 `CAUGHT_UP`；头部改变则启动向刚完成范围的头部窗口衔接的下一轮，仍受预算限制。这覆盖了旧游标返回固定快照、排除追赶期间新增记录的模拟情况。若一个响应已同时覆盖头部和完整边界 / 自然末尾，可直接完成该次观察。
5. **History 的完成**：原 History 游标照常延续；Recent 已验证扫至自然末尾时也可标记 History COMPLETE。History COMPLETE 只表示旧范围已到末尾，绝不阻止之后新的 Recent 追赶。Recent 与 History 分别报告进度。

| Recent 状态 | 含义与下一步 |
| --- | --- |
| UNKNOWN | 还未建立可信的 Recent 边界 |
| ACTIVE | 仍有待扫区间；下次 Sync 延续游标 |
| VERIFY | 已到衔接边界，等待下一次头部复核 |
| CAUGHT_UP | 本次 API 观察范围已衔接；不代表上游提供全量快照保证 |
| INVALID | 游标 / 检查点失效或循环；显式重置后从头重新衔接 |

在稳定且可访问的输入模型中，80 / 150 条新 Likes 都能跨多次点击补齐完整 ID 集合：80 条的情况在第二次点击时发现剩余 30 条，之后仍可能需要边界扫描与复核。每次最多新增 50 条的限制保留；既不要求一次结束，也不把请求数当新增数。

## X API 能力边界

[官方 Likes endpoint](https://docs.x.com/x-api/users/get-liked-posts) 只有 `pagination_token` / `max_results` 等参数，没有可用于增量检查点的 `since_id` 或精确点赞时间。[官方分页说明](https://docs.x.com/x-api/fundamentals/pagination) 允许保存 opaque token 后续使用，也明确提示 token 可能过期；短页面不等于结束，应以 `next_token` 判断。

实施时未找到该 endpoint 对跨请求快照、并发插入 / 删除、游标寿命或与网页版严格同序的保证。因此这是有持久化进度、可测试衔接的保守方案，不宣称在任意上游变化下绝对全量一致。若上游在保留相同头部的同时重排 / 删除中部、静默跳过记录、限制可访问历史，或新增速度长期超过用户批准的预算，仍不能证明没有遗漏。完整窗口和复核是本地证据，不能替代上游契约。页面使用"按 API 观察已衔接"，并独立显示待扫描 / 待验证区间。

## 页面事务与失败

Recent 每页由 Spring 代理上的 `saveRecentPage` 在同一个事务中保存条目、媒体、顺序、Unsupported 恢复和 Recent 检查点；任一失败整页回滚，之前成功的页面保留。跨页不做长事务，也没有文件操作。头部探测使用已有的页面保存方法；若后续建立检查点失败，已经保存的头部数据保留并报告部分完成，重新扫描是安全的。

History 保留原来的页面事务：页面提交后推进游标；游标持久化失败时已提交页面保留，旧游标不变，下一次显式点击时重读。Post ID 唯一键保证幂等。

HTTP 200 携带 `errors[]`，或声明的 `result_count` 与实际不符时：保存可完整规范化的记录，标记 `API_PARTIAL`，不推进该页的 Recent / History 检查点，也不宣称自然结束。缺作者 / 必需字段等规范化错误使整页不保存，但仍尽可能统计已收到条数。上游返回超过请求大小的页面不保存。

重复、缺失或被上游 400 拒绝的分页游标标记 Invalid（400 本身不能证明一定是过期）。429 / 5xx / 网络 / 保存失败保留旧检查点，下一次点击重试，不自动无限重试。Recent 失败时本次不继续 History，以明确报告部分完成。

**手动恢复**：Inbox 初始的只读 GET 返回 `syncProgress`，重开页面即可看到 Invalid 的重置按钮，不必先调用付费 Sync。重置 Recent 保留可信的旧窗口，从头重新衔接；重置失效的 History 会清除旧 History 进度，并安排一次无锚点的 Recent 保守遍历至末尾，因为旧 Recent 的"已追上"状态无法证明历史没有缺失。两种重置都保留全部帖子 / 媒体 / 归档，不自动调用 X API；之后再点击 Sync 才会扫描。顺序空间异常或损坏的 Recent 检查点也需要显式重置。

## 顺序与去重

V7 增加 `x_like_item.likes_order BIGINT NOT NULL DEFAULT 0` 并替换 Pending 索引，同时给既有状态 / token 表添加 Recent 检查点和作用域字段。Inbox 排序为 `likes_order DESC, discovered_at DESC, id DESC`：近期观察使用递减的正排名，按 API 数组及分页次序放在最前；历史补齐使用递减的负排名；旧记录保持 0，继续使用原来的发现时间 / ID 稳定回退。相同 `discovered_at` 不再颠倒有排名的记录。

Recent 把递减排名的分配位置随检查点持久化，跨点击 / 重启继续；新的头部探测分配更高排名，旧尾部不得覆盖更早页面或更新头部的位置。重试的部分页面允许校正自己的排名。排序不改变首次发现时间、状态、媒体或归档关联；History 不下移已被 Recent 观察到的帖子。未知的旧数据不会伪造点赞时间或历史精确顺序。

排名只表示本地观察到的 API 顺序，不用帖子发布时间或 Post ID 推断点赞顺序；`discovered_at` 也不是点赞时间。跨批次与网页版的一致性仍需真实验收。Gallery 继续按归档时间组织作品，作品内部 Asset 顺序沿用原有 `sort_order`。

唯一 Post ID 只产生一个 Inbox 记录。Imported / Skipped 不恢复为 Pending；Unsupported 只在现有恢复条件满足时恢复。同图不同 Post 允许进入 Pending，Sync 时不下载文件去重。Archive 才使用 SHA-256 拒绝重复内容，保留原来的整帖多图事务、文件补偿、批次部分成功和 Duplicate 反馈；该修复没有修改 Gallery、下载器、存储或归档的生产逻辑。

## V7 迁移与备份

- V7（`V7__add_x_like_api_order.sql`）只调整字段、索引和 `x_like_sync_seen_token` 主键，不回填或删除数据；已于 2026-10-15 在真实 MySQL 成功应用。
- 同日导出的 SQL 备份已在隔离 MySQL 容器中实际恢复验证。**导出时数据库已经是 V7，该备份保存的是 V7 状态，不能当作迁移前的 V6 恢复点。**
- MySQL ALTER / 建索引可能持有元数据锁，DDL 不能靠普通数据库事务回滚。需要回退时先停用新 Sync，明确目标 schema 并制定独立的回退方案；不修改已应用的 V1–V7，也不声称现有 V7 备份能直接恢复 V6。
- 迁移的自动化证据来自默认禁用的 `XLikeSyncMigrationMySqlTest`：只接受 `X_SYNC_MYSQL_TEST_URL=jdbc:mysql://127.0.0.1:3306/illustration_archive_x_sync_test_<数字>`（或 localhost），测试账号 / 密码由进程环境 `X_SYNC_MYSQL_TEST_USERNAME` / `X_SYNC_MYSQL_TEST_PASSWORD` 提供。
- 该测试创建不存在的专用 schema，构造 V6 数据后验证 V7 的数据保留、索引、重复 migrate、排序、归档去重，以及真实 Spring 事务下检查点写入失败时的整页回滚；结束时只删除自己创建的 schema，不复制真实用户帖子。

## 验证记录

自动化测试使用真实 API 解析器与 Mock HttpClient，不发送真实 X 请求。稳定输入用**期望的完整 Post ID 集合**断言，而不是只比数量；覆盖连续新增、80 / 150 / 170 条多轮追赶、History ACTIVE / COMPLETE、跨重启续传、缺失边界回退、500 / 429 重试、400 / 循环后的手动恢复，以及 API 顺序与帖子日期不一致。JS Mock 测试覆盖初始 GET 显示重置入口、两种进度独立显示和同步结果展示。逐项清单见归档。

两组全量结果运行条件不同，分别记录，不互相替代：

| 来源 | 运行条件 | Java | JavaScript |
| --- | --- | --- | --- |
| 实现阶段（2026-10-14 至 10-15，Codex） | JBR 21，`X_API_ENABLED=false`，另行提供 `X_SYNC_MYSQL_TEST_*`，启用并通过了迁移 MySQL 测试 | 385 tests，382 passed，0 failures / errors，3 skipped（旧 Gallery MySQL、animated persistence MySQL、LocalStack 未启用） | 133/133 |
| 接手健康检查（2026-10-26，Claude Code） | 从 HEAD `4645b22` 导出的干净副本，JDK 17.0.19，`mvnw verify`，未设置任何 opt-in 环境变量 | 385 tests，0 failures / errors，4 skipped（3 个 MySQL opt-in，含本迁移测试，以及 LocalStack） | 133/133 |

两次都属于自动化证据，不能替代真实 X API 验收。复现命令见 README 的[测试与构建](../README.md#测试与构建)。

## 首次受控真实验收（2026-10-15，用户确认）

以下真实环境结果由用户验收确认，之后的文档整理没有连接数据库、调用真实 X API，也没有重新执行迁移 / 备份 / 恢复。

- Flyway V7 在真实 MySQL 成功迁移；SQL 备份成功导出，并在隔离 MySQL 容器中完成实际恢复验证（V7 状态，见上节）。
- 原有 **351 Pending、14 Imported、4 Skipped、21 Unsupported** 均保留，Gallery 原有 **31 件作品**正常显示。
- 首次受控真实 X Sync 成功：最新发现 **35 条**，历史新发现 **0 条**；Inbox Pending **351 → 384**。此前遗漏的 X Post **`2109573113432399935`** 已真实入库，状态为 **PENDING**。新发现总数与 Pending 增量分别记录，不混为同一指标。
- 当时 **Recent 追赶与 History 补齐均未完成**：这次成功证明目标帖可以找回、首轮同步入库正常，不能证明整个 X Likes 已完整同步。
- 以上数量是验收当时的状态，不代表当前队列。

同步后修复了通知重叠：完整结果与近期 / 历史状态显示在按钮下方的页面结果区，底部只保留单条简短 Toast，6 秒后消失。Codex 使用独立 Mock 服务检查了 1280×720 桌面与 390×844 手机宽度下的换行、滚动和按钮可达性；该 UI 检查不调用真实 X API，不等同于生产页面上追加的真实 Sync 验收。

80 / 150 条新增的跨轮补齐、并发新增模型、重启续传、游标失效 / 错误恢复及完整 ID 集合证明仍属于 Mock / 隔离测试覆盖，不能全部表述为已完成真实 API 验证。尚未完成的 Recent / History 范围和 API 顺序限制仍然存在；后续真实调用须控制费用与扫描预算，不会自动继续同步。
