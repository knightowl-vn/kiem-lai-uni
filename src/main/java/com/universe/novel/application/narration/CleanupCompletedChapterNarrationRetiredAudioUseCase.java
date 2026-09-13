package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Completion cleanup coordinator for retired narration audio assignments after chapter generation (MS-04.9H.7C1C2A).
 * <p>
 * <strong>Manifest-Stability Invariant:</strong>
 * The chapter narration manifest is captured before readiness evaluation and re-validated after candidate collection.
 * If the manifest is absent, deleted, or changes (due to a concurrent republish), cleanup handoff is skipped and a
 * {@link ChapterNarrationCompletionCleanupStatus#MANIFEST_CHANGED} summary is returned.
 * <p>
 * <strong>Readiness Invariant:</strong>
 * Cleanup is allowed ONLY after a fresh post-generation database read proves that every CURRENT narration segment
 * for the selected {@link ManagedVoice} is {@link ChapterNarrationAudioHealthStatus#READY}.
 * Any {@code MISSING}, {@code FAILED}, or {@code OUTDATED} segment blocks retired audio cleanup.
 * <p>
 * <strong>Same-Voice Invariant:</strong>
 * Cleanup applies strictly to RETIRED audio assignments belonging to the specified {@code managedVoiceId}.
 * Audio assignments for other voices are never touched.
 * <p>
 * <strong>Transaction Boundary:</strong>
 * This coordinator does NOT declare {@code @Transactional}. Each individual handoff is managed within its own short
 * transaction by {@link HandoffRetiredNarrationAudioCleanupUseCase}.
 */
@Service
public class CleanupCompletedChapterNarrationRetiredAudioUseCase {

    private static final Logger log = LoggerFactory.getLogger(CleanupCompletedChapterNarrationRetiredAudioUseCase.class);

    private final ChapterNarrationManifestRepositoryPort manifestRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    private final HandoffRetiredNarrationAudioCleanupUseCase handoffUseCase;

    public CleanupCompletedChapterNarrationRetiredAudioUseCase(
            ChapterNarrationManifestRepositoryPort manifestRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort,
            HandoffRetiredNarrationAudioCleanupUseCase handoffUseCase
    ) {
        this.manifestRepositoryPort = Objects.requireNonNull(manifestRepositoryPort, "manifestRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.failureRepositoryPort = Objects.requireNonNull(failureRepositoryPort, "failureRepositoryPort must not be null");
        this.handoffUseCase = Objects.requireNonNull(handoffUseCase, "handoffUseCase must not be null");
    }

    /**
     * Executes the chapter completion cleanup workflow for the specified command.
     *
     * @param command input command containing chapterId and managedVoiceId
     * @return cleanup summary
     */
    public ChapterNarrationCompletionCleanupSummary execute(CleanupCompletedChapterNarrationRetiredAudioCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.chapterId(), command.managedVoiceId());
    }

    /**
     * Executes the chapter completion cleanup workflow for the given chapter ID and managed voice ID.
     *
     * @param chapterId      the chapter identity
     * @param managedVoiceId the managed voice identity
     * @return cleanup summary
     */
    public ChapterNarrationCompletionCleanupSummary execute(UUID chapterId, UUID managedVoiceId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        // 1. Initial snapshot: load ChapterNarrationManifest
        Optional<ChapterNarrationManifest> initialManifestOpt = manifestRepositoryPort.findByChapterId(chapterId);
        if (initialManifestOpt.isEmpty()) {
            return ChapterNarrationCompletionCleanupSummary.notEligible();
        }
        ChapterNarrationManifest initialManifest = initialManifestOpt.get();
        long initialSourceContentVersion = initialManifest.getSourceContentVersion();
        String initialManifestHash = initialManifest.getManifestHash();

        // 2. Fresh read: load ManagedVoice and current synthesis revision
        Optional<ManagedVoice> voiceOpt = managedVoiceRepositoryPort.findById(managedVoiceId);
        if (voiceOpt.isEmpty()) {
            return ChapterNarrationCompletionCleanupSummary.notEligible();
        }
        ManagedVoice voice = voiceOpt.get();
        if (!voice.isActive()) {
            return ChapterNarrationCompletionCleanupSummary.notEligible();
        }
        long currentVoiceRevision = voice.getSynthesisRevision();

        // 3. Fresh read: load CURRENT narration segments
        List<ChapterNarrationSegment> currentSegments = segmentRepositoryPort.findByChapterIdAndStatus(
                chapterId,
                ChapterNarrationSegmentStatus.CURRENT
        );
        if (currentSegments.isEmpty()) {
            return ChapterNarrationCompletionCleanupSummary.notEligible();
        }

        // 4. Batch-load audio assignments and failure records for CURRENT segments (Zero N+1)
        List<UUID> currentSegmentIds = currentSegments.stream()
                .map(ChapterNarrationSegment::getId)
                .toList();

        Map<UUID, ChapterNarrationAudio> currentAudioBySegmentId = audioRepositoryPort
                .findBySegmentIdInAndManagedVoiceId(currentSegmentIds, managedVoiceId)
                .stream()
                .collect(Collectors.toMap(ChapterNarrationAudio::getSegmentId, a -> a, (a1, a2) -> a1));

        Map<UUID, ChapterNarrationAudioFailure> currentFailureBySegmentId = failureRepositoryPort
                .findBySegmentIdInAndManagedVoiceId(currentSegmentIds, managedVoiceId)
                .stream()
                .collect(Collectors.toMap(ChapterNarrationAudioFailure::getSegmentId, f -> f, (f1, f2) -> f1));

        // 5. Authoritative readiness gate: all CURRENT segments must be READY
        for (ChapterNarrationSegment segment : currentSegments) {
            ChapterNarrationAudio audio = currentAudioBySegmentId.get(segment.getId());
            ChapterNarrationAudioFailure failure = currentFailureBySegmentId.get(segment.getId());

            ChapterNarrationAudioHealthResolution resolution =
                    ChapterNarrationAudioHealthResolver.resolve(audio, failure, currentVoiceRevision);

            if (resolution.status() != ChapterNarrationAudioHealthStatus.READY) {
                return ChapterNarrationCompletionCleanupSummary.notEligible();
            }
        }

        // 6. READY gate passed -> load RETIRED segments for chapter
        List<ChapterNarrationSegment> retiredSegments = segmentRepositoryPort.findByChapterIdAndStatus(
                chapterId,
                ChapterNarrationSegmentStatus.RETIRED
        );
        if (retiredSegments.isEmpty()) {
            return ChapterNarrationCompletionCleanupSummary.clean(true);
        }

        List<UUID> retiredSegmentIds = retiredSegments.stream()
                .map(ChapterNarrationSegment::getId)
                .toList();

        // 7. Batch-load RETIRED audio assignments strictly for the SAME managed voice
        List<ChapterNarrationAudio> retiredAudios = audioRepositoryPort
                .findBySegmentIdInAndManagedVoiceId(retiredSegmentIds, managedVoiceId);
        if (retiredAudios.isEmpty()) {
            return ChapterNarrationCompletionCleanupSummary.clean(true);
        }

        // 8. Deterministic order: segmentIndex ASC, then audio id ASC
        Map<UUID, Integer> segmentIndexById = retiredSegments.stream()
                .collect(Collectors.toMap(
                        ChapterNarrationSegment::getId,
                        ChapterNarrationSegment::getSegmentIndex,
                        (i1, i2) -> i1
                ));

        List<ChapterNarrationAudio> sortedRetiredAudios = retiredAudios.stream()
                .sorted(Comparator
                        .comparingInt((ChapterNarrationAudio a) -> segmentIndexById.getOrDefault(a.getSegmentId(), Integer.MAX_VALUE))
                        .thenComparing(ChapterNarrationAudio::getId))
                .toList();

        int candidateCount = sortedRetiredAudios.size();

        // 9. Re-read ChapterNarrationManifest BEFORE the first handoff call to ensure manifest stability
        Optional<ChapterNarrationManifest> currentManifestOpt = manifestRepositoryPort.findByChapterId(chapterId);
        if (currentManifestOpt.isEmpty()) {
            return ChapterNarrationCompletionCleanupSummary.manifestChanged(candidateCount);
        }
        ChapterNarrationManifest currentManifest = currentManifestOpt.get();
        if (currentManifest.getSourceContentVersion() != initialSourceContentVersion
                || !Objects.equals(currentManifest.getManifestHash(), initialManifestHash)) {
            return ChapterNarrationCompletionCleanupSummary.manifestChanged(candidateCount);
        }

        // 10. Execute atomic handoffs with per-candidate failure isolation
        int handedOffCount = 0;
        int alreadyAbsentCount = 0;
        int skippedNotRetiredCount = 0;
        int skippedSharedMediaReferenceCount = 0;
        int failedCount = 0;

        for (ChapterNarrationAudio audio : sortedRetiredAudios) {
            try {
                HandoffRetiredNarrationAudioCleanupResult result = handoffUseCase.execute(audio.getId());
                switch (result.outcome()) {
                    case HANDED_OFF -> handedOffCount++;
                    case ALREADY_ABSENT -> alreadyAbsentCount++;
                    case SKIPPED_NOT_RETIRED -> skippedNotRetiredCount++;
                    case SKIPPED_SHARED_MEDIA_REFERENCE -> skippedSharedMediaReferenceCount++;
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to hand off retired narration audio cleanup for audio [{}] and segment [{}]: {}",
                        audio.getId(), audio.getSegmentId(), ex.getMessage(), ex);
                failedCount++;
            }
        }

        ChapterNarrationCompletionCleanupStatus status =
                (failedCount == 0 && skippedNotRetiredCount == 0 && skippedSharedMediaReferenceCount == 0)
                        ? ChapterNarrationCompletionCleanupStatus.COMPLETED
                        : ChapterNarrationCompletionCleanupStatus.PARTIAL;

        return new ChapterNarrationCompletionCleanupSummary(
                status,
                true,
                candidateCount,
                handedOffCount,
                alreadyAbsentCount,
                skippedNotRetiredCount,
                skippedSharedMediaReferenceCount,
                failedCount
        );
    }
}
