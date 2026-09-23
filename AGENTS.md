# Illustration Archive

- This is a long-lived Java backend learning project and internship-resume project that should also become genuinely usable.
- Explain important concepts, architecture, transaction boundaries, and business logic clearly; do not hide core reasoning behind needless complexity.

- Keep changes small, explicit, and reviewable. Do not implement work outside the current task scope.
- Do not add complexity merely for resume appeal.
- Do not introduce Redis, MQ, Elasticsearch, Docker, Spring Security, microservices, AI, or external integrations unless the current task explicitly requires them.
- If requirements are unclear in a way that affects scope or business behavior, or a change would clearly expand scope, stop and explain rather than extending the design independently.
- Treat `docs/PROJECT_STATE.md` as the source of truth for the current project phase, completed milestones, and next planned stage. Do not advance the phase unless the task explicitly does so.
- Automated tests and `BUILD SUCCESS` are evidence, not substitutes for real-environment acceptance when behavior depends on MySQL, filesystem state, HTTP, or browser behavior.
- One-off maintenance or backfill jobs must be explicit opt-in operations, default to disabled, and be safe to rerun where practical.

- Preserve the current Controller / Service / Repository structure and JdbcTemplate style unless the task explicitly requires architectural change.
- The current foundation is Java, Spring Boot, Maven, MySQL, JdbcTemplate, Flyway, and local filesystem storage.
- Do not distort production design merely to make tests easier; tests should adapt to sound production code.

- Store user archive media outside the Git repository. Documentation screenshots and repository documentation assets are allowed.
- The image storage root must be configurable and must not be hardcoded as a machine-specific absolute path in Java code.
- Database records must store relative `storage_key` values, never machine-specific absolute file paths.
- Do not modify already-applied Flyway migrations; schema changes must use a new migration.
- Database transactions cannot roll back filesystem operations; preserve explicit transaction boundaries and file-compensation behavior where needed.

- Never commit passwords, API keys, tokens, or other secrets.
- Unless explicitly requested, do not run `git commit` or `git push`.
- Preserve existing user changes. Do not stage unrelated files or temporary review artifacts such as patch files.

- After changes, summarize what changed and explain key design choices.
- Run validation proportional to the change: focused tests for small/local code changes; full test suite for cross-layer/core changes or phase acceptance. Documentation-only changes normally need only `git diff --check`. Follow the current task's explicit validation requirements; do not run Maven test/package by default for every change.
- Report the actual commands run and their results; do not claim behavior was verified if it was not. A unit test that manually constructs a service does not verify Spring transaction rollback.
