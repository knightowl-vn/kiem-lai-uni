package com.universe.novel.application.narration;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationManifestDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationSegmentDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationVoiceDTO;
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
 * Public application read use case that builds a playback-safe chapter narration manifest (MS-04.9H.7A).
 */
@Service
@Transactional(readOnly = true)
public class GetPublicChapterNarrationManifestUseCase {

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;

    public GetPublicChapterNarrationManifestUseCase(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort, "readerChapterAccessQueryPort must not be null"
        );
        this.managedVoiceRepositoryPort = Objects.requireNonNull(
                managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null"
        );
        this.segmentRepositoryPort = Objects.requireNonNull(
                segmentRepositoryPort, "segmentRepositoryPort must not be null"
        );
        this.audioRepositoryPort = Objects.requireNonNull(
                audioRepositoryPort, "audioRepositoryPort must not be null"
        );
        this.failureRepositoryPort = Objects.requireNonNull(
                failureRepositoryPort, "failureRepositoryPort must not be null"
        );
    }

    public PublicChapterNarrationManifestDTO execute(GetPublicChapterNarrationManifestQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        return execute(query.chapterId(), query.voiceKey());
    }

    public PublicChapterNarrationManifestDTO execute(UUID chapterId, String requestedVoiceKey) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }

        // 1. Enforce public chapter publication access (chapter PUBLISHED & volume PUBLISHED)
        readerChapterAccessQueryPort.findPublishedById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        // 2. Load and explicitly sort ACTIVE managed voices (displayOrder ASC, createdAt ASC, id ASC)
        List<ManagedVoice> rawActiveVoices = managedVoiceRepositoryPort.findAllActive();
        List<ManagedVoice> activeVoices = rawActiveVoices.stream()
                .sorted(Comparator
                        .comparingInt(ManagedVoice::getDisplayOrder)
                        .thenComparing(ManagedVoice::getCreatedAt)
                        .thenComparing(ManagedVoice::getId))
                .toList();

        // 3. Handle explicit voiceKey validation or no-active-voice empty state
        if (activeVoices.isEmpty()) {
            if (requestedVoiceKey != null && !requestedVoiceKey.isBlank()) {
                String trimmedKey = requestedVoiceKey.trim();
                Optional<ManagedVoice> voiceOpt = managedVoiceRepositoryPort.findByVoiceKey(trimmedKey);
                if (voiceOpt.isEmpty()) {
                    throw new ManagedVoiceNotFoundException(trimmedKey);
                }
                throw new ManagedVoiceInvalidStateException("Giọng đọc không khả dụng: " + trimmedKey);
            }
            return new PublicChapterNarrationManifestDTO(
                    chapterId,
                    Collections.emptyList(),
                    null,
                    Collections.emptyList()
            );
        }

        List<PublicNarrationVoiceDTO> availableVoiceDTOs = activeVoices.stream()
                .map(v -> new PublicNarrationVoiceDTO(v.getVoiceKey(), v.getDisplayName(), v.isDefaultVoice()))
                .toList();

        // 4. Resolve selected voice from explicitly sorted active voices list
        ManagedVoice selectedVoice = resolveSelectedVoice(activeVoices, requestedVoiceKey);

        // 5. Load CURRENT segments (sorted by segmentIndex ASC)
        List<ChapterNarrationSegment> currentSegments = segmentRepositoryPort.findByChapterIdAndStatus(
                chapterId,
                ChapterNarrationSegmentStatus.CURRENT
        ).stream()
                .sorted(Comparator.comparingInt(ChapterNarrationSegment::getSegmentIndex))
                .toList();

        // 6. Batch-load audio assignments and failure diagnostics
        List<PublicNarrationSegmentDTO> segmentDTOs;
        if (!currentSegments.isEmpty()) {
            List<UUID> segmentIds = currentSegments.stream()
                    .map(ChapterNarrationSegment::getId)
                    .toList();

            Map<UUID, ChapterNarrationAudio> audioBySegmentId = audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(
                            segmentIds, selectedVoice.getId()
                    ).stream()
                    .collect(Collectors.toMap(ChapterNarrationAudio::getSegmentId, a -> a, (a1, a2) -> a1));

            Map<UUID, ChapterNarrationAudioFailure> failureBySegmentId = failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(
                            segmentIds, selectedVoice.getId()
                    ).stream()
                    .collect(Collectors.toMap(ChapterNarrationAudioFailure::getSegmentId, f -> f, (f1, f2) -> f1));

            long currentVoiceRevision = selectedVoice.getSynthesisRevision();
            segmentDTOs = new ArrayList<>(currentSegments.size());

            for (ChapterNarrationSegment segment : currentSegments) {
                ChapterNarrationAudio audio = audioBySegmentId.get(segment.getId());

                if (audio != null) {
                    boolean compatible = audio.isCompatibleWith(currentVoiceRevision);
                    String healthStatus = compatible ? "READY" : "OUTDATED";
                    String audioUrl = MediaDeliveryUrlSupport.contentUrl(audio.getMediaAssetId());
                    segmentDTOs.add(new PublicNarrationSegmentDTO(
                            segment.getId(),
                            segment.getSegmentIndex(),
                            healthStatus,
                            true,
                            audioUrl
                    ));
                } else {
                    ChapterNarrationAudioFailure failure = failureBySegmentId.get(segment.getId());
                    String healthStatus = (failure != null) ? "FAILED" : "MISSING";
                    segmentDTOs.add(new PublicNarrationSegmentDTO(
                            segment.getId(),
                            segment.getSegmentIndex(),
                            healthStatus,
                            false,
                            null
                    ));
                }
            }
        } else {
            segmentDTOs = Collections.emptyList();
        }

        PublicNarrationVoiceDTO selectedVoiceDTO = new PublicNarrationVoiceDTO(
                selectedVoice.getVoiceKey(), selectedVoice.getDisplayName(), selectedVoice.isDefaultVoice()
        );

        return new PublicChapterNarrationManifestDTO(
                chapterId,
                availableVoiceDTOs,
                selectedVoiceDTO,
                segmentDTOs
        );
    }

    private ManagedVoice resolveSelectedVoice(List<ManagedVoice> sortedActiveVoices, String requestedVoiceKey) {
        if (requestedVoiceKey != null && !requestedVoiceKey.isBlank()) {
            String trimmedKey = requestedVoiceKey.trim();
            Optional<ManagedVoice> voiceOpt = managedVoiceRepositoryPort.findByVoiceKey(trimmedKey);
            if (voiceOpt.isEmpty()) {
                throw new ManagedVoiceNotFoundException(trimmedKey);
            }
            ManagedVoice voice = voiceOpt.get();
            if (!voice.isActive()) {
                throw new ManagedVoiceInvalidStateException("Giọng đọc không khả dụng: " + trimmedKey);
            }
            return voice;
        }

        // Fallback 1: ACTIVE default voice from sorted list
        Optional<ManagedVoice> defaultVoice = sortedActiveVoices.stream()
                .filter(ManagedVoice::isDefaultVoice)
                .findFirst();
        if (defaultVoice.isPresent()) {
            return defaultVoice.get();
        }

        // Fallback 2: first ACTIVE voice by displayOrder from sorted list
        return sortedActiveVoices.get(0);
    }
}
