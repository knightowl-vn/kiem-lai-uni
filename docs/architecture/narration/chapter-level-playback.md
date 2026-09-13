# Chapter-Level Narration Playback Architecture

Milestone: `MS-04.9H.9` / `H.9I5E`  
Status: **Authoritative Current Architecture** (Implemented)

---

## 1. Current Architecture Overview

The KiemLai Novel Reader delivers managed audio through continuous, chapter-level audio artifacts accompanied by precise cue timelines for follow-along reading. Public Reader delivery is strictly chapter-oriented, while per-segment synthesis and reconciliation are preserved as internal generation primitives.

### End-to-End Delivery Flow

```text
NarrationController
├─ ManagedAudioEngine
│  └─ GET /api/novel/narration/voices
│     catalog discovery only
│
└─ ChapterAudioEngine
   └─ GET /api/novel/chapters/{chapterId}/narration/playback
      passive persisted-state lookup
      ↓ when unavailable and a committed playback intent requires preparation
      POST /api/novel/chapters/{chapterId}/narration/prepare
      ↓
      PreparePublicChapterNarrationPlaybackUseCase
      ↓
      ReaderChapterNarrationPreparationDispatcher
      ↓
      ReaderChapterNarrationPreparationWorker
      ↓
      GenerateChapterNarrationUseCase
      ↓
      BuildChapterNarrationPlaybackUseCase
      ↓
      Media / Finalization
      ↓
      GET /playback eventually READY
      ↓
      ChapterAudioEngine playback
```

---

## 2. Core Architectural Invariants

1. **Reader never plays managed segments directly:**  
   The Reader consumes only continuous chapter-level audio artifacts via one `HTMLAudioElement`. Segment-level audio playback in the client has been completely retired.

2. **No public Reader manifest delivery:**  
   The legacy endpoint `GET /api/novel/chapters/{chapterId}/narration/manifest` is **RETIRED and REMOVED**. Public manifest DTOs and query use cases have been deleted.

3. **No public per-segment prepare endpoint:**  
   The legacy endpoint `POST /api/novel/chapters/{chapterId}/narration/segments/{segmentId}/prepare` is **RETIRED and REMOVED**. All segment-level preparation use cases and requests have been deleted.

4. **`ManagedAudioEngine` is catalog-only:**  
   In the browser, `ManagedAudioEngine` is strictly responsible for voice catalog discovery via `GET /api/novel/narration/voices`. It contains no playback, buffering, segment loading, or prepare logic.

5. **`ChapterAudioEngine` is Managed Reader playback authority:**  
   `ChapterAudioEngine` owns all Managed Audio playback in the Reader, including audio streaming, timeline progress, ±5s seek, cue tracking, and chapter-level preparation requests.

6. **`GET /playback` is passive persisted-state lookup:**  
   `GET /api/novel/chapters/{chapterId}/narration/playback` is completely side-effect-free. It performs a lightweight read model lookup against the database without triggering synthesis, transcoding, or assembly.

7. **`POST /prepare` initiates chapter-level preparation:**  
   `POST /api/novel/chapters/{chapterId}/narration/prepare` is a public Reader command endpoint with CSRF protection. Application-layer Reader access checks validate chapter/volume visibility and the requested active Managed voice before dispatch. Background preparation is coordinated through the single-flight dispatcher.

8. **Preload remains speculative and never POSTs `/prepare`:**  
   Next-chapter transition preload inspects chapter availability passively via `GET /playback`. It never issues `POST /prepare` calls speculatively, preventing wasted server-side synthesis.

9. **Segments remain internal generation/reuse primitives:**  
   `ChapterNarrationSegment` and `ChapterNarrationAudio` remain the internal foundation for adaptive chunking, TTS provider synthesis, caching, reconciliation, Admin inspection, and assembly input. They are never exposed directly to the public Reader.

10. **`STALE_CONTENT` is not playable:**  
    If chapter text or structure changes such that `sourceContentVersion` or `manifestHash` diverges from the artifact, the artifact is marked `STALE_CONTENT` and must NOT be served as normal managed playback.

11. **`CURRENT` and permitted `STALE_VOICE` chapter audio may be playable:**  
    An artifact matching current text is `CURRENT` and fully playable. If provider voice settings change without text modification, `STALE_VOICE` artifacts may remain playable while signaling `refreshRecommended = true`.

12. **Media asset/version lifecycle remains authoritative for produced audio:**  
    Assembled chapter audio is uploaded to Media Core as an immutable `PUBLIC AUDIO` candidate asset. Only after the finalization transaction commits does the stable pointer switch, with superseded artifacts safely scheduled for asynchronous media cleanup.

---

## 3. Public Reader HTTP Surface

| Method | Endpoint | Status | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/novel/narration/voices` | **ACTIVE** | Lightweight voice catalog discovery consumed by `ManagedAudioEngine`. |
| `GET` | `/api/novel/chapters/{chapterId}/narration/playback` | **ACTIVE** | Passive read-only chapter playback metadata and cue timeline lookup. |
| `POST` | `/api/novel/chapters/{chapterId}/narration/prepare` | **ACTIVE** | Public Reader command endpoint (with CSRF protection) to trigger chapter narration build. |
| `GET` | `/api/novel/chapters/{chapterId}/narration/manifest` | **RETIRED / HISTORICAL** | *Deleted in H.9I5D.* Formerly served per-segment manifest JSON. |
| `POST` | `/api/novel/chapters/{chapterId}/narration/segments/{segmentId}/prepare` | **RETIRED / HISTORICAL** | *Deleted in H.9I5D.* Formerly triggered individual segment synthesis. |

---

## 4. Infrastructure & Concurrency Architecture

### Thread Pool Configuration
The dedicated executor for background chapter narration preparation is configured in:
`src/main/java/com/universe/novel/infrastructure/narration/config/ReaderChapterNarrationPreparationConfig.java`

- **Bean Name:** `readerChapterNarrationPreparationTaskExecutor`
- **Core Pool Size:** 2
- **Max Pool Size:** 4
- **Queue Capacity:** 50
- **Thread Name Prefix:** `reader-chapter-narration-prep-`
- **Saturation Policy:** `ThreadPoolExecutor.AbortPolicy` (strictly rejects excess tasks to prevent executing heavy background work on the HTTP request caller thread).

### Single-Flight Coordination
`ReaderChapterNarrationPreparationDispatcher` (`src/main/java/com/universe/novel/application/narration/ReaderChapterNarrationPreparationDispatcher.java`) enforces an in-memory concurrent map keyed by `(chapterId, managedVoiceId)`. If preparation is already queued or actively running for a given key:
- Subsequent duplicate requests immediately receive `ALREADY_IN_FLIGHT`.
- On completion or unexpected termination, the key is evicted.
- On thread-pool saturation, `RejectedExecutionException` is caught, the key is immediately cleared, and status `REJECTED` is returned.

---

## 5. Historical Audit Context (MS-04.9H.9A)

> [!NOTE]
> The section below preserves the initial exploration, gap analysis, and recommendations recorded during the `MS-04.9H.9A` architecture audit prior to implementation.

### Original Scope & Direction
KiemLai sought to evolve Reader delivery from segment-per-file playback to one continuous chapter-level playback artifact per `(chapterId, managedVoiceId)`, while preserving the existing segment-based generation and reconciliation system.

Locked principles established during audit:
- Keep segment generation, segment health, voice revision, reconciliation, retries, and cleanup lifecycle.
- Add a chapter-level delivery layer owned by Novel narration.
- Keep Media Core consumer-neutral.
- Publish chapter playback artifacts atomically after all heavy work and validation completes.
- Do not destructively overwrite generated media.

### Original Component Reuse Map (Historical Audit)

| Component | Historical Recommendation | Final Implementation State (H.9I5E) |
| --- | --- | --- |
| `ChapterNarrationSegment` | Keep | **Retained**: internal text/cue source and generation unit. |
| `ChapterNarrationAudio` | Keep | **Retained**: reusable per-segment, per-voice synthesis cache. |
| `ChapterNarrationManifest` | Keep and reference | **Retained**: source content version and manifest hash validation. |
| `NarrationManifestHasher` | Keep | **Retained**: stable ordered-segment content token. |
| `ManagedVoice` | Keep | **Retained**: public `voiceKey`; internal provider mapping and revision. |
| `ChapterNarrationAudioHealthResolver` | Keep | **Retained**: internal segment health evaluation (`READY`, `OUTDATED`, `MISSING`, `FAILED`). |
| `GenerateChapterNarrationAudioUseCase` | Keep | **Retained**: primitive synthesis operations. |
| `GenerateChapterNarrationUseCase` | Keep | **Retained**: whole-chapter segment readiness orchestrator. |
| `ReaderNarrationContinuationDispatcher` | Migrate | **RETIRED / DELETED**: replaced by `ReaderChapterNarrationPreparationDispatcher`. |
| `BuildReaderNarrationContinuationPlanUseCase` | Reuse / migrate | **RETIRED / DELETED**: legacy segment-continuation pipeline removed in H.9I5D2B. |
| `ExecuteReaderNarrationContinuationUseCase` | Reuse / migrate | **RETIRED / DELETED**: legacy segment-continuation pipeline removed in H.9I5D2B. |
| Public manifest endpoint | Migrate | **RETIRED / DELETED**: replaced by `GET /playback`. |
| Public per-segment prepare endpoint | Retire later | **RETIRED / DELETED**: replaced by `POST /prepare`. |
| `ManagedAudioEngine` | Migrate | **Migrated**: reduced to voice catalog discovery only. |
| `ChapterAudioEngine` | Introduce | **Created**: authoritative Managed playback engine in Reader. |
| `NarrationController` | Migrate | **Migrated**: coordinates voice UI, Follow Mode, Auto Next, and ChapterAudioEngine. |
| Media asset/version model | Keep unchanged | **Retained**: consumer-neutral immutable asset storage. |
| Narration media cleanup tasks | Extend | **Retained**: cleans up superseded chapter playback artifacts post-commit. |

### Atomic Build and Finalization Sequence
1. Resolve `chapterId` and `voiceKey` to internal `managedVoiceId`.
2. Capture snapshot: chapter `contentVersion`, `sourceContentVersion`, `manifestHash`, `synthesisRevision`, ordered segments, and segment audio media binaries.
3. Verify all CURRENT segments are `READY`.
4. Decode segment audio into PCM and assemble continuous PCM timeline with natural boundary pauses.
5. Encode compressed Reader artifact (e.g. MP3/AAC).
6. Upload candidate to Media Core as a new `PUBLIC AUDIO` asset.
7. Execute finalization in one short transaction:
   - Revalidate all snapshot properties;
   - Abort if concurrent edits occurred;
   - Insert immutable `ChapterNarrationPlaybackArtifact`;
   - Insert cue rows;
   - Atomically switch stable `ChapterNarrationPlayback` pointer;
   - Commit.
8. Post-commit: enqueue asynchronous cleanup for superseded previous artifact Media assets.
