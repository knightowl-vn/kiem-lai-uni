package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.ChapterNarrationPlaybackArtifactRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackArtifact;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared, metadata-only exact-current inspection for Admin and the builder preflight. */
@Service
public class InspectChapterNarrationPlaybackUseCase {
    private final ChapterNarrationPlaybackRepositoryPort playbackRepository;
    private final ChapterNarrationPlaybackArtifactRepositoryPort artifactRepository;
    private final ChapterNarrationPlaybackCueRepositoryPort cueRepository;
    private final MediaContract mediaContract;
    private final ResolveChapterNarrationPlaybackBuildSnapshotUseCase snapshotUseCase;

    public InspectChapterNarrationPlaybackUseCase(
            ChapterNarrationPlaybackRepositoryPort playbackRepository,
            ChapterNarrationPlaybackArtifactRepositoryPort artifactRepository,
            ChapterNarrationPlaybackCueRepositoryPort cueRepository,
            MediaContract mediaContract,
            ResolveChapterNarrationPlaybackBuildSnapshotUseCase snapshotUseCase
    ) {
        this.playbackRepository = Objects.requireNonNull(playbackRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.cueRepository = Objects.requireNonNull(cueRepository);
        this.mediaContract = Objects.requireNonNull(mediaContract);
        this.snapshotUseCase = Objects.requireNonNull(snapshotUseCase);
    }

    public Optional<ChapterNarrationPlaybackArtifact> findAlreadyCurrent(ChapterNarrationPlaybackBuildSnapshot snapshot) {
        return findUsableArtifact(snapshot.chapterId(), snapshot.managedVoiceId())
                .filter(artifact -> matches(artifact, snapshot));
    }

    public AdminChapterNarrationPlaybackDTO execute(UUID chapterId, UUID voiceId, long contentVersion, long synthesisRevision) {
        Optional<ChapterNarrationPlaybackArtifact> existing = findUsableArtifact(chapterId, voiceId);
        if (existing.isEmpty()) {
            return AdminChapterNarrationPlaybackDTO.missing();
        }
        ChapterNarrationPlaybackArtifact artifact = existing.get();
        ChapterNarrationPlaybackState state;
        // Preserve H.9F precedence: content changes take priority over voice changes.
        if (artifact.getSourceContentVersion() != contentVersion) {
            state = ChapterNarrationPlaybackState.STALE_CONTENT;
        } else if (artifact.getSynthesisRevision() != synthesisRevision) {
            state = ChapterNarrationPlaybackState.STALE_VOICE;
        } else if (artifact.getSourceFingerprint() == null) {
            state = ChapterNarrationPlaybackState.STALE_SOURCE;
        } else {
            try {
                state = matches(artifact, snapshotUseCase.inspect(chapterId, voiceId))
                        ? ChapterNarrationPlaybackState.CURRENT : ChapterNarrationPlaybackState.STALE_SOURCE;
            } catch (IllegalStateException invalidCurrentSources) {
                // A missing/stale manifest, non-READY assignment, or unavailable source cannot prove currentness.
                state = ChapterNarrationPlaybackState.STALE_SOURCE;
            }
        }
        return new AdminChapterNarrationPlaybackDTO(state, artifact.getDurationMillis(),
                artifact.getCueCount(), artifact.getCreatedAt());
    }

    private Optional<ChapterNarrationPlaybackArtifact> findUsableArtifact(UUID chapterId, UUID voiceId) {
        return playbackRepository.findByChapterIdAndManagedVoiceId(chapterId, voiceId)
                .filter(playback -> chapterId.equals(playback.getChapterId())
                        && voiceId.equals(playback.getManagedVoiceId()) && playback.getCurrentArtifactId() != null)
                .flatMap(playback -> artifactRepository.findById(playback.getCurrentArtifactId())
                        .filter(artifact -> playback.getCurrentArtifactId().equals(artifact.getId())
                                && playback.getId().equals(artifact.getPlaybackId())
                                && chapterId.equals(artifact.getChapterId()) && voiceId.equals(artifact.getManagedVoiceId())))
                .filter(this::hasUsableMetadata);
    }

    private boolean hasUsableMetadata(ChapterNarrationPlaybackArtifact artifact) {
        if (artifact.getDurationMillis() <= 0 || artifact.getCueCount() <= 0
                || !FinalizeChapterNarrationPlaybackUseCase.PLAYBACK_CODEC_MIME_TYPE.equals(artifact.getCodecMimeType())) {
            return false;
        }
        List<ChapterNarrationPlaybackCue> cues = orderedCues(artifact);
        if (cues.size() != artifact.getCueCount()) {
            return false;
        }
        long previousEnd = 0L;
        for (int i = 0; i < cues.size(); i++) {
            ChapterNarrationPlaybackCue cue = cues.get(i);
            if (!artifact.getId().equals(cue.getArtifactId()) || cue.getCueOrdinal() != i
                    || cue.getStartMillis() < previousEnd || cue.getEndMillis() <= cue.getStartMillis()
                    || cue.getEndMillis() > artifact.getDurationMillis()) {
                return false;
            }
            previousEnd = cue.getEndMillis();
        }
        // Same delivery eligibility as H.9F; metadata reads only, never open a binary stream.
        return mediaContract.getAssetDetail(artifact.getMediaAssetId()).filter(detail ->
                artifact.getMediaAssetId().equals(detail.id()) && detail.status() == MediaAssetStatusDTO.ACTIVE
                        && detail.visibility() == MediaVisibilityDTO.PUBLIC && detail.currentVersion() != null
                        && detail.id().equals(detail.currentVersion().assetId())
                        && detail.currentVersionNumber() == detail.currentVersion().versionNumber()).isPresent();
    }

    private boolean matches(ChapterNarrationPlaybackArtifact artifact, ChapterNarrationPlaybackBuildSnapshot snapshot) {
        if (!artifact.getChapterId().equals(snapshot.chapterId())
                || !artifact.getManagedVoiceId().equals(snapshot.managedVoiceId())
                || artifact.getSourceContentVersion() != snapshot.sourceContentVersion()
                || artifact.getSynthesisRevision() != snapshot.synthesisRevision()
                || !artifact.getManifestHash().equals(snapshot.manifestHash())
                || !Objects.equals(artifact.getSourceFingerprint(), ChapterNarrationPlaybackSourceFingerprint.compute(snapshot))) {
            return false;
        }
        List<ChapterNarrationPlaybackCue> cues = orderedCues(artifact);
        if (cues.size() != snapshot.segments().size()) {
            return false;
        }
        for (int i = 0; i < cues.size(); i++) {
            if (!cues.get(i).getSegmentId().equals(snapshot.segments().get(i).segmentId())
                    || cues.get(i).getSegmentIndex() != snapshot.segments().get(i).segmentIndex()) {
                return false;
            }
        }
        return true;
    }

    private List<ChapterNarrationPlaybackCue> orderedCues(ChapterNarrationPlaybackArtifact artifact) {
        return cueRepository.findByArtifactId(artifact.getId()).stream()
                .sorted(Comparator.comparingInt(ChapterNarrationPlaybackCue::getCueOrdinal)).toList();
    }
}
