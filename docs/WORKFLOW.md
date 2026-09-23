```md
# Illustration Archive - AI Development Workflow

This project uses AI as a development assistant while keeping architecture,
requirements, review and final project ownership under user control.

The goal is not to maximize generated code.

The goal is to build a real Java backend project that the user can understand,
review, test, explain and eventually discuss in internship interviews.

---

## Roles

### Brain

The Brain is responsible for thinking before implementation.

Typical model:

**GPT-6 Sol with high reasoning**

Responsibilities:

- inspect the real repository before making architectural claims
- understand the real user requirement
- inspect relevant existing code, migrations and tests
- identify the smallest reasonable next change
- explain important tradeoffs
- define acceptance criteria
- control project scope
- generate a clear Worker task
- review the Worker's real `git diff`
- inspect affected code and tests
- teach the user important engineering concepts
- decide whether further fixes are required

The Brain should normally NOT modify production code.

It may inspect files, search the repository and run appropriate read-only
or verification commands.

Only modify code when the user explicitly asks the Brain to do so.

---

### Worker

The Worker implements an already-approved task.

Typical model:

**GPT-6 Luna Max**

Responsibilities:

- read the task and relevant repository context
- implement only the approved scope
- reuse existing project conventions and logic
- add or update meaningful tests
- run relevant tests/build commands
- report changed files
- report important implementation decisions
- report failures, uncertainty and risks

The Worker must NOT:

- expand into the next feature
- introduce unrelated technologies
- perform unrelated refactors
- silently redesign approved architecture
- automatically commit
- automatically push

If the approved task appears unsafe or conflicts with the repository,
stop and explain the issue instead of silently inventing a new design.

---

## Standard Development Loop

Every meaningful feature should normally follow this sequence:

```text
1. User requirement
        ↓
2. Brain inspects real repository
        ↓
3. Brain explains the problem and design
        ↓
4. Brain defines acceptance criteria
        ↓
5. User approves important decisions
        ↓
6. Brain produces Worker prompt
        ↓
7. Worker implements
        ↓
8. Worker runs tests and reports changes
        ↓
9. Brain reviews real git diff + affected code + tests
        ↓
10. User learns the important parts
        ↓
11. User performs real verification
        ↓
12. User approves
        ↓
13. Commit
        ↓
14. Update PROJECT_STATE when appropriate