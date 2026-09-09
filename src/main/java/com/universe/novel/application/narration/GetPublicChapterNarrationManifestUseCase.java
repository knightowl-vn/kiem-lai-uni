package com.universe.novel.application.narration;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
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

        PublicNarrationVoiceResolver.Resolution voiceResolution = PublicNarrationVoiceResolver.resolve(
                managedVoiceRepositoryPort,
                requestedVoiceKey
        );
        List<ManagedVoice> activeVoices = voiceResolution.activeVoices();
        ManagedVoice selectedVoice = voiceResolution.selectedVoice();

        // 2. Preserve the established no-active-voice empty state.
        if (selectedVoice == null) {
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

        // 3. Load CURRENT segments (sorted by segmentIndex ASC)
        List<ChapterNarrationSegment> currentSegments = segmentRepositoryPort.findByChapterIdAndStatus(
                chapterId,
                ChapterNarrationSegmentStatus.CURRENT
        ).stream()
                .sorted(Comparator.comparingInt(ChapterNarrationSegment::getSegmentIndex))
                .toList();

        // 4. Batch-load audio assignments and failure diagnostics
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
                ChapterNarrationAudioFailure failure = failureBySegmentId.get(segment.getId());

                ChapterNarrationAudioHealthResolution resolution =
                        ChapterNarrationAudioHealthResolver.resolve(audio, failure, currentVoiceRevision);

                boolean isPlayable = resolution.isPlayable();
                String audioUrl = (audio != null && isPlayable)
                        ? MediaDeliveryUrlSupport.contentUrl(audio.getMediaAssetId())
                        : null;

                segmentDTOs.add(new PublicNarrationSegmentDTO(
                        segment.getId(),
                        segment.getSegmentIndex(),
                        resolution.status().name(),
                        isPlayable,
                        audioUrl
                ));
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

}
