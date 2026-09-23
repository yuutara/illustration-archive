# Illustration Archive - Project State

> This file records the current project state and important design decisions.
> When summaries and the actual repository conflict, the repository is the source of truth.

## Current Version

V0.2 - Archive Quality & Gallery Experience

Current development stage:

**B2 - Thumbnail integration and historical thumbnail backfill**

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

## V0.2 Progress

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

Current database state:

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
originals/2026-09/example.jpg
↓
thumbnails/2026-09/example.jpg