# Illustration Archive - Project State

> This file records the current project state and important design decisions.
> When summaries and the actual repository conflict, the repository is the source of truth.

## Current Version

V0.2 - Archive Quality & Gallery Experience

Current development stage:

**V0.2 COMPLETE; Final Acceptance / 封版已完成。最终 commit、push 和 tag 待手动执行。**

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

V0.2 Final Acceptance 已完成。下一步由项目维护者手动进行最终 commit、push 和 tag；此处不记录为已发布。
