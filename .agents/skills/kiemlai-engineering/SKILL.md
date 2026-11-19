---
name: kiemlai-engineering
description: Core engineering rules and architecture conventions for the KiemLai project. Use this skill whenever analyzing, implementing, debugging, refactoring, or testing code in the KiemLai repository.
---

# KiemLai Engineering

KiemLai is a Java 17 Spring Boot Modular Monolith.

This skill defines durable project-wide engineering rules.

Module-specific business invariants belong in their corresponding module skills.

Temporary task details, current bugs, test counts, Git status, and uncommitted work
belong in the current task context, not in this skill.

## Architecture

Preserve the existing Clean Architecture / DDD-inspired structure:

- domain
- application
- contracts
- entry
- infrastructure

Keep controllers thin.

Business logic belongs in application/domain layers.

Persistence logic belongs in infrastructure.

Do not expose JPA entities directly to controllers or views.

Prefer ports and adapters.

Preserve existing aggregate boundaries unless a task explicitly requires an
architectural change.

## Dependency Boundaries

Domain must remain framework-free.

Application must not depend on:

- JPA entities
- Spring Data repositories or types
- infrastructure adapters
- framework-specific persistence exceptions

Infrastructure-specific failures should be translated before crossing an
application boundary when application behavior depends on those failures.

Cross-module communication must use explicit contracts or ports rather than
directly importing another module's repositories, persistence entities, or
internal infrastructure.

Do not introduce a cross-module dependency merely because the required data
exists in another module.

## Database

Shared/remote Aiven MySQL is owner-managed, not a disposable test database.
Without explicit owner approval, never apply Flyway, run DDL/DML, execute
database-mutating integration tests, or start/restart a database-connected app
that may auto-apply migrations. Performance verification must not rely on
Hikari `readOnly` alone as a write-access security boundary.

Use Flyway for schema changes.

Never modify an already-applied migration unless the user explicitly approves it.

Create a new migration for future schema changes.

Preserve existing database naming, UUID storage, charset, collation, constraint,
and indexing conventions.

Do not introduce JPA relationships such as `@ManyToOne`, `@OneToMany`, or
`@ManyToMany` merely for convenience when the current aggregate design
intentionally uses scalar IDs.

Do not add speculative schema complexity for future capabilities that do not
currently exist.

A future Flyway migration is an acceptable way to evolve the schema when the
product actually requires new capabilities.

## Persistence Conventions

Persistence-specific state belongs in infrastructure unless it represents a real
domain concept.

Examples include:

- JPA `@Version`
- persistence version counters
- Hibernate-specific state
- database locking implementation details

Do not add persistence fields to domain aggregates merely to make JPA mapping
easier.

Preserve scalar-ID relationships and aggregate boundaries established by the
existing model.

When concurrency matters, make the transaction boundary explicit and ensure the
locking/version mechanism actually protects the state observed by that
transaction.

Do not rely on undocumented persistence-context behavior or accidental call order.

Prefer lightweight query projections when a read path needs only a small subset
of fields.

Do not hydrate large aggregates or large content fields solely to obtain simple
metadata when a small query projection is appropriate.

## Time and IDs

Reuse the project's established time and ID-generation conventions.

If the project already provides Clock/time or ID-generation ports, use them.

Do not introduce direct calls such as:

- `Instant.now()`
- `UUID.randomUUID()`

inside application/domain behavior when an established project abstraction
already exists for that responsibility.

Keep time-dependent behavior deterministic and testable.

## Frontend

Current frontend stack:

- Thymeleaf
- HTML
- CSS
- Vanilla JavaScript

Do not introduce React, Vue, HTMX, jQuery, npm, or another frontend framework
unless explicitly approved.

Preserve the existing server-rendered application architecture.

Do not add frontend dependencies for behavior that can be implemented cleanly
with the current stack.

## Testing

Run targeted tests first.

Do not weaken, delete, skip, or change tests merely to make them pass.

Understand the cause of a failure before changing production code or tests.

Follow the project's existing JUnit 5, Mockito, AssertJ, integration-test, and
test-naming conventions.

Add tests for new business invariants and important failure paths.

Use unit tests for pure domain/application behavior where possible.

Use integration tests when correctness depends on real framework or database
behavior such as:

- JPA mappings
- Flyway migrations
- optimistic/pessimistic locking
- transaction boundaries
- database constraints

Ask before running tests that require external infrastructure unless permission
was already given for the current work.

Run the full regression suite at milestone boundaries or before integration when
appropriate.

## Git Safety

Do not inspect private agent brain/reasoning/transcript files, including
`.gemini/.../brain`, conversation logs, or private execution caches.
Use repository source, approved instructions, and task-provided evidence only.

Do not automatically:

- commit
- push
- merge
- rebase
- reset
- clean
- discard user changes

Inspect the working tree before broad changes.

Do not modify unrelated files.

Do not include generated, local-agent, temporary, credential, or environment
files unless they are intentionally part of the repository.

Prefer small, reviewable checkpoints aligned with one coherent feature or fix.

## Minimal Diffs and Whitespace Discipline

Preserve the existing formatting style of every file you edit.

Do not reformat, re-indent, reorder, or normalize unrelated code.

Do not introduce unnecessary blank lines or trailing whitespace.

Do not change line endings unless the task explicitly requires it.

A focused feature change must not contain whitespace-only changes in unrelated
lines or files.

Make the smallest line-level change necessary for the requested task.

When editing an existing tracked file, modify it in place. Do not recreate or
rewrite the whole file merely to make a small change.

Do not run a formatter, import organizer, auto-fix command, or IDE cleanup action
when it would rewrite unrelated sections of a file unless repository-wide
formatting was explicitly requested.

Preserve surrounding indentation, wrapping, blank-line style, and ordering when
the existing code is valid.

Before reporting completion, run:

`git diff --check`

Review the actual `git diff` before considering the task complete.

Remove or revert whitespace-only changes that are unrelated to the requested
behavior.

If a file shows unexpectedly large formatting changes compared with the actual
feature scope, investigate and reduce the diff before reporting completion.

Do not treat a clean `git diff --check` result as proof that the diff is minimal;
unnecessary blank lines, reformatting, or line-ending churn may still be valid
whitespace syntactically but must still be removed.

## Implementation Discipline

Before creating a new:

- port
- DTO
- exception
- service
- adapter
- abstraction
- configuration mechanism

inspect the existing implementation and project conventions first.

Reuse an existing abstraction when it correctly represents the required behavior.

Otherwise introduce the smallest new abstraction required by the current task.

Do not add speculative:

- multi-tenant architecture
- multi-aggregate infrastructure
- multi-product support
- generic frameworks
- future-proof abstractions

for capabilities that do not currently exist.

Optimize for the current domain while leaving normal migration/refactoring paths
open for future requirements.

Correctness and established domain semantics take priority over premature
generalization.

Avoid unrelated refactoring while implementing a feature.

## Work Style

Before implementing a medium or large feature:

1. Inspect the existing implementation.
2. Understand the established project pattern.
3. Identify relevant domain and module invariants.
4. Propose the smallest architecture-consistent solution.
5. Implement only the approved scope.
6. Run targeted tests.
7. Report changed files and important decisions.
8. Stop and wait before starting another feature.

For small, clearly scoped changes, avoid unnecessary architecture discussion when
the existing pattern already determines the solution.

Do not silently expand the task into adjacent features.

## Verification

After implementation, report concisely:

- files created or modified
- important architecture or business decisions
- tests run and results
- remaining risks or follow-up items
- Git status when relevant

Do not restate the entire architecture unless requested.

Do not claim a concurrency, migration, persistence, or integration guarantee based
only on mocked unit tests when the behavior depends on real framework/database
semantics.

When a task changes framework-sensitive behavior, verify it at the appropriate
integration level.

## Performance and Read-Path Engineering

### Proactive performance-by-design gate

For any new or changed HTTP/SSR/API route, database read/write path,
cross-module data composition, Media delivery path, or browser data-fetching
flow, read and apply `.agents/skills/kiemlai-performance-by-design/SKILL.md`
**before implementation**, not only after a performance regression.

Identify likely SQL round trips, transaction/connection boundaries, N+1
patterns, frontend requests, and user-specific data risks; design a bounded
read/write path as part of the feature acceptance criteria. Record a concise
performance assessment in the completion report. For trivial changes with no
runtime/data-path impact, state "performance impact: none" without creating
new tests or abstractions.

Design-time estimates are not runtime measurements. Preserve the
measurement-first requirements below whenever claiming a measured bottleneck
or performance improvement. Correctness, isolation, security, and established
Media/Reader invariants take priority over reducing query counts.

### Measurement-first optimization

- Never declare a bottleneck from source structure alone.
- Distinguish:
  - structurally suboptimal;
  - plausibly performance-relevant;
  - runtime-measured bottleneck.
- Prefer Server-Timing, SQL counts, transaction counts, Chrome Network timing,
  FCP/LCP/CLS, and focused runtime evidence.
- A performance milestone may close with no implementation when runtime is
  already healthy.

### Remote DB / transaction awareness

- Development may use remote Aiven MySQL over WAN.
- Distinguish:
  - SQL execution;
  - connection acquisition;
  - transaction control;
  - application residual time;
  - storage/download;
  - browser behavior.
- Never weaken correctness, transaction boundaries, or race safety merely to
  hide WAN latency.
- App/DB co-location belongs to deployment/topology work when out of scope.

### Authenticated Reader identity

- Normal authenticated Reader requests consume the request identity snapshot
  produced by AccountStatusFilter.
- Downstream Reader code must reuse that snapshot instead of introducing a
  second normal-path identity query.
- Fallback lookup is allowed only when the request identity snapshot is
  legitimately unavailable.

### Shared Reader cache boundaries

- Shared Reader caches contain only user-neutral/public data.
- Bookmark, progress, history, and other user-specific state stay outside.
- DB-backed cache loaders own short read-only transactions.
- Mutation-driven invalidation must preserve commit/failure consistency.
- Use AFTER_COMMIT semantics where invalidation must happen only after a
  successful mutation commit.
- Generation/in-flight/coordination state must remain bounded.
- A transient narration/rendering failure must not populate the shared rendered
  chapter cache unless the established cache contract explicitly permits it.

### Media delivery two-phase race safety

The approved Media content path is intentionally two-phase.

Phase 1:
- resolve current public delivery metadata.

Controller:
- ETag / HEAD / Range / If-Range decisions.

Phase 2 for body responses:
- resolve a fresh current representation;
- compare it against Phase-1 metadata;
- open storage only if unchanged.

Never remove Phase 2 as a "redundant query".

Locked SQL matrix:

- HEAD 200: 1 SQL
- 304: 1 SQL
- 416: 1 SQL
- GET 200: 2 SQL
- Range 206: 2 SQL
- If-Range mismatch full 200: 2 SQL

Phase 2 protects concurrent:
- version advancement;
- lifecycle changes;
- visibility changes;
- storage-provider changes;
- representation metadata changes.

### Media HTTP caching

/media/assets/{assetId}/content

is a mutable current-version URL.

Therefore:
- keep Cache-Control: public, no-cache;
- keep strong ETag;
- preserve HEAD / 304 / Range / If-Range behavior;
- never mark this current-version URL immutable;
- long-lived immutable caching requires a version-stable/hash-stable URL.

### HTTP response compression

- Text response compression is enabled for appropriate HTML/CSS/JS/JSON/XML.
- Do not add audio/*, image/*, or already-compressed binary/media formats merely
  for perceived optimization.
- Preserve byte-range semantics for Media delivery.

### Static browser caching

- Do not use long-lived immutable caching for unversioned CSS/JS URLs.
- Content fingerprinting/versioned URLs must exist before aggressive immutable
  static caching.
- Spring Boot DevTools intentionally reduces/disables static caching during
  development; do not globally override development freshness merely to improve
  localhost benchmark numbers.
- Production fingerprinting/CDN/reverse-proxy policy belongs to dedicated
  deployment/static-delivery work.

### Reader first-paint

- Chapter prose remains server-rendered in the initial HTML.
- Do not introduce client-side reconstruction or hiding of core prose without
  approved architectural reason.
- Narration/player/progress/history/settings features must not block initial
  prose paint.

### Performance testing workflow

- Run only focused/relevant tests for incremental performance subtasks.
- Do not run the full repository suite after every slice.
- Reserve broad regression for the final major milestone unless explicitly
  required earlier.
- Use runtime acceptance for behavior unit tests cannot prove, including:
  compression, browser cache headers, Range responses, network timing, and
  FCP/LCP/CLS.

## Task Prompt Rules

Rules in this skill are implicit for every KiemLai task and do not need to be
repeated in individual task prompts.

Task prompts should primarily describe:

- the behavior to implement
- task-specific business invariants
- genuinely task-specific architectural decisions
- explicit scope exclusions when needed to prevent accidental expansion
- acceptance criteria and important tests

Do not repeat generic instructions already covered by this skill, such as:

- keeping controllers thin
- preserving Clean Architecture
- avoiding JPA leakage
- using Flyway
- using the existing frontend stack
- running targeted tests first
- avoiding unrelated refactoring
- not committing or pushing automatically
- reporting changed files after implementation

Module-specific durable rules should live in the appropriate module skill rather
than being repeated across task prompts.

## Workflow Discipline

### Source discovery

For code/reference/test discovery:

- prefer built-in Search/Find;
- then Read only relevant matches;
- do not use `git grep`, `Select-String`, PowerShell search commands, or helper scripts when Search/Read can perform the task.

Reserve shell commands for genuine shell work such as Git hygiene, build/test/runtime commands.

### Long-running foreground commands

When a foreground command is running:

- never issue dummy waiting commands;
- never poll with unrelated commands;
- never launch the same command again;
- wait for normal tool completion;
- do not intentionally convert a foreground build/test command into a background task merely because it is long-running;
- if the tool environment automatically backgrounds it, report that fact once and do not launch/poll a replacement command.

### Explicit stop/yield reporting

Never silently end a work turn while the requested task is incomplete.

If execution must stop/yield before completion, print:

STOP REASON: <specific reason>
NEXT ACTION: <specific remaining action>

Applicable reasons include:

- permission required;
- tool/runtime error;
- ambiguity requiring user clarification;
- foreground-command/tool limitation;
- context/turn limit;
- task completion.

If no blocker exists, continue the requested scope.