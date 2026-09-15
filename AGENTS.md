# Illustration Archive

- This is a long-lived Java backend learning project and internship-resume project that should also become genuinely usable.
- The user is a Java backend beginner. Explain important concepts, architecture, and business logic; do not hide them behind needlessly complex implementations.
- Keep changes small, explicit, and reviewable. Do not implement work outside the current task scope.
- Do not add complexity merely for resume appeal. The current foundation is Java, Spring Boot, Maven, and MySQL.
- Do not introduce Redis, MQ, Elasticsearch, Docker, Spring Security, microservices, AI, or external integrations unless the current task explicitly requires them.
- Store original media outside the Git repository. Never commit real images or other media files.
- The image storage root must be configurable and must not be hardcoded as a machine-specific absolute path in Java code.
- Future database records must store relative `storage_key` values, never machine-specific absolute file paths.
- Never commit passwords, API keys, tokens, or other secrets.
- Unless explicitly requested, do not run `git commit` or `git push`.
- After changes, summarize what changed, explain key design choices, and run task-relevant tests or build commands.
- If requirements are unclear or a change would clearly expand scope, stop and explain rather than extending the design independently.
