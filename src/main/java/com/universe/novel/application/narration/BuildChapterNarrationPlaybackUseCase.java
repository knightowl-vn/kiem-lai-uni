package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetVersionContentDTO;
import com.universe.media.contracts.dto.MediaAssetVersionReferenceDTO;
import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.ChapterAudioAssemblerPort;
import com.universe.novel.application.ports.ChapterAudioEncoderPort;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds and publishes one chapter-level narration playback artifact.
 * Heavy Media, streaming, assembly, encoding, and upload work is performed with
 * transaction propagation suspended. Only the delegated finalizer opens a short transaction.
 */
@Service
public class BuildChapterNarrationPlaybackUseCase {

    private static final Logger log = LoggerFactory.getLogger(BuildChapterNarrationPlaybackUseCase.class);

    private final ResolveChapterNarrationPlaybackBuildSnapshotUseCase snapshotUseCase;
    private final MediaContract mediaContract;
    private final ChapterAudioAssemblerPort assemblerPort;
    private final ChapterAudioEncoderPort encoderPort;
    private final UploadChapterNarrationPlaybackMediaUseCase uploadMediaUseCase;
    private final FinalizeChapterNarrationPlaybackUseCase finalizerUseCase;
    private final RequestNarrationMediaCleanupUseCase cleanupUseCase;

    public BuildChapterNarrationPlaybackUseCase(
            ResolveChapterNarrationPlaybackBuildSnapshotUseCase snapshotUseCase,
            MediaContract mediaContract,
            ChapterAudioAssemblerPort assemblerPort,
            ChapterAudioEncoderPort encoderPort,
            UploadChapterNarrationPlaybackMediaUseCase uploadMediaUseCase,
            FinalizeChapterNarrationPlaybackUseCase finalizerUseCase,
            RequestNarrationMediaCleanupUseCase cleanupUseCase
    ) {
        this.snapshotUseCase = Objects.requireNonNull(snapshotUseCase, "snapshotUseCase must not be null");
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
        this.assemblerPort = Objects.requireNonNull(assemblerPort, "assemblerPort must not be null");
        this.encoderPort = Objects.requireNonNull(encoderPort, "encoderPort must not be null");
        this.uploadMediaUseCase = Objects.requireNonNull(uploadMediaUseCase, "uploadMediaUseCase must not be null");
        this.finalizerUseCase = Objects.requireNonNull(finalizerUseCase, "finalizerUseCase must not be null");
        this.cleanupUseCase = Objects.requireNonNull(cleanupUseCase, "cleanupUseCase must not be null");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BuildChapterNarrationPlaybackResult execute(BuildChapterNarrationPlaybackCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.chapterId() == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (command.managedVoiceId() == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        ChapterNarrationPlaybackBuildSnapshot snapshot = snapshotUseCase.execute(
                command.chapterId(),
                command.managedVoiceId()
        );

        UUID candidateMediaAssetId = null;
        long durationMillis;
        List<ChapterAudioAssemblyCue> cues;

        try (OpenedExactSources sources = openExactSources(snapshot);
             ChapterAudioAssemblyResult assembly = assemblerPort.assemble(sources.toAssemblyRequest());
             ChapterAudioEncodingResult encoding = encoderPort.encode(
                     new ChapterAudioEncodingRequest(assembly.resource())
             )) {
            durationMillis = assembly.durationMillis();
            cues = assembly.cues();
            candidateMediaAssetId = uploadMediaUseCase.execute(
                    new UploadChapterNarrationPlaybackMediaCommand(
                            encoding.resource(),
                            originalFilename(snapshot.chapterId(), snapshot.managedVoiceId())
                    )
            ).mediaAssetId();
        } catch (RuntimeException | Error buildFailure) {
            if (candidateMediaAssetId != null) {
                requestCandidateCleanupPreservingFailure(candidateMediaAssetId, buildFailure);
            }
            throw buildFailure;
        }

        FinalizeChapterNarrationPlaybackResult finalized;
        try {
            finalized = finalizerUseCase.execute(new FinalizeChapterNarrationPlaybackCommand(
                    snapshot,
                    candidateMediaAssetId,
                    durationMillis,
                    cues
            ));
        } catch (RuntimeException | Error finalizationFailure) {
            requestCandidateCleanupPreservingFailure(candidateMediaAssetId, finalizationFailure);
            throw finalizationFailure;
        }

        UUID supersededMediaAssetId = finalized.supersededMediaAssetId();
        if (supersededMediaAssetId != null && !supersededMediaAssetId.equals(candidateMediaAssetId)) {
            requestSupersededCleanupAfterCommit(finalized, supersededMediaAssetId);
        }

        return new BuildChapterNarrationPlaybackResult(
                finalized.playbackId(),
                finalized.artifactId(),
                finalized.mediaAssetId()
        );
    }

    private OpenedExactSources openExactSources(ChapterNarrationPlaybackBuildSnapshot snapshot) {
        OpenedExactSources openedSources = new OpenedExactSources();
        try {
            for (ChapterNarrationPlaybackSegmentSnapshot segment : snapshot.segments()) {
                MediaAssetVersionSnapshotDTO captured = segment.sourceMediaVersion();
                MediaAssetVersionReferenceDTO reference = new MediaAssetVersionReferenceDTO(
                        captured.assetId(),
                        captured.versionNumber(),
                        captured.contentHash()
                );
                MediaAssetVersionContentDTO content = mediaContract.openVersionContent(reference);
                OwnedExactInputStream ownedStream = openedSources.register(requireContentStream(content));
                requireExactContent(captured, content);
                openedSources.addSource(new ChapterAudioSegmentSource(
                        segment.segmentId(),
                        segment.segmentIndex(),
                        content.mimeType(),
                        ownedStream::openOnce
                ));
            }
            return openedSources;
        } catch (RuntimeException | Error primaryFailure) {
            openedSources.closePreserving(primaryFailure);
            throw primaryFailure;
        }
    }

    private static InputStream requireContentStream(MediaAssetVersionContentDTO content) {
        if (content == null) {
            throw new IllegalStateException("Media returned null exact-version content.");
        }
        if (content.content() == null) {
            throw new IllegalStateException("Media returned a null exact-version content stream.");
        }
        return content.content();
    }

    private static void requireExactContent(
            MediaAssetVersionSnapshotDTO captured,
            MediaAssetVersionContentDTO content
    ) {
        if (!Objects.equals(content.assetId(), captured.assetId())
                || content.versionNumber() != captured.versionNumber()
                || !Objects.equals(content.contentHash(), captured.contentHash())
                || !Objects.equals(content.mimeType(), captured.mimeType())
                || content.sizeBytes() != captured.sizeBytes()) {
            throw new IllegalStateException("Media exact-version content did not match captured provenance.");
        }
    }

    private void requestCandidateCleanupPreservingFailure(UUID mediaAssetId, Throwable primaryFailure) {
        try {
            cleanupUseCase.execute(mediaAssetId, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        } catch (RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private void requestSupersededCleanupAfterCommit(
            FinalizeChapterNarrationPlaybackResult finalized,
            UUID supersededMediaAssetId
    ) {
        try {
            cleanupUseCase.execute(
                    supersededMediaAssetId,
                    NarrationMediaCleanupReason.SUPERSEDED_CHAPTER_PLAYBACK_ASSET
            );
        } catch (RuntimeException cleanupFailure) {
            log.error(
                    "Post-commit cleanup request failed for published chapter narration playback [{}], "
                            + "artifact [{}], and superseded Media asset [{}]. Published candidate remains current.",
                    finalized.playbackId(),
                    finalized.artifactId(),
                    supersededMediaAssetId,
                    cleanupFailure
            );
        }
    }

    static String originalFilename(UUID chapterId, UUID managedVoiceId) {
        return "chapter-" + chapterId + "-voice-" + managedVoiceId + ".mp3";
    }

    private static final class OpenedExactSources implements AutoCloseable {

        private final List<OwnedExactInputStream> streams = new ArrayList<>();
        private final List<ChapterAudioSegmentSource> sources = new ArrayList<>();

        OwnedExactInputStream register(InputStream stream) {
            OwnedExactInputStream owned = new OwnedExactInputStream(stream);
            streams.add(owned);
            return owned;
        }

        void addSource(ChapterAudioSegmentSource source) {
            sources.add(source);
        }

        ChapterAudioAssemblyRequest toAssemblyRequest() {
            return new ChapterAudioAssemblyRequest(sources);
        }

        @Override
        public void close() {
            RuntimeException primaryFailure = null;
            for (int i = streams.size() - 1; i >= 0; i--) {
                try {
                    streams.get(i).close();
                } catch (IOException | RuntimeException closeFailure) {
                    if (primaryFailure == null) {
                        primaryFailure = new IllegalStateException(
                                "Failed to close exact narration source stream.",
                                closeFailure
                        );
                    } else {
                        primaryFailure.addSuppressed(closeFailure);
                    }
                }
            }
            if (primaryFailure != null) {
                throw primaryFailure;
            }
        }

        void closePreserving(Throwable primaryFailure) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                primaryFailure.addSuppressed(closeFailure);
            }
        }
    }

    private static final class OwnedExactInputStream extends FilterInputStream {

        private boolean opened;
        private boolean closed;

        private OwnedExactInputStream(InputStream delegate) {
            super(Objects.requireNonNull(delegate, "delegate must not be null"));
        }

        synchronized InputStream openOnce() {
            if (opened) {
                throw new IllegalStateException("Exact narration source stream can only be opened once.");
            }
            opened = true;
            return this;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            super.close();
        }
    }
}
