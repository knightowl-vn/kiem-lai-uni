package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationPlaybackArtifactRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableNarrationChapterReference;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackAvailability;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackCueDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackDTO;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationPlaybackFreshness;
import com.universe.novel.domain.narration.ChapterNarrationPlayback;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackArtifact;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
import com.universe.novel.domain.narration.ManagedVoice;
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

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationPlaybackRepositoryPort playbackRepositoryPort;
    private final ChapterNarrationPlaybackArtifactRepositoryPort artifactRepositoryPort;
    private final ChapterNarrationPlaybackCueRepositoryPort cueRepositoryPort;
    private final MediaContract mediaContract;

    public GetPublicChapterNarrationPlaybackUseCase(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationPlaybackRepositoryPort playbackRepositoryPort,
            ChapterNarrationPlaybackArtifactRepositoryPort artifactRepositoryPort,
            ChapterNarrationPlaybackCueRepositoryPort cueRepositoryPort,
            MediaContract mediaContract
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort, "readerChapterAccessQueryPort must not be null"
        );
        this.managedVoiceRepositoryPort = Objects.requireNonNull(
                managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null"
        );
        this.playbackRepositoryPort = Objects.requireNonNull(
                playbackRepositoryPort, "playbackRepositoryPort must not be null"
        );
        this.artifactRepositoryPort = Objects.requireNonNull(
                artifactRepositoryPort, "artifactRepositoryPort must not be null"
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

        ReadableNarrationChapterReference chapter = readerChapterAccessQueryPort
                .findPublishedNarrationById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        PublicNarrationVoiceResolver.Resolution voiceResolution = PublicNarrationVoiceResolver.resolve(
                managedVoiceRepositoryPort,
                query.voiceKey()
        );
        ManagedVoice selectedVoice = voiceResolution.selectedVoice();
        if (selectedVoice == null) {
            return missing(chapterId, null);
        }

        String voiceKey = selectedVoice.getVoiceKey();
        Optional<ChapterNarrationPlayback> playbackOptional = playbackRepositoryPort
                .findByChapterIdAndManagedVoiceId(chapterId, selectedVoice.getId());
        if (playbackOptional.isEmpty()) {
            return missing(chapterId, voiceKey);
        }

        ChapterNarrationPlayback playback = playbackOptional.get();
        if (!chapterId.equals(playback.getChapterId())
                || !selectedVoice.getId().equals(playback.getManagedVoiceId())
                || playback.getCurrentArtifactId() == null) {
            return missing(chapterId, voiceKey);
        }

        UUID currentArtifactId = playback.getCurrentArtifactId();
        Optional<ChapterNarrationPlaybackArtifact> artifactOptional = artifactRepositoryPort.findById(currentArtifactId);
        if (artifactOptional.isEmpty()) {
            return missing(chapterId, voiceKey);
        }

        ChapterNarrationPlaybackArtifact artifact = artifactOptional.get();
        if (!isOwnedCurrentArtifact(artifact, playback, chapterId, selectedVoice.getId(), currentArtifactId)
                || !hasValidPlaybackMetadata(artifact)) {
            return missing(chapterId, voiceKey);
        }

        List<ChapterNarrationPlaybackCue> orderedCues = validateAndOrderCues(
                cueRepositoryPort.findByArtifactId(artifact.getId()),
                artifact
        );
        if (orderedCues == null || !isPubliclyEligibleMedia(artifact.getMediaAssetId())) {
            return missing(chapterId, voiceKey);
        }

        PublicChapterNarrationPlaybackFreshness freshness = deriveFreshness(
                artifact,
                chapter.contentVersion(),
                selectedVoice.getSynthesisRevision()
        );

        if (freshness == PublicChapterNarrationPlaybackFreshness.STALE_CONTENT) {
            return new PublicChapterNarrationPlaybackDTO(
                    chapterId,
                    voiceKey,
                    PublicChapterNarrationPlaybackAvailability.READY,
                    freshness,
                    false,
                    artifact.getId(),
                    null,
                    null,
                    null,
                    List.of()
            );
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
                artifact.getId(),
                MediaDeliveryUrlSupport.contentUrl(artifact.getMediaAssetId()),
                artifact.getCodecMimeType(),
                artifact.getDurationMillis(),
                publicCues
        );
    }

    private boolean isOwnedCurrentArtifact(
            ChapterNarrationPlaybackArtifact artifact,
            ChapterNarrationPlayback playback,
            UUID chapterId,
            UUID managedVoiceId,
            UUID currentArtifactId
    ) {
        return currentArtifactId.equals(artifact.getId())
                && playback.getId().equals(artifact.getPlaybackId())
                && chapterId.equals(artifact.getChapterId())
                && managedVoiceId.equals(artifact.getManagedVoiceId());
    }

    private boolean hasValidPlaybackMetadata(ChapterNarrationPlaybackArtifact artifact) {
        return artifact.getDurationMillis() > 0
                && artifact.getCueCount() >= 0
                && artifact.getCodecMimeType() != null
                && !artifact.getCodecMimeType().isBlank();
    }

    private List<ChapterNarrationPlaybackCue> validateAndOrderCues(
            List<ChapterNarrationPlaybackCue> cues,
            ChapterNarrationPlaybackArtifact artifact
    ) {
        if (cues.size() != artifact.getCueCount() || cues.stream().anyMatch(Objects::isNull)) {
            return null;
        }

        List<ChapterNarrationPlaybackCue> orderedCues = new ArrayList<>(cues);
        orderedCues.sort(Comparator.comparingInt(ChapterNarrationPlaybackCue::getCueOrdinal));

        long previousEndMillis = 0L;
        for (int expectedOrdinal = 0; expectedOrdinal < orderedCues.size(); expectedOrdinal++) {
            ChapterNarrationPlaybackCue cue = orderedCues.get(expectedOrdinal);
            if (cue.getCueOrdinal() != expectedOrdinal
                    || !artifact.getId().equals(cue.getArtifactId())
                    || cue.getSegmentId() == null
                    || cue.getSegmentIndex() < 0
                    || cue.getStartMillis() < 0
                    || cue.getEndMillis() <= cue.getStartMillis()
                    || cue.getStartMillis() < previousEndMillis
                    || cue.getEndMillis() > artifact.getDurationMillis()) {
                return null;
            }
            previousEndMillis = cue.getEndMillis();
        }

        return List.copyOf(orderedCues);
    }

    private boolean isPubliclyEligibleMedia(UUID mediaAssetId) {
        Optional<MediaAssetDetailDTO> detailOptional = mediaContract.getAssetDetail(mediaAssetId);
        if (detailOptional.isEmpty()) {
            return false;
        }

        MediaAssetDetailDTO detail = detailOptional.get();
        MediaVersionDTO currentVersion = detail.currentVersion();
        return mediaAssetId.equals(detail.id())
                && detail.status() == MediaAssetStatusDTO.ACTIVE
                && detail.visibility() == MediaVisibilityDTO.PUBLIC
                && currentVersion != null
                && mediaAssetId.equals(currentVersion.assetId())
                && currentVersion.versionNumber() == detail.currentVersionNumber();
    }

    private PublicChapterNarrationPlaybackFreshness deriveFreshness(
            ChapterNarrationPlaybackArtifact artifact,
            long currentContentVersion,
            long currentSynthesisRevision
    ) {
        if (artifact.getSourceContentVersion() != currentContentVersion) {
            return PublicChapterNarrationPlaybackFreshness.STALE_CONTENT;
        }
        if (artifact.getSynthesisRevision() != currentSynthesisRevision) {
            return PublicChapterNarrationPlaybackFreshness.STALE_VOICE;
        }
        return PublicChapterNarrationPlaybackFreshness.CURRENT;
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
