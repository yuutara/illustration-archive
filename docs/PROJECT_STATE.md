# Illustration Archive - Project State

> This file records the current project state and important design decisions.
> When summaries and the actual repository conflict, the repository is the source of truth.

## Current Version

V0.3 - X Likes Import Inbox

Current development stage:

**V0.3-D3-2 Sync End State & Idempotency 已完成验收：真实 X API 多页分页、`maxPages` 截断及跨轮 continuation 已验证；自然末页和重复同步的边界语义由自动化回归测试覆盖。当前未遍历完全部真实历史 Likes，C2、C3、C4 的真实环境验收已完成。**

V0.2 的 Final Acceptance 已完成；其最终 commit、push 和 tag 是否执行由项目维护者决定。

### V0.3-A | X Likes → Import Inbox backend

- V0.3-A 最初只请求最近 Likes 的一页；V0.3-D1 增加单次手动同步的有限分页，见下方 D1 记录。
- 新增 `x_like_item` / `x_like_media`，用唯一 `x_post_id` 防止重复候选；同一个 Post 再次出现时保留已有状态和媒体。
- 只有直接 attachments 全为带 URL 的 photo 且至少一张时标记 `PENDING`；其余标记 `UNSUPPORTED`。
- `GET /api/x-import/inbox` 仅返回 `PENDING`，按 Post 创建时间倒序并按媒体顺序返回。
- `POST /api/x-import/sync/recent` 返回本次同步摘要。X Access Token 只从未跟踪的本地配置或环境变量读取；默认关闭。
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

- 只在用户点击 Sync latest Likes 时请求 X Likes；逐页使用 `meta.next_token` 作为下一次请求的 `pagination_token`，每页复用原有幂等持久化逻辑。
- `maxResults` 默认 5、范围 5..100；`maxPages` 默认 3、范围 1..10。远端无下一页、达到页数上限、游标缺失或重复时停止。
- 摘要汇总请求页数、各状态数量、远端是否仍有下一页，以及是否因页数上限或异常游标停止。自动化测试通过；V5 migration 已在真实 MySQL 成功执行。D3-1 的跨轮 continuation 真实 X API 验收见下方记录。
- D1 提交前修复：页数上限处把下一页 token 和 `maxResults` 保存到 `x_like_sync_state`，后续手动 Sync 从该位置继续；完整追完后清除 continuation，下次回到最新 Likes。`x_like_sync_seen_token` 记录 token 摘要，防止跨次同步循环。
- 游标缺失、重复或被 X 以 HTTP 400 拒绝时标记为 `INVALID` 并停止；429 等暂时错误保留 continuation。只有显式调用 `POST /api/x-import/sync/continuation/reset` 才清除无效状态；重置后可能存在未补齐的 Likes 缺口。一次同步只请求一次 `/2/users/me`。

### V0.3-D2 | X API authentication diagnostics

- 对上游 HTTP 401 返回 `X_CREDENTIAL_REJECTED`，提示检查本地凭据并重启；对 403 返回 `X_ACCESS_DENIED`，提示凭据无效或不适用于当前接口、App/User 权限不足等可能原因，不据此判断凭据已被识别。响应不包含 Access Token 或 Authorization header，其他 X API 错误映射保持原有行为。
- D2 真实 X API 验收中，将本地 Access Token 改为无效测试值并重启后，X 实际返回 403；此观察仅验证该错误路径，不代表 401 或其他认证场景已完成真实验收。
- 当前仍使用本地手动配置的 X Access Token；修改 `X_API_ACCESS_TOKEN` 后必须重启 Spring Boot。未实现 OAuth token lifecycle、refresh token、callback、PKCE 或 token persistence。

### V0.3-D3-1 | Continuation persistence

- 已通过真实 X API 验收：`maxResults=5`、`maxPages=1` 时，每轮后的 `x_like_sync_state.next_token` 推进，Spring Boot 重启后继续使用保存的 token，`x_like_item` 总数随每轮增加，`stoppedByInvalidToken=false`。
- 该验收覆盖截断与重启后继续；未覆盖自然追到末页及完成后再次从最新 Likes 同步。

### V0.3-D3-2 | Sync End State & Idempotency

- D3-2 已完成验收。真实 X API 已验证多页 Likes 分页正常；`maxPages` 截断后 continuation 持久化到 MySQL，下一轮 Sync 从保存的位置继续，而非重新从第一页开始。
- 2026-09-28 验收记录：真实数据库已有 255 条 `x_like_item`；`x_like_sync_state` 为 `ACTIVE` 且保存 `next_token`，因为尚未主动遍历全部历史 Likes。D3-2 验收不要求耗尽真实历史 Likes。
- 自动化回归测试覆盖自然末页（包括恰好达到 `maxPages` 时）返回 `hasMore=false`、两个停止标志为 `false`、清除 continuation，以及下次从最新 Likes 开始；还覆盖重复 `x_post_id` 计入 `existingCount`、不重复插入媒体、保持 `IMPORTED` / `SKIPPED` 状态和 `imported_illustration_id` 关联，以及后续新 Like 进入 `PENDING`。页数截断继续同步及无效 token 防护保留。
- 自然追到真实 X API 末页、末页后重复同步及新 Like 后再次同步未在真实历史 Likes 上执行；这些边界以自动化测试作为本阶段验收依据。

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

README 已按 V0.2 最终能力收尾。V0.2 已完成 Final Acceptance；尚未进行最终 commit、push 或 tag。

### V0.2 Out of Scope

- 登录与权限系统。
- Redis、MQ、Elasticsearch、微服务、Docker 等新基础设施。
- AI、OCR、外部平台集成或对象存储。
- 为本版本目标之外的功能重写现有后端分层或引入大型前端框架。

以上边界遵循 `AGENTS.md` 中的 scope 控制；若未来确有新需求，应先由用户确认范围变化。

### Current next step

V0.3-D3-2 已完成验收：真实 X API 多页同步、截断后 MySQL continuation 与跨轮继续已验证；自然末页、重复同步和 `IMPORTED` 关联保护由自动化回归测试覆盖。无需为了本阶段验收耗尽全部真实历史 Likes。V0.3 尚未宣告完成，后续阶段由项目维护者决定。
