# Illustration Archive

- This is a long-lived Java backend learning project and internship-resume project that should also become genuinely usable.
- Explain important concepts, architecture, transaction boundaries, and business logic clearly; do not hide core reasoning behind needless complexity.

- Keep changes small, explicit, and reviewable. Do not implement work outside the current task scope.
- Do not add complexity merely for resume appeal.
- During exploration, freely compare designs and future options; the scope and phase constraints below govern implementation, not brainstorming or small reversible prototypes that do not touch live data.
- Do not introduce Redis, MQ, Elasticsearch, Docker, Spring Security, microservices, AI, or external integrations unless the current task explicitly requires them.
- Resolve routine implementation details autonomously. Ask when an unresolved choice changes scope or business behavior, while continuing independent work that does not depend on that answer.
- Treat `docs/PROJECT_STATE.md` as the source of truth for the current project phase, completed milestones, and next planned stage. Do not advance the phase unless the task explicitly does so.
- Automated tests and `BUILD SUCCESS` are evidence, not substitutes for real-environment acceptance when behavior depends on MySQL, filesystem state, HTTP, or browser behavior.
- One-off maintenance or backfill jobs must be explicit opt-in operations, default to disabled, and be safe to rerun where practical.

- Preserve the current Controller / Service / Repository structure and JdbcTemplate style unless the task explicitly requires architectural change. Since 2026-10-29 the gallery list read (count, page, page assets) goes through a MyBatis mapper behind `IllustrationRepository`; move further SQL to MyBatis only as an explicitly approved, whole use case.
- The current foundation is Java, Spring Boot, Maven, MySQL, JdbcTemplate (plus MyBatis for the gallery list read), Flyway, and local filesystem storage.
- Do not distort production design merely to make tests easier; tests should adapt to sound production code.

- Store user archive media outside the Git repository. Documentation screenshots and repository documentation assets are allowed.
- The image storage root must be configurable and must not be hardcoded as a machine-specific absolute path in Java code.
- Database records must store relative `storage_key` values, never machine-specific absolute file paths.
- Do not modify already-applied Flyway migrations; schema changes must use a new migration.
- Database transactions cannot roll back filesystem operations; preserve explicit transaction boundaries and file-compensation behavior where needed.

- Never commit passwords, API keys, tokens, or other secrets.
- Git staging, commits, pushes, merges, and branch cleanup are manual user operations unless explicitly delegated.
- Preserve existing user changes. Do not stage unrelated files or temporary review artifacts such as patch files.

- After changes, summarize what changed and explain key design choices.
- Run validation proportional to the change: focused tests for small/local code changes; full test suite for cross-layer/core changes or phase acceptance. Documentation-only changes normally need only `git diff --check`. Follow the current task's explicit validation requirements; do not run Maven test/package by default for every change.
- Report the actual commands run and their results; do not claim behavior was verified if it was not. A unit test that manually constructs a service does not verify Spring transaction rollback.

## Maintenance workflow (2026-10-26)

- Claude and Codex start every task by reading this file and the short `docs/PROJECT_STATE.md`. Read `README.md`, `docs/X_LIKES_SYNC_CONSISTENCY.md`, or source code only as the task needs; do not load `docs/PROJECT_HISTORY.md` by default.
- Claude Code leads development and product/UI/UX design. It may independently explore visual styles, interactions, and implementation approaches, and challenge or adapt Codex suggestions with reasons; Codex's design preferences are not constraints.
- Codex mainly assesses complex technical architecture, database changes, and high-risk issues, with review as needed.
- Fast Lane: ordinary small changes proceed autonomously with focused validation, without mandatory Codex planning/review, double approval, or full-suite testing. Real-data deletion, production migrations, and paid external calls still require explicit authorization.
- Product-direction discussions with ChatGPT use the short project state plus the current question, not the full history.
- Update the matching document only when behavior, configuration, constraints, the project phase, or an important acceptance conclusion changes. Ordinary tasks do not produce standalone long reports; record durable facts in `PROJECT_STATE` or the relevant topic document, and move superseded detail to `PROJECT_HISTORY`.

## Practical design

- Add concurrency controls, defensive checks, retries, or abstractions for concrete risks or requirements, not unsupported hypotheticals. Before material complexity, weigh risk, benefit, and maintenance cost; discuss significant scope or behavior tradeoffs with the user.
- Validate at explicit trust boundaries; rely on established contracts within the call chain instead of repeating checks. Preserve database constraints, required authorization, and necessary security protections.
- Keep simple one-off logic direct; extract helpers when they improve reuse, readability, or testing, not merely to create layers.
- Use try/catch for recovery, compensation, error translation, or meaningful context. Preserve error causes or explicit failure results; do not silently swallow failures or add redundant wrappers.
- These principles do not weaken X Sync concurrency safety, idempotency, request budgets, resumable checkpoints, page transactions, SHA-256 uniqueness, or file compensation and post-commit cleanup. Keep cleanup failures observable without masking the original failure.

## Risk-based validation and tool cost

- Claude and Codex choose coverage by risk and affected behavior, run full regression when warranted, and avoid repeating passed checks unless new changes, failures, or uncertainty justify it. Do not impose fixed tool-call or test-count caps.
- Prefer existing unit/integration tests, CLI checks, HTTP requests, and lightweight browser tools; reuse reliable evidence instead of verifying the same claim through multiple tools.
- Windows MCP, Computer Use, and similar high-interaction tools are not the default for testing. Use them when real GUI behavior cannot be verified more cheaply and automation adds clear value.
- Visual taste, interaction feel, and desktop installation/exit may be left to user acceptance. Provide brief, concrete steps and keep acceptance pending until confirmed.
- Migrations, transactions, concurrency, file deletion, authorization, and similar high-risk changes still require sufficient targeted automated tests; do not reduce necessary coverage to save tool cost.
- Keep work-package reports brief and separate "automated tests passed", "AI-verified behavior", and "awaiting user acceptance"; carry unperformed checks as pending.
