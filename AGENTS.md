# KiemLai Repository Agent Entry Point

For every KiemLai code task, first read:
- `.agents/skills/kiemlai-engineering/SKILL.md`
- Relevant module-specific skills for the affected code.

For any new or changed HTTP/SSR/API route, persistence/query path, cross-module
data composition, Media delivery path, or JavaScript data fetch, **before coding**
also read `.agents/skills/kiemlai-performance-by-design/SKILL.md`.

Apply its lightweight performance-by-design gate as part of implementation and
verification. Avoid speculative optimizations; correctness, authorization,
transaction safety, and module-specific invariants are non-negotiable.

The owner controls Git commits/pushes, app restarts, shared Aiven migrations
and storage mutations. Never inspect private agent brain/transcript files.
