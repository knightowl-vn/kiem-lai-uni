package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetCurrentMetadataDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.application.ports.PlaybackManagedVoiceQueryPort;
import com.universe.novel.application.ports.PlaybackManagedVoiceQueryPort.PlaybackManagedVoice;
import com.universe.novel.application.ports.PublicChapterNarrationPlaybackQueryPort;
import com.universe.novel.application.ports.PublicChapterNarrationPlaybackQueryPort.PublicChapterNarrationPlaybackSnapshot;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackAvailability;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackCueDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackFreshness;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Side-effect-free public Reader query for immutable chapter-level narration playback metadata.
 */
@Service
@Transactional(readOnly = true)
public class GetPublicChapterNarrationPlaybackUseCase {

    private final PlaybackManagedVoiceQueryPort playbackManagedVoiceQueryPort;
    private final PublicChapterNarrationPlaybackQueryPort playbackQueryPort;
    private final ChapterNarrationPlaybackCueRepositoryPort cueRepositoryPort;
    private final MediaContract mediaContract;

    public GetPublicChapterNarrationPlaybackUseCase(
            PlaybackManagedVoiceQueryPort playbackManagedVoiceQueryPort,
            PublicChapterNarrationPlaybackQueryPort playbackQueryPort,
            ChapterNarrationPlaybackCueRepositoryPort cueRepositoryPort,
            MediaContract mediaContract
    ) {
        this.playbackManagedVoiceQueryPort = Objects.requireNonNull(
                playbackManagedVoiceQueryPort, "playbackManagedVoiceQueryPort must not be null"
        );
        this.playbackQueryPort = Objects.requireNonNull(
                playbackQueryPort, "playbackQueryPort must not be null"
        );
        this.cueRepositoryPort = Objects.requireNonNull(
                cueRepositoryPort, "cueRepositoryPort must not be null"
        );
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
    }

    public PublicChapterNarrationPlaybackDTO execute(GetPublicChapterNarrationPlaybackQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        UUID chapterId = query.chapterId();
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }

        String requestedVoiceKey = normalizedVoiceKey(query.voiceKey());
        Optional<PlaybackManagedVoice> selectedVoiceOptional = requestedVoiceKey == null
                ? playbackManagedVoiceQueryPort.findPreferredActiveVoice()
                : playbackManagedVoiceQueryPort.findByVoiceKey(requestedVoiceKey);

        UUID selectedVoiceId = selectedVoiceOptional.map(PlaybackManagedVoice::id).orElse(null);
        PublicChapterNarrationPlaybackSnapshot snapshot = playbackQueryPort
                .findPublishedPlayback(chapterId, selectedVoiceId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        PlaybackManagedVoice selectedVoice = resolveSelectedVoice(
                selectedVoiceOptional,
                requestedVoiceKey
        );
        if (selectedVoice == null) {
            return missing(chapterId, null);
        }

        String voiceKey = selectedVoice.voiceKey();
        if (!isOwnedCurrentArtifact(snapshot, chapterId, selectedVoice.id())
                || !hasValidPlaybackMetadata(snapshot)) {
            return missing(chapterId, voiceKey);
        }

        PublicChapterNarrationPlaybackFreshness freshness = deriveFreshness(snapshot, selectedVoice);
        if (freshness == PublicChapterNarrationPlaybackFreshness.STALE_CONTENT) {
            return staleContent(chapterId, voiceKey, snapshot.artifactId());
        }

        List<ChapterNarrationPlaybackCue> orderedCues = validateAndOrderCues(
                cueRepositoryPort.findByArtifactId(snapshot.artifactId()),
                snapshot
        );
        if (orderedCues == null || !isPubliclyEligibleMedia(snapshot.mediaAssetId())) {
            return missing(chapterId, voiceKey);
        }

        List<PublicChapterNarrationPlaybackCueDTO> publicCues = orderedCues.stream()
                .map(cue -> new PublicChapterNarrationPlaybackCueDTO(
                        cue.getCueOrdinal(),
                        cue.getSegmentId(),
                        cue.getSegmentIndex(),
                        cue.getStartMillis(),
                        cue.getEndMillis()
                ))
                .toList();

        return new PublicChapterNarrationPlaybackDTO(
                chapterId,
                voiceKey,
                PublicChapterNarrationPlaybackAvailability.READY,
                freshness,
                true,
                snapshot.artifactId(),
                MediaDeliveryUrlSupport.contentUrl(snapshot.mediaAssetId()),
                snapshot.codecMimeType(),
                snapshot.durationMillis(),
                publicCues
        );
    }

    private String normalizedVoiceKey(String requestedVoiceKey) {
        return requestedVoiceKey == null || requestedVoiceKey.isBlank()
                ? null
                : requestedVoiceKey.trim();
    }

    private PlaybackManagedVoice resolveSelectedVoice(
            Optional<PlaybackManagedVoice> selectedVoiceOptional,
            String requestedVoiceKey
    ) {
        if (selectedVoiceOptional.isEmpty()) {
            if (requestedVoiceKey != null) {
                throw new ManagedVoiceNotFoundException(requestedVoiceKey);
            }
            return null;
        }

        PlaybackManagedVoice selectedVoice = selectedVoiceOptional.get();
        if (!selectedVoice.isActive()) {
            if (requestedVoiceKey != null) {
                throw new ManagedVoiceInvalidStateException(
                        "Managed voice is not active: " + requestedVoiceKey
                );
            }
            return null;
        }
        return selectedVoice;
    }

    private boolean isOwnedCurrentArtifact(
            PublicChapterNarrationPlaybackSnapshot snapshot,
            UUID chapterId,
            UUID managedVoiceId
    ) {
        return chapterId.equals(snapshot.chapterId())
                && snapshot.playbackId() != null
                && snapshot.currentArtifactId() != null
                && snapshot.currentArtifactId().equals(snapshot.artifactId())
                && snapshot.playbackId().equals(snapshot.artifactPlaybackId())
                && chapterId.equals(snapshot.artifactChapterId())
                && managedVoiceId.equals(snapshot.artifactManagedVoiceId());
    }

    private boolean hasValidPlaybackMetadata(PublicChapterNarrationPlaybackSnapshot snapshot) {
        return snapshot.artifactSourceContentVersion() != null
                && snapshot.artifactSynthesisRevision() != null
                && snapshot.mediaAssetId() != null
                && snapshot.durationMillis() != null
                && snapshot.durationMillis() > 0
                && snapshot.cueCount() != null
                && snapshot.cueCount() >= 0
                && snapshot.codecMimeType() != null
                && !snapshot.codecMimeType().isBlank();
    }

    private List<ChapterNarrationPlaybackCue> validateAndOrderCues(
            List<ChapterNarrationPlaybackCue> cues,
            PublicChapterNarrationPlaybackSnapshot snapshot
    ) {
        if (cues.size() != snapshot.cueCount() || cues.stream().anyMatch(Objects::isNull)) {
            return null;
        }

        List<ChapterNarrationPlaybackCue> orderedCues = new ArrayList<>(cues);
        orderedCues.sort(Comparator.comparingInt(ChapterNarrationPlaybackCue::getCueOrdinal));

        long previousEndMillis = 0L;
        for (int expectedOrdinal = 0; expectedOrdinal < orderedCues.size(); expectedOrdinal++) {
            ChapterNarrationPlaybackCue cue = orderedCues.get(expectedOrdinal);
            if (cue.getCueOrdinal() != expectedOrdinal
                    || !snapshot.artifactId().equals(cue.getArtifactId())
                    || cue.getSegmentId() == null
                    || cue.getSegmentIndex() < 0
                    || cue.getStartMillis() < 0
                    || cue.getEndMillis() <= cue.getStartMillis()
                    || cue.getStartMillis() < previousEndMillis
                    || cue.getEndMillis() > snapshot.durationMillis()) {
                return null;
            }
            previousEndMillis = cue.getEndMillis();
        }

        return List.copyOf(orderedCues);
    }

    private boolean isPubliclyEligibleMedia(UUID mediaAssetId) {
        Optional<MediaAssetCurrentMetadataDTO> metadataOptional =
                mediaContract.getAssetCurrentMetadata(mediaAssetId);
        if (metadataOptional.isEmpty()) {
            return false;
        }

        MediaAssetCurrentMetadataDTO metadata = metadataOptional.get();
        return mediaAssetId.equals(metadata.id())
                && metadata.status() == MediaAssetStatusDTO.ACTIVE
                && metadata.visibility() == MediaVisibilityDTO.PUBLIC;
    }

    private PublicChapterNarrationPlaybackFreshness deriveFreshness(
            PublicChapterNarrationPlaybackSnapshot snapshot,
            PlaybackManagedVoice selectedVoice
    ) {
        if (snapshot.artifactSourceContentVersion() != snapshot.chapterContentVersion()) {
            return PublicChapterNarrationPlaybackFreshness.STALE_CONTENT;
        }
        if (snapshot.artifactSynthesisRevision() != selectedVoice.synthesisRevision()) {
            return PublicChapterNarrationPlaybackFreshness.STALE_VOICE;
        }
        return PublicChapterNarrationPlaybackFreshness.CURRENT;
    }

    private PublicChapterNarrationPlaybackDTO staleContent(
            UUID chapterId,
            String voiceKey,
            UUID artifactId
    ) {
        return new PublicChapterNarrationPlaybackDTO(
                chapterId,
                voiceKey,
                PublicChapterNarrationPlaybackAvailability.READY,
                PublicChapterNarrationPlaybackFreshness.STALE_CONTENT,
                false,
                artifactId,
                null,
                null,
                null,
                List.of()
        );
    }

    private PublicChapterNarrationPlaybackDTO missing(UUID chapterId, String voiceKey) {
        return new PublicChapterNarrationPlaybackDTO(
                chapterId,
                voiceKey,
                PublicChapterNarrationPlaybackAvailability.MISSING,
                null,
                false,
                null,
                null,
                null,
                null,
                List.of()
        );
    }
}
