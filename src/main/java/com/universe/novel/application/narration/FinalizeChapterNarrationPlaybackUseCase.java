package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackArtifactRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackCueRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationPlaybackRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.novel.domain.narration.ChapterNarrationPlayback;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackArtifact;
import com.universe.novel.domain.narration.ChapterNarrationPlaybackCue;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the short, serializable transaction that revalidates and publishes one playback artifact.
 * No Media, binary, encoder, assembler, or cleanup operation is performed here.
 */
@Service
public class FinalizeChapterNarrationPlaybackUseCase {

    static final String PLAYBACK_CODEC_MIME_TYPE = "audio/mpeg";

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ChapterNarrationManifestRepositoryPort manifestRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    private final ChapterNarrationPlaybackRepositoryPort playbackRepositoryPort;
    private final ChapterNarrationPlaybackArtifactRepositoryPort artifactRepositoryPort;
    private final ChapterNarrationPlaybackCueRepositoryPort cueRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public FinalizeChapterNarrationPlaybackUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ChapterNarrationManifestRepositoryPort manifestRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            ChapterNarrationAudioRepositoryPort audioRepositoryPort,
            ChapterNarrationPlaybackRepositoryPort playbackRepositoryPort,
            ChapterNarrationPlaybackArtifactRepositoryPort artifactRepositoryPort,
            ChapterNarrationPlaybackCueRepositoryPort cueRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(chapterRepositoryPort, "chapterRepositoryPort must not be null");
        this.managedVoiceRepositoryPort = Objects.requireNonNull(managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null");
        this.manifestRepositoryPort = Objects.requireNonNull(manifestRepositoryPort, "manifestRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.audioRepositoryPort = Objects.requireNonNull(audioRepositoryPort, "audioRepositoryPort must not be null");
        this.playbackRepositoryPort = Objects.requireNonNull(playbackRepositoryPort, "playbackRepositoryPort must not be null");
        this.artifactRepositoryPort = Objects.requireNonNull(artifactRepositoryPort, "artifactRepositoryPort must not be null");
        this.cueRepositoryPort = Objects.requireNonNull(cueRepositoryPort, "cueRepositoryPort must not be null");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "idGeneratorPort must not be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public FinalizeChapterNarrationPlaybackResult execute(FinalizeChapterNarrationPlaybackCommand command) {
        requireValidCommand(command);
        ChapterNarrationPlaybackBuildSnapshot snapshot = command.snapshot();
        revalidate(snapshot);
        requireValidCueTimeline(command.cues(), snapshot.segments(), command.durationMillis());

        Instant now = clockPort.now();
        Optional<ChapterNarrationPlayback> existingPlayback =
                playbackRepositoryPort.findByChapterIdAndManagedVoiceId(
                        snapshot.chapterId(),
                        snapshot.managedVoiceId()
                );

        ChapterNarrationPlayback playback = existingPlayback.orElseGet(() -> ChapterNarrationPlayback.create(
                idGeneratorPort.generate(),
                snapshot.chapterId(),
                snapshot.managedVoiceId(),
                now
        ));
        if (existingPlayback.isEmpty()) {
            playback = playbackRepositoryPort.save(playback);
        }

        UUID supersededMediaAssetId = resolveCurrentMediaAsset(playback);
        UUID artifactId = idGeneratorPort.generate();
        ChapterNarrationPlaybackArtifact artifact = ChapterNarrationPlaybackArtifact.create(
                artifactId,
                playback,
                snapshot.sourceContentVersion(),
                snapshot.synthesisRevision(),
                snapshot.manifestHash(),
                command.candidateMediaAssetId(),
                command.durationMillis(),
                command.cues().size(),
                PLAYBACK_CODEC_MIME_TYPE,
                now
        );
        ChapterNarrationPlaybackArtifact persistedArtifact = artifactRepositoryPort.insert(artifact);

        List<ChapterNarrationPlaybackCue> cues = command.cues().stream()
                .map(cue -> ChapterNarrationPlaybackCue.create(
                        persistedArtifact.getId(),
                        cue.cueOrdinal(),
                        cue.segmentId(),
                        cue.segmentIndex(),
                        cue.startMillis(),
                        cue.endMillis()
                ))
                .toList();
        cueRepositoryPort.insertAll(cues);

        playback.switchCurrentArtifact(persistedArtifact, now);
        ChapterNarrationPlayback persistedPlayback = playbackRepositoryPort.save(playback);

        return new FinalizeChapterNarrationPlaybackResult(
                persistedPlayback.getId(),
                persistedArtifact.getId(),
                persistedArtifact.getMediaAssetId(),
                supersededMediaAssetId
        );
    }

    private static void requireValidCommand(FinalizeChapterNarrationPlaybackCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.snapshot() == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (command.candidateMediaAssetId() == null) {
            throw new IllegalArgumentException("candidateMediaAssetId must not be null");
        }
        if (command.durationMillis() <= 0) {
            throw new IllegalArgumentException("durationMillis must be positive");
        }
        if (command.cues() == null || command.cues().isEmpty()) {
            throw new IllegalArgumentException("cues must not be empty");
        }
        ChapterNarrationPlaybackBuildSnapshot snapshot = command.snapshot();
        if (snapshot.chapterId() == null || snapshot.managedVoiceId() == null) {
            throw new IllegalArgumentException("snapshot chapter and voice identities must not be null");
        }
        if (snapshot.segments() == null || snapshot.segments().isEmpty()) {
            throw new IllegalArgumentException("snapshot segments must not be empty");
        }
    }

    private void revalidate(ChapterNarrationPlaybackBuildSnapshot snapshot) {
        Chapter chapter = chapterRepositoryPort.findById(snapshot.chapterId())
                .orElseThrow(() -> new ChapterNotFoundException(snapshot.chapterId()));
        if (chapter.getStatus() != ChapterStatus.PUBLISHED
                || chapter.getContentVersion() != snapshot.sourceContentVersion()) {
            throw stale("chapter content or publication state changed");
        }

        ManagedVoice voice = managedVoiceRepositoryPort.findById(snapshot.managedVoiceId())
                .orElseThrow(() -> new ManagedVoiceNotFoundException(snapshot.managedVoiceId()));
        if (!voice.isActive() || voice.getSynthesisRevision() != snapshot.synthesisRevision()) {
            throw stale("managed voice state or synthesis revision changed");
        }

        ChapterNarrationManifest manifest = manifestRepositoryPort.findByChapterId(snapshot.chapterId())
                .orElseThrow(() -> stale("chapter narration manifest disappeared"));
        if (manifest.getSourceContentVersion() != snapshot.sourceContentVersion()
                || !Objects.equals(manifest.getManifestHash(), snapshot.manifestHash())) {
            throw stale("chapter narration manifest changed");
        }

        List<ChapterNarrationSegment> currentSegments = segmentRepositoryPort.findByChapterIdAndStatus(
                        snapshot.chapterId(),
                        ChapterNarrationSegmentStatus.CURRENT
                ).stream()
                .sorted(Comparator.comparingInt(ChapterNarrationSegment::getSegmentIndex))
                .toList();
        if (currentSegments.size() != snapshot.segments().size()) {
            throw stale("CURRENT narration segment set changed");
        }

        for (int i = 0; i < currentSegments.size(); i++) {
            ChapterNarrationSegment current = currentSegments.get(i);
            ChapterNarrationPlaybackSegmentSnapshot captured = snapshot.segments().get(i);
            if (!Objects.equals(current.getId(), captured.segmentId())
                    || current.getSegmentIndex() != captured.segmentIndex()
                    || !Objects.equals(current.getContentHash(), captured.contentHash())) {
                throw stale("CURRENT narration segment identity, order, or content changed");
            }
        }

        List<UUID> segmentIds = currentSegments.stream().map(ChapterNarrationSegment::getId).toList();
        Map<UUID, ChapterNarrationAudio> currentAudio = indexAudioAssignments(
                audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(segmentIds, snapshot.managedVoiceId())
        );
        if (currentAudio.size() != snapshot.segments().size()) {
            throw stale("READY narration audio set changed");
        }

        for (ChapterNarrationPlaybackSegmentSnapshot captured : snapshot.segments()) {
            ChapterNarrationAudio audio = currentAudio.get(captured.segmentId());
            if (audio == null
                    || !Objects.equals(audio.getId(), captured.narrationAudioId())
                    || !Objects.equals(audio.getVersion(), captured.narrationAudioVersion())
                    || !Objects.equals(audio.getManagedVoiceId(), snapshot.managedVoiceId())
                    || !Objects.equals(audio.getMediaAssetId(), captured.mediaAssetId())
                    || audio.getGeneratedSynthesisRevision() != captured.generatedSynthesisRevision()
                    || audio.getGeneratedSynthesisRevision() != snapshot.synthesisRevision()
                    || captured.sourceMediaVersion() == null
                    || !Objects.equals(captured.sourceMediaVersion().assetId(), audio.getMediaAssetId())) {
                throw stale("READY narration audio provenance changed");
            }
        }
    }

    private UUID resolveCurrentMediaAsset(ChapterNarrationPlayback playback) {
        if (playback.getCurrentArtifactId() == null) {
            return null;
        }
        ChapterNarrationPlaybackArtifact currentArtifact = artifactRepositoryPort
                .findById(playback.getCurrentArtifactId())
                .orElseThrow(() -> new IllegalStateException(
                        "Current chapter narration playback artifact not found: " + playback.getCurrentArtifactId()
                ));
        if (!currentArtifact.getPlaybackId().equals(playback.getId())) {
            throw new IllegalStateException("Current chapter narration playback artifact belongs to another playback.");
        }
        return currentArtifact.getMediaAssetId();
    }

    private static Map<UUID, ChapterNarrationAudio> indexAudioAssignments(List<ChapterNarrationAudio> assignments) {
        Map<UUID, ChapterNarrationAudio> indexed = new HashMap<>();
        for (ChapterNarrationAudio audio : assignments) {
            if (indexed.put(audio.getSegmentId(), audio) != null) {
                throw stale("duplicate narration audio assignment returned during revalidation");
            }
        }
        return indexed;
    }

    private static void requireValidCueTimeline(
            List<ChapterAudioAssemblyCue> cues,
            List<ChapterNarrationPlaybackSegmentSnapshot> segments,
            long durationMillis
    ) {
        if (cues.size() != segments.size()) {
            throw new IllegalArgumentException("Assembly cue count must match the captured segment count.");
        }
        long previousEndMillis = 0L;
        for (int i = 0; i < cues.size(); i++) {
            ChapterAudioAssemblyCue cue = cues.get(i);
            ChapterNarrationPlaybackSegmentSnapshot segment = segments.get(i);
            if (cue == null) {
                throw new IllegalArgumentException("Assembly cues must not contain null entries.");
            }
            if (cue.cueOrdinal() != i
                    || !Objects.equals(cue.segmentId(), segment.segmentId())
                    || cue.segmentIndex() != segment.segmentIndex()) {
                throw new IllegalArgumentException("Assembly cues must preserve the captured segment order exactly.");
            }
            if (i > 0 && cue.startMillis() < previousEndMillis) {
                throw new IllegalArgumentException("Assembly cue intervals must not overlap.");
            }
            if (cue.endMillis() > durationMillis) {
                throw new IllegalArgumentException("Assembly cue end must not exceed the artifact duration.");
            }
            previousEndMillis = cue.endMillis();
        }
    }

    private static IllegalStateException stale(String detail) {
        return new IllegalStateException("Chapter narration playback build snapshot is stale: " + detail + '.');
    }
}
