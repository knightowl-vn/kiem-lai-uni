package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.NarrationManifestHasher;
import com.universe.novel.domain.narration.NarrationTextSegment;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves the immutable Novel and Media provenance used by a chapter playback build.
 * This service intentionally has no Novel database transaction; the build coordinator
 * invokes it while its transaction propagation is suspended.
 */
@Service
public class ResolveChapterNarrationPlaybackBuildSnapshotUseCase {

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationManifestRepositoryPort manifestRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final MediaContract mediaContract;

    public ResolveChapterNarrationPlaybackBuildSnapshotUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationManifestRepositoryPort manifestRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            MediaContract mediaContract
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(chapterRepositoryPort, "chapterRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.manifestRepositoryPort = Objects.requireNonNull(manifestRepositoryPort, "manifestRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
    }

    public ChapterNarrationPlaybackBuildSnapshot execute(UUID chapterId, UUID managedVoiceId) {
        return resolve(chapterId, managedVoiceId, true);
    }

    /** Read-only metadata inspection also supports draft chapters and disabled voices. */
    public ChapterNarrationPlaybackBuildSnapshot inspect(UUID chapterId, UUID managedVoiceId) {
        return resolve(chapterId, managedVoiceId, false);
    }

    private ChapterNarrationPlaybackBuildSnapshot resolve(UUID chapterId, UUID managedVoiceId, boolean requireBuildEligibility) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        Chapter chapter = chapterRepositoryPort.findById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));
        if (requireBuildEligibility && chapter.getStatus() != ChapterStatus.PUBLISHED) {
            throw new IllegalStateException("Chapter must be PUBLISHED before narration playback can be built.");
        }

        ManagedVoice voice = managedVoiceRepositoryPort.findById(managedVoiceId)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(managedVoiceId));
        if (requireBuildEligibility && !voice.isActive()) {
            throw new ManagedVoiceInvalidStateException("Managed voice must be ACTIVE before narration playback can be built.");
        }

        ChapterNarrationManifest manifest = manifestRepositoryPort.findByChapterId(chapterId)
                .orElseThrow(() -> new IllegalStateException(
                        "Chapter narration manifest not found for chapter: " + chapterId
                ));
        if (manifest.getSourceContentVersion() != chapter.getContentVersion()) {
            throw new IllegalStateException("Chapter narration manifest is stale for chapter: " + chapterId);
        }

        List<ChapterNarrationSegment> segments = segmentRepositoryPort.findByChapterIdAndStatus(
                        chapterId,
                        ChapterNarrationSegmentStatus.CURRENT
                ).stream()
                .sorted(Comparator.comparingInt(ChapterNarrationSegment::getSegmentIndex))
                .toList();
        if (segments.isEmpty()) {
            throw new IllegalStateException("Chapter narration playback requires at least one CURRENT segment.");
        }

        requireManifestMatchesSegments(manifest, segments);

        List<UUID> segmentIds = segments.stream().map(ChapterNarrationSegment::getId).toList();
        Map<UUID, ChapterNarrationAudio> audioBySegmentId = indexAudioAssignments(
                audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, managedVoiceId),
                managedVoiceId
        );

        long synthesisRevision = voice.getSynthesisRevision();
        for (ChapterNarrationSegment segment : segments) {
            ChapterNarrationAudio audio = audioBySegmentId.get(segment.getId());
            if (audio == null || !audio.isCompatibleWith(synthesisRevision)) {
                throw new IllegalStateException(
                        "Every CURRENT narration segment must have READY audio; segment is not READY: " + segment.getId()
                );
            }
        }

        List<ChapterNarrationPlaybackSegmentSnapshot> sourceSnapshots = segments.stream()
                .map(segment -> captureSourceSnapshot(segment, audioBySegmentId.get(segment.getId())))
                .toList();

        return new ChapterNarrationPlaybackBuildSnapshot(
                chapterId,
                managedVoiceId,
                chapter.getContentVersion(),
                synthesisRevision,
                manifest.getManifestHash(),
                sourceSnapshots
        );
    }

    private static void requireManifestMatchesSegments(
            ChapterNarrationManifest manifest,
            List<ChapterNarrationSegment> segments
    ) {
        List<NarrationTextSegment> manifestSegments = segments.stream()
                .map(segment -> new NarrationTextSegment(
                        segment.getSegmentIndex(),
                        segment.getText(),
                        segment.getCharacterCount(),
                        segment.getContentHash()
                ))
                .toList();
        String currentManifestHash = NarrationManifestHasher.computeManifestHash(manifestSegments);
        if (!Objects.equals(currentManifestHash, manifest.getManifestHash())) {
            throw new IllegalStateException("Persisted narration manifest does not match the CURRENT segment set.");
        }
    }

    private static Map<UUID, ChapterNarrationAudio> indexAudioAssignments(
            List<ChapterNarrationAudio> assignments,
            UUID managedVoiceId
    ) {
        Map<UUID, ChapterNarrationAudio> indexed = new HashMap<>();
        for (ChapterNarrationAudio audio : assignments) {
            if (!managedVoiceId.equals(audio.getManagedVoiceId())) {
                throw new IllegalStateException("Narration audio query returned an assignment for a different voice.");
            }
            if (indexed.put(audio.getSegmentId(), audio) != null) {
                throw new IllegalStateException("Duplicate narration audio assignment for segment: " + audio.getSegmentId());
            }
        }
        return indexed;
    }

    private ChapterNarrationPlaybackSegmentSnapshot captureSourceSnapshot(
            ChapterNarrationSegment segment,
            ChapterNarrationAudio audio
    ) {
        MediaAssetVersionSnapshotDTO mediaVersion = mediaContract.getCurrentVersionSnapshot(audio.getMediaAssetId())
                .orElseThrow(() -> new IllegalStateException(
                        "Narration source Media asset is unavailable: " + audio.getMediaAssetId()
                ));
        requireValidMediaSnapshot(audio.getMediaAssetId(), mediaVersion);

        return new ChapterNarrationPlaybackSegmentSnapshot(
                segment.getId(),
                segment.getSegmentIndex(),
                segment.getContentHash(),
                audio.getId(),
                audio.getVersion(),
                audio.getMediaAssetId(),
                audio.getGeneratedSynthesisRevision(),
                mediaVersion
        );
    }

    private static void requireValidMediaSnapshot(UUID expectedAssetId, MediaAssetVersionSnapshotDTO mediaVersion) {
        if (mediaVersion == null
                || !expectedAssetId.equals(mediaVersion.assetId())
                || mediaVersion.versionNumber() < 1
                || mediaVersion.contentHash() == null
                || mediaVersion.contentHash().isBlank()
                || mediaVersion.mimeType() == null
                || mediaVersion.mimeType().isBlank()
                || mediaVersion.sizeBytes() <= 0) {
            throw new IllegalStateException("Media returned invalid immutable source provenance for asset: " + expectedAssetId);
        }
    }
}
