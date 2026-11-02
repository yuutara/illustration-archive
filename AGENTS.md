# Illustration Archive

- This is a long-lived Java backend learning and internship-resume project whose product goal is a complete Windows desktop app that genuinely works (`docs/DESKTOP_ROADMAP.md`).
- Explain important concepts, architecture, transaction boundaries, and business logic clearly; do not hide core reasoning behind needless complexity.

- Keep changes small, explicit, and reviewable. Do not implement work outside the current task scope.
- Do not add complexity merely for resume appeal.
- During exploration, freely compare designs and future options; the scope and phase constraints below govern implementation, not brainstorming or small reversible prototypes that do not touch live data.
- Do not add new infrastructure or integrations such as Redis, MQ, Elasticsearch, Spring Security, microservices, or further external services unless the current task explicitly requires them. Existing optional capabilities (Docker, S3-compatible storage, X, Gemini) stay as they are.
- Resolve routine implementation details autonomously. Ask when an unresolved choice changes scope or business behavior, while continuing independent work that does not depend on that answer.
- Treat `docs/PROJECT_STATE.md` as the source of truth for the current project phase, completed milestones, and next planned stage. Do not advance the phase unless the task explicitly does so.
- Automated tests and `BUILD SUCCESS` are evidence, not substitutes for real-environment acceptance when behavior depends on MySQL, filesystem state, HTTP, or browser behavior.
- One-off maintenance or backfill jobs must be explicit opt-in operations, default to disabled, and be safe to rerun where practical.

- Preserve the current Controller / Service / Repository structure and JdbcTemplate style unless the task explicitly requires architectural change. Since 2026-10-29 the gallery list read (count, page, page assets) goes through a MyBatis mapper behind `IllustrationRepository`; move further SQL to MyBatis only as an explicitly approved, whole use case.
- The current foundation is Java 21, Spring Boot, Maven, JdbcTemplate (plus MyBatis for the gallery list read), Flyway, and local filesystem storage. The desktop app uses file-based H2 and an SWT + WebView2 window; server mode uses MySQL.
- Do not distort production design merely to make tests easier; tests should adapt to sound production code.

- Store user archive media outside the Git repository. Documentation screenshots and repository documentation assets are allowed.
- The image storage root must be configurable and must not be hardcoded as a machine-specific absolute path in Java code.
- Database records must store relative `storage_key` values, never machine-specific absolute file paths.
- Do not modify already-applied Flyway migrations; schema changes must use a new migration, with equivalent versions in `db/migration` (MySQL) and `db/migration-h2` (H2).
- Database transactions cannot roll back filesystem operations; preserve explicit transaction boundaries and file-compensation behavior where needed.
- A locally running instance may use `target\classes`: never run `mvn clean` in the repository, and build `dist` from a copy outside it, because `scripts/build-desktop.ps1` deletes `target\classes`.

- Never commit passwords, API keys, tokens, or other secrets. Do not write the user's X account handle or ID into tracked files.
- Git staging, commits, pushes, merges, and branch cleanup are manual user operations unless explicitly delegated.
- The Milestones section below says when the user needs to run Git. Give ready-to-run commands that name the exact files; do not wait to be asked.
- Preserve existing user changes. Do not stage unrelated files or temporary review artifacts such as patch files.

- After changes, summarize what changed and explain key design choices.
- Run validation proportional to the change: focused tests for small/local code changes; full test suite for cross-layer/core changes or phase acceptance. Documentation-only changes normally need only `git diff --check`. Follow the current task's explicit validation requirements; do not run Maven test/package by default for every change.
- Report the actual commands run and their results; do not claim behavior was verified if it was not. A unit test that manually constructs a service does not verify Spring transaction rollback.

## Maintenance workflow (2026-10-26)

- Claude and Codex start every task by reading this file and the short `docs/PROJECT_STATE.md`. Read `README.md`, `docs/X_LIKES_SYNC_CONSISTENCY.md`, or source code only as the task needs; do not load `docs/PROJECT_HISTORY.md` by default.
- Claude Code leads development and product/UI/UX design. It may independently explore visual styles, interactions, and implementation approaches, and challenge or adapt Codex suggestions with reasons; Codex's design preferences are not constraints.
- Codex mainly assesses complex technical architecture, database changes, and high-risk issues, and owns Tier C acceptance below, with review as needed.
- Fast Lane: ordinary small changes proceed autonomously with focused validation, without mandatory Codex planning/review, double approval, or full-suite testing. Real-data deletion, production migrations, and paid external calls still require explicit authorization.
- Product-direction discussions with ChatGPT use the short project state plus the current question, not the full history.
- Update the matching document only when behavior, configuration, constraints, the project phase, or an important acceptance conclusion changes. Ordinary tasks do not produce standalone long reports; record durable facts in the relevant topic document.
- Keep `docs/PROJECT_STATE.md` under about 6 KB: phase, branches, authorizations, open acceptance items, restrictions, and next steps only. A finished work package gets at most one line there; its details go to the topic document.
- Record every standing authorization in the state file's authorization table with its scope, exclusions, and end condition. Within that scope, do not ask again; outside it, ask.
- Hand off through `docs/handoffs/NEXT.md`: overwrite it each time, keep it to about 30 lines, link evidence instead of copying it, and never put credentials or account identifiers in it. The file stays out of Git.

## Practical design

- Add concurrency controls, defensive checks, retries, or abstractions for concrete risks or requirements, not unsupported hypotheticals. Before material complexity, weigh risk, benefit, and maintenance cost; discuss significant scope or behavior tradeoffs with the user.
- Validate at explicit trust boundaries; rely on established contracts within the call chain instead of repeating checks. Preserve database constraints, required authorization, and necessary security protections.
- Keep simple one-off logic direct; extract helpers when they improve reuse, readability, or testing, not merely to create layers.
- Use try/catch for recovery, compensation, error translation, or meaningful context. Preserve error causes or explicit failure results; do not silently swallow failures or add redundant wrappers.
- These principles do not weaken X Sync concurrency safety, idempotency, request budgets, resumable checkpoints, page transactions, SHA-256 uniqueness, or file compensation and post-commit cleanup. Keep cleanup failures observable without masking the original failure.
- Product network code inherits the system proxy first, then falls back to the in-app setting. Never hardcode a local proxy address.

## Risk-based validation and tool cost

- Claude and Codex choose coverage by risk and affected behavior, run full regression when warranted, and avoid repeating passed checks unless new changes, failures, or uncertainty justify it. Do not impose fixed tool-call or test-count caps.
- Prefer existing unit/integration tests, CLI checks, HTTP requests, and lightweight browser tools; reuse reliable evidence instead of verifying the same claim through multiple tools.
- Windows MCP, Computer Use, and similar high-interaction tools are not the default for testing. Use them when real GUI behavior cannot be verified more cheaply and automation adds clear value.
- Migrations, transactions, concurrency, file deletion, authorization, and similar high-risk changes still require sufficient targeted automated tests; do not reduce necessary coverage to save tool cost.
- Keep work-package reports brief and separate "automated tests passed", "AI-verified behavior", and "awaiting user acceptance".

## Acceptance tiers

| Tier | Owner | Covers | Closes when |
| --- | --- | --- | --- |
| A | Claude or Codex | Logic, API, persistence, regression: anything tests, HTTP, or CLI can decide | The checks pass |
| B | User | Visual taste, interaction feel, copy, tray, double-click start, install, exit | The user says it passed or reports a problem |
| C | Codex | Migrations, real-data and file-deletion paths, authorization and credentials, cross-layer core flows, packaged release candidates | Codex signs off; defects go back to Claude |
| D | User, on an isolated copy | Real X or Gemini calls, real-library upgrades, anything that spends money or touches real data | The user authorizes it and the check passes |

- Ordinary UI changes are A + B: no Codex handoff and no reminders.
- List a Tier B item once, when it is created, in the state file's acceptance table. It never blocks the next work package. Do not repeat it in later reports unless a new change affects it. Tier B items are reviewed together before a release.
- Tier B starts from a desktop shortcut to an isolated copy, not from a path the user has to find.
- Tier C runs only when a change hits that row. Use Tier C for a small change only if the user asks.
- Claude may check its own UI work on an isolated, no-auto-open server at one or two widths. That is a self-check, not acceptance, and it never closes a Tier B or C item.

## Milestones

A commit is a save point, not a claim of acceptance, so it comes before review. Whichever agent ends the turn at one of these moments gives the user a short 下一步 block, once. Do not prompt between milestones.

| Moment | Git (user runs it) | Also tell the user |
| --- | --- | --- |
| A package passes its automated checks, or the working tree holds more than one finished package | `git add` + `git commit`, before any Codex handoff or packaged candidate | Tier B items to look at; whether Tier C applies, and if so `接 NEXT.md` |
| A round of review or acceptance fixes is done | A new commit on top; never amend or squash reviewed commits unless asked | Who goes next and which paths to re-verify |
| A package is signed off | `git push` | The state file and map are updated; the options for the next package; whether to start a new conversation |
| A phase goal is reached | Merge and tag, if the user wants them | The Tier B sweep, the next phase, a new conversation |

- Small changes outside a package ride along with the next commit.
- A review targets the commit named in `NEXT.md`. Fixes go in later commits, so nothing needs undoing.

### Project map and ideas

- `docs/PROJECT_MAP.md` is the user's one-page view in plain Chinese: the roadmap, each capability's code and real-environment status, decisions waiting on the user, and the idea pool. Keep it to about 60 lines. Details stay in the topic documents.
- Update the map when a package starts or finishes, or when something it states changes. When the user asks where the project stands, answer from the map.
- The user chooses the next package. Before planning or development starts, say in plain words what the package does, what the user gets from it, and why it is the suggested next step, and list the realistic alternatives. A code name alone is not enough.
- `想法：…` in any conversation is the way in for a new idea. Sort it and reply in one line saying where it went:
  - A defect in the current package: fix it there.
  - A small change: do it through the Fast Lane, here or in a `维护` conversation.
  - A new feature or change of direction: add it to the idea pool without interrupting current work, and bring it up at the next milestone.

### New conversations

- Suggest a new conversation when a package is signed off, a phase ends, the work moves between planning, development, and review, the topic is unrelated, or context has been summarized and fresh work is starting. Do not suggest one in the middle of a package.
- Each agent judges its own current conversation against these triggers and says so when one applies, Codex included. Codex also leads the call for planning, review, and acceptance work across the project; Claude raises it for Fast Lane work with no Codex involved.
- Give a ready-to-use name and the opening line. Name format: `R<round>.<package> <name> <规划|开发|审核|验收>`, such as `R3.1 安装器 开发`; work outside the roadmap uses `维护 <topic>`.
- An agent that can rename its own conversation does so when the title is stale and the user did not set it by hand.

## Housekeeping

- Keep the source tree free of temporary files. Working files go in the tool's scratch directory and acceptance evidence in `D:\Temp\ia-*`. Only the ignored `dist/`, `target/`, and `.maven/` hold generated output in the repository.
- Claude and Codex decide for themselves when to update, condense, merge, or delete documents and their own generated output, and report it in one line. Do not ask first.
- Fix or delete outdated text in place instead of adding correction notes. Git history is the archive for committed text; `PROJECT_HISTORY` is frozen and does not grow.
- `dist/`: keep the newest signed-off package and any package under acceptance, and delete the others when a new package is built.
- `D:\Temp\ia-*`: once a package is signed off and committed, delete bulky intermediates and keep each `result.md`.
- Ask first before deleting anything that holds real data, credentials, or backups (acceptance homes, copies of the real library, `D:\IllustrationArchiveBackups`), evidence for an open item, or anything a package under acceptance depends on. Changes to rules files and phase decisions also stay with the user.
