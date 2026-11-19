---
name: kiemlai-performance-by-design
description: Mandatory proactive performance review for KiemLai Java/Spring/Thymeleaf changes that create or modify HTTP/SSR/API routes, persistence reads/writes, cross-module query composition, media delivery, or JavaScript data fetching. Use before implementation and during verification; prevent unnecessary queries, transaction round trips, N+1, and duplicate browser requests.
---

# KiemLai Performance by Design

This skill complements `kiemlai-engineering`. It is **preventive engineering
discipline**, not permission to refactor unrelated code or chase theoretical
milliseconds. Apply it for any user-visible data path, including new features.

## 1. Performance gate BEFORE coding

For the affected path, write a short critical-path assessment (5-15 lines
for normal tasks; a compact table for complex requests):

- Entry point: SSR controller, API GET/POST, JavaScript fetch, scheduled work.
- Dependencies: application use cases, cross-module contracts, DB adapters,
  identity, reactions/comments, media, external storage/network services.
- Read/write plan: expected DB statements and whether each is one-off, batched,
  paginated, or per-item; note **unknown** counts instead of inventing numbers.
- Transaction plan: which service owns the boundary; expected connection
  acquisitions and transaction lifecycles; account for authenticated request
  filters and any separately loaded API requests.
- Browser plan: initial HTML vs subsequent fetches; anticipated API calls,
  response sizes, duplicate initialization risks, and critical first paint.
- Risk/acceptance: worst plausible growth as page size/comment depth grows;
  important correctness invariants, and a focused performance verification.

For changes that cannot affect a runtime/data path (e.g., text-only renaming),
record `Performance impact: none` and skip this gate.

## 2. DB access: design for bounded round trips

- Before adding a list/detail/feed endpoint, inspect the real call graph through
  services/ports/adapters. Do not count only repository calls visible in the
  controller; include hidden identity, reaction, appreciation, publication,
  contribution, and media reads.
- Avoid N+1 and per-row network round trips. Prefer bounded projections,
  set-based/batched queries, or existing batch ports when semantics allow.
- Prefer bounded pagination; reject or cap inputs according to each endpoint's
  established contract. Preserve stable ordering and page metadata.
- Do not hydrate large content, blobs, or aggregates to obtain only list metadata.
- Avoid duplicate identity/profile lookups and repeated public-eligibility
  lookups when an existing safe batch API or request-scoped result can serve them.
- Write paths must also avoid per-item write/query loops where a safer bounded
  approach exists; preserve lock/version/authorization guarantees.
- Do not change schema, add indexes, or introduce caches based on guesses. Where
  relevant, investigate cardinality and query plans using approved isolated or
  read-only evidence.

## 3. Transactions: reduce overhead WITHOUT breaking correctness

- Remote Aiven MySQL adds WAN latency. Estimate sequential SQL and transaction
  **round trips** as first-class design costs; do not present estimates as
  confirmed timing.
- Consider a Spring-managed application-layer read coordinator when multiple
  compatible DB reads belong to one response. Do not mandate one transaction
  for every request: security filters, truly separate operations, concurrency
  safety, or external calls may require distinct boundaries.
- A coordinator must be invoked through a Spring proxy; do not instantiate
  transactional services with `new` in the production controller. No silent
  no-op dependency fallback.
- Validate real transaction manager and datasource participation, especially
  JPA + JdbcTemplate mixed reads and nested `@Transactional(REQUIRED)` calls.
- Audit every `try/catch` across a participating transactional service. An
  unchecked exception can mark the shared transaction rollback-only even if
  caught by the caller. Do not swallow DB failures or globally disable rollback.
  Preserve graceful handling of normal missing/blank data without exceptions.
- Keep Markdown, SSR rendering, remote storage/network calls, and other slow
  non-DB work outside short DB transactions whenever semantics permit.
- Preserve access control, publication checks, viewer-specific isolation,
  concurrent updates, and Media's two-phase representation/race checks. Never
  eliminate required safety queries solely to improve latency.

## 4. Browser and SSR performance by design

- Keep essential content server-rendered and available at first paint.
- Do not block primary content on optional comment/reaction/notification feeds;
  where feasible, load them progressively without changing their contract.
- Inspect JavaScript event listeners and initialization for duplicate fetches,
  repeated render cycles, uncontrolled retries, or fetch-per-item patterns.
- Consider the full user-perceived path: HTML request, deferred API requests,
  images, CSS/JS, layout stability, and mobile screen widths.
- Preserve responsive layout, image fallback, direct Media URL contracts, cache
  correctness and appropriate response headers. Do not disable fresh-data
  checks or extend caching on mutable URLs as a shortcut.
- Only use an existing frontend stack: Thymeleaf, HTML, CSS, Vanilla JS.

## 5. Minimal, focused verification DURING the feature

For any material new/changed data path, add focused verification appropriate
to the risk (not all tests for every small change):

- Bounded query count / batch semantics under representative nonempty data,
  including multiple roots/replies/assets where N+1 could hide.
- Guest vs authenticated viewer and different authors/users, without leaking
  private saved, reaction, appreciation, or other viewer-specific state.
- Actual Spring AOP/proxy and rollback participation when transaction semantics
  matter; Mockito-only tests do **not** prove transaction consolidation.
- Duplicate frontend fetch prevention and stable response/status contracts.
- Baseline versus changed-path evidence, when available, on equivalent
  endpoints, data, authentication, cache state and device conditions.

Use `Server-Timing`, stage timings, JDBC counts/durations, Chrome Network
(document versus Fetch/XHR), and 3+ warm runs when useful. Separate server
TTFB from API feed wait, static asset load, and browser paint. Name all
unmeasured quantities as estimates, not measured results.

Never run unsafe load tests or database-writing tests against shared Aiven.
Do not start/restart the owner's app. For owner-executed perf runs, require
Flyway explicitly disabled and ideally a SELECT-only DB account; application
`readOnly`/Hikari settings do not grant a safe privilege boundary.

## 6. Performance acceptance in EVERY relevant task report

Add a brief section headed `Performance by Design`:

1. **Path:** Which endpoint/feature and which viewer state.
2. **Shape:** Number of expected/observed SQL statements, transaction
   lifecycles, connection acquisitions, browser API fetches; say
   `unknown/not measured` where not verified.
3. **Prevention:** Which N+1, duplicate query, excess transaction, large
   response, or duplicate frontend fetch was avoided, or why no optimization
   was warranted.
4. **Evidence:** Tests actually run and runtime measurements if any; clearly
   distinguish source analysis, test proof, and owner-observed runtime.
5. **Risks:** Any unavoidable extra round trips and how to measure them later.

When a change clearly introduces a per-item query, unnecessary second
transaction, duplicate browser fetch, or unbounded read path, fix it **within
the feature scope** if the safe solution is evident. Otherwise flag it
**before marking the feature complete**, with a precise bounded follow-up.
Never hide correctness risks or expand to unrelated optimizations.

## 7. Explicit anti-patterns

Avoid:
- `for each comment -> query author/reaction/children`;
- `for each image -> query media asset/version/variant` when an approved batch
  contract can preserve the same eligibility/fallback behavior;
- multiple sequential read transactions for one composed response without an
  explicit reason;
- catching `RuntimeException` across a participating transactional proxy and
  assuming the outer transaction is still committable;
- fetching the same feed twice on initial load;
- loading very large content just to display a list card;
- claiming a measured speedup based only on code inspection or mock tests.

These are review triggers, **not automatic rewrite instructions**. Follow
existing module-specific safety contracts and choose the smallest correct
implementation.
