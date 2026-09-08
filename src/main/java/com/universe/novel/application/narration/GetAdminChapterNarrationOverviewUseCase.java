package com.universe.novel.application.narration;

import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.application.voice.dto.ManagedVoiceDTOMapper;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Page-oriented read use case that loads the admin narration overview for a chapter,
 * including managed voice selection, CURRENT segments sorted by segmentIndex, and dynamic audio health.
 */
@Service
@Transactional(readOnly = true)
public class GetAdminChapterNarrationOverviewUseCase {

    private final GetChapterDetailUseCase getChapterDetailUseCase;
    private final GetVolumeDetailUseCase getVolumeDetailUseCase;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;

    public GetAdminChapterNarrationOverviewUseCase(
            GetChapterDetailUseCase getChapterDetailUseCase,
            GetVolumeDetailUseCase getVolumeDetailUseCase,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort
    ) {
        this.getChapterDetailUseCase = Objects.requireNonNull(getChapterDetailUseCase, "getChapterDetailUseCase must not be null");
        this.getVolumeDetailUseCase = Objects.requireNonNull(getVolumeDetailUseCase, "getVolumeDetailUseCase must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.failureRepositoryPort = Objects.requireNonNull(failureRepositoryPort, "failureRepositoryPort must not be null");
    }

    public GetAdminChapterNarrationOverviewResult execute(GetAdminChapterNarrationOverviewQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        return execute(query.chapterId(), query.voiceId());
    }

    public GetAdminChapterNarrationOverviewResult execute(UUID chapterId, UUID requestedVoiceId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }

        // 1. Load and validate chapter & volume
        ChapterDTO chapter = getChapterDetailUseCase.execute(chapterId);
        VolumeDTO volume = getVolumeDetailUseCase.execute(chapter.volumeId());

        // 2. Load all managed voices (sorted by displayOrder ASC)
        List<ManagedVoice> allVoices = managedVoiceRepositoryPort.findAll().stream()
                .sorted(Comparator.comparingInt(ManagedVoice::getDisplayOrder))
                .toList();

        List<ManagedVoiceDTO> voiceDTOs = allVoices.stream()
                .map(ManagedVoiceDTOMapper::toDTO)
                .toList();

        // 3. Resolve selected voice with fallback hierarchy
        ManagedVoice selectedVoice = resolveSelectedVoice(allVoices, requestedVoiceId);
        ManagedVoiceDTO selectedVoiceDTO = selectedVoice != null ? ManagedVoiceDTOMapper.toDTO(selectedVoice) : null;

        // 4. Load CURRENT narration segments (guarantee deterministic sort by segmentIndex ASC)
        List<ChapterNarrationSegment> currentSegments = segmentRepositoryPort.findByChapterIdAndStatus(
                chapterId,
                ChapterNarrationSegmentStatus.CURRENT
        ).stream()
                .sorted(Comparator.comparingInt(ChapterNarrationSegment::getSegmentIndex))
                .toList();

        // 5. Construct segment view DTOs and aggregate counts using batch loading
        List<AdminChapterNarrationSegmentViewDTO> segmentViews;
        int readyCount = 0;
        int outdatedCount = 0;
        int missingCount = 0;
        int failedCount = 0;

        if (selectedVoice != null) {
            List<UUID> segmentIds = currentSegments.stream()
                    .map(ChapterNarrationSegment::getId)
                    .toList();

            Map<UUID, ChapterNarrationAudio> audioBySegmentId = segmentIds.isEmpty()
                    ? Collections.emptyMap()
                    : audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, selectedVoice.getId())
                            .stream()
                            .collect(Collectors.toMap(ChapterNarrationAudio::getSegmentId, a -> a, (a1, a2) -> a1));

            Map<UUID, ChapterNarrationAudioFailure> failureBySegmentId = segmentIds.isEmpty()
                    ? Collections.emptyMap()
                    : failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, selectedVoice.getId())
                            .stream()
                            .collect(Collectors.toMap(ChapterNarrationAudioFailure::getSegmentId, f -> f, (f1, f2) -> f1));

            long currentVoiceRevision = selectedVoice.getSynthesisRevision();
            segmentViews = new ArrayList<>(currentSegments.size());

            for (ChapterNarrationSegment segment : currentSegments) {
                ChapterNarrationAudio audio = audioBySegmentId.get(segment.getId());
                ChapterNarrationAudioFailure failure = failureBySegmentId.get(segment.getId());

                ChapterNarrationAudioHealthResolution resolution =
                        ChapterNarrationAudioHealthResolver.resolve(audio, failure, currentVoiceRevision);

                ChapterNarrationAudioHealthStatus healthStatus = resolution.status();
                switch (healthStatus) {
                    case READY -> readyCount++;
                    case OUTDATED -> outdatedCount++;
                    case FAILED -> failedCount++;
                    case MISSING -> missingCount++;
                }

                NarrationAudioFailureDiagnosticsDTO failureDto = resolution.relevantFailure() != null
                        ? new NarrationAudioFailureDiagnosticsDTO(
                                resolution.relevantFailure().getOperation(),
                                resolution.relevantFailure().getStage(),
                                resolution.relevantFailure().getAttemptedSynthesisRevision(),
                                resolution.relevantFailure().getFailureCount(),
                                resolution.relevantFailure().getErrorType(),
                                resolution.relevantFailure().getErrorMessage(),
                                resolution.relevantFailure().getFirstFailedAt(),
                                resolution.relevantFailure().getLastFailedAt()
                        )
                        : null;

                segmentViews.add(new AdminChapterNarrationSegmentViewDTO(
                        segment.getId(),
                        segment.getSegmentIndex(),
                        segment.getText(),
                        segment.getCharacterCount(),
                        healthStatus,
                        audio != null ? audio.getId() : null,
                        audio != null ? audio.getMediaAssetId() : null,
                        audio != null ? audio.getGeneratedSynthesisRevision() : null,
                        currentVoiceRevision,
                        failureDto
                ));
            }
        } else {
            // No managed voice exists: zero per-voice health counters, no invented health
            segmentViews = currentSegments.stream()
                    .map(segment -> new AdminChapterNarrationSegmentViewDTO(
                            segment.getId(),
                            segment.getSegmentIndex(),
                            segment.getText(),
                            segment.getCharacterCount(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    ))
                    .toList();
        }

        // 6. Load RETIRED narration segments and calculate obsolete retired audio diagnostics
        List<ChapterNarrationSegment> retiredSegments = segmentRepositoryPort.findByChapterIdAndStatus(
                chapterId,
                ChapterNarrationSegmentStatus.RETIRED
        );

        int retiredSegmentCount = retiredSegments.size();
        int obsoleteRetiredSegmentCount = 0;
        int obsoleteRetiredAudioCount = 0;
        boolean contentChangeWarning = false;

        if (!retiredSegments.isEmpty()) {
            List<UUID> retiredSegmentIds = retiredSegments.stream()
                    .map(ChapterNarrationSegment::getId)
                    .filter(Objects::nonNull)
                    .toList();

            if (!retiredSegmentIds.isEmpty()) {
                List<ChapterNarrationAudio> retiredAudios = audioRepositoryPort.findBySegmentIdIn(retiredSegmentIds);
                obsoleteRetiredAudioCount = retiredAudios.size();
                obsoleteRetiredSegmentCount = (int) retiredAudios.stream()
                        .map(ChapterNarrationAudio::getSegmentId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count();
                contentChangeWarning = obsoleteRetiredAudioCount > 0;
            }
        }

        int currentGenerationRequiredCount = outdatedCount + missingCount + failedCount;

        return new GetAdminChapterNarrationOverviewResult(
                chapter,
                volume,
                voiceDTOs,
                selectedVoiceDTO,
                segmentViews,
                currentSegments.size(),
                readyCount,
                outdatedCount,
                missingCount,
                failedCount,
                currentGenerationRequiredCount,
                retiredSegmentCount,
                obsoleteRetiredSegmentCount,
                obsoleteRetiredAudioCount,
                contentChangeWarning
        );
    }

    private ManagedVoice resolveSelectedVoice(List<ManagedVoice> allVoices, UUID requestedVoiceId) {
        if (requestedVoiceId != null) {
            return allVoices.stream()
                    .filter(v -> v.getId().equals(requestedVoiceId))
                    .findFirst()
                    .orElseThrow(() -> new ManagedVoiceNotFoundException(requestedVoiceId));
        }

        // Fallback 1: ACTIVE default voice
        Optional<ManagedVoice> activeDefault = allVoices.stream()
                .filter(v -> v.isActive() && v.isDefaultVoice())
                .findFirst();
        if (activeDefault.isPresent()) {
            return activeDefault.get();
        }

        // Fallback 2: first ACTIVE voice by displayOrder
        Optional<ManagedVoice> firstActive = allVoices.stream()
                .filter(ManagedVoice::isActive)
                .findFirst();
        if (firstActive.isPresent()) {
            return firstActive.get();
        }

        // Fallback 3: first managed voice by displayOrder
        if (!allVoices.isEmpty()) {
            return allVoices.get(0);
        }

        // Fallback 4: none if no managed voices exist
        return null;
    }
}
