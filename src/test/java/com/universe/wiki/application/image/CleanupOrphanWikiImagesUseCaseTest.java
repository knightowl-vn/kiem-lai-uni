package com.universe.wiki.application.image;

import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.ports
        .LegacyWikiImageStoragePort;
import com.universe.wiki.application.ports
        .WikiImageRepositoryPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension
        .ExtendWith;

import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter
        .MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions
        .assertThat;
import static org.mockito.ArgumentMatchers
        .any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito
        .never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito
        .verify;
import static org.mockito.Mockito
        .verifyNoInteractions;
import static org.mockito.Mockito
        .when;

@ExtendWith(MockitoExtension.class)
class CleanupOrphanWikiImagesUseCaseTest {

    private static final Instant NOW =
            Instant.parse("2026-08-14T07:00:00Z");

    private static final Instant CUTOFF =
            NOW.minus(Duration.ofDays(7));

    @Mock
    private WikiImageRepositoryPort
            imageRepositoryPort;

    @Mock
    private LegacyWikiImageStoragePort
            legacyImageStoragePort;

    @Mock
    private MediaContract
            mediaContract;

    @Mock
    private ClockPort
            clockPort;

    private CleanupOrphanWikiImagesUseCase
            useCase;

    private MediaAssetDetailDTO createMediaAssetDetail(UUID id, MediaAssetStatusDTO status) {
        return new MediaAssetDetailDTO(
                id,
                MediaTypeDTO.IMAGE,
                MediaVisibilityDTO.PUBLIC,
                status,
                1,
                NOW.minus(Duration.ofDays(10)),
                NOW.minus(Duration.ofDays(10)),
                null
        );
    }

    @BeforeEach
    void setUp() {
        useCase =
                new CleanupOrphanWikiImagesUseCase(
                        imageRepositoryPort,
                        legacyImageStoragePort,
                        mediaContract,
                        clockPort
                );

        when(
                clockPort.now()
        ).thenReturn(
                NOW
        );
    }

    @Test
    @DisplayName("dry run performs zero Media/Cloudinary/DB deletes")
    void shouldNotDeleteAnythingInDryRun() {
        UUID mediaAssetId = UUID.randomUUID();
        WikiImageAsset mediaCandidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-media",
                        "/media/assets/" + mediaAssetId + "/content",
                        null,
                        mediaAssetId,
                        "image/png",
                        1024L,
                        NOW.minus(Duration.ofDays(8))
                );

        WikiImageAsset legacyCandidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-legacy",
                        "https://example.com/legacy.webp",
                        "kiemlai/wiki/legacy",
                        null,
                        "image/webp",
                        2048L,
                        NOW.minus(Duration.ofDays(9))
                );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(mediaCandidate, legacyCandidate)
        );

        WikiImageCleanupResult result =
                useCase.execute(true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.deleted()).isZero();
        assertThat(result.failed()).isZero();

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(legacyImageStoragePort);
        verify(imageRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("Media-backed orphan calls MediaContract.delete then Wiki metadata delete")
    void shouldDeleteMediaBackedOrphanViaMediaContractThenWikiMetadata() {
        UUID mediaAssetId = UUID.randomUUID();
        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-media",
                        "/media/assets/" + mediaAssetId + "/content",
                        null,
                        mediaAssetId,
                        "image/png",
                        1024L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        when(
                mediaContract.getAssetDetail(mediaAssetId)
        ).thenReturn(
                Optional.of(createMediaAssetDetail(mediaAssetId, MediaAssetStatusDTO.ACTIVE))
        );

        WikiImageCleanupResult result =
                useCase.execute(false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        InOrder inOrder = inOrder(mediaContract, imageRepositoryPort);
        inOrder.verify(mediaContract).delete(mediaAssetId);
        inOrder.verify(imageRepositoryPort).deleteById(candidate.id());
    }

    @Test
    @DisplayName("Media-backed orphan never calls LegacyWikiImageStoragePort")
    void shouldNeverCallStoragePortForMediaBackedOrphan() {
        UUID mediaAssetId = UUID.randomUUID();
        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-media",
                        "/media/assets/" + mediaAssetId + "/content",
                        null,
                        mediaAssetId,
                        "image/png",
                        1024L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        when(
                mediaContract.getAssetDetail(mediaAssetId)
        ).thenReturn(
                Optional.of(createMediaAssetDetail(mediaAssetId, MediaAssetStatusDTO.ACTIVE))
        );

        useCase.execute(false);

        verify(legacyImageStoragePort, never()).delete(any());
    }

    @Test
    @DisplayName("legacy orphan calls LegacyWikiImageStoragePort.delete then Wiki metadata delete")
    void shouldDeleteLegacyOrphanViaStoragePortThenWikiMetadata() {
        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-legacy",
                        "https://res.cloudinary.com/demo/image/upload/v1/kiemlai/wiki/legacy.webp",
                        "kiemlai/wiki/legacy",
                        null,
                        "image/webp",
                        2048L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        WikiImageCleanupResult result =
                useCase.execute(false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        InOrder inOrder = inOrder(legacyImageStoragePort, imageRepositoryPort);
        inOrder.verify(legacyImageStoragePort).delete("kiemlai/wiki/legacy");
        inOrder.verify(imageRepositoryPort).deleteById(candidate.id());
    }

    @Test
    @DisplayName("legacy orphan never calls MediaContract")
    void shouldNeverCallMediaContractForLegacyOrphan() {
        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-legacy",
                        "https://res.cloudinary.com/demo/image/upload/v1/kiemlai/wiki/legacy.webp",
                        "kiemlai/wiki/legacy",
                        null,
                        "image/webp",
                        2048L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        useCase.execute(false);

        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("Media delete failure keeps Wiki metadata and counts failure")
    void shouldKeepDatabaseMetadataWhenMediaDeleteFails() {
        UUID mediaAssetId = UUID.randomUUID();
        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-media",
                        "/media/assets/" + mediaAssetId + "/content",
                        null,
                        mediaAssetId,
                        "image/png",
                        1024L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        when(
                mediaContract.getAssetDetail(mediaAssetId)
        ).thenReturn(
                Optional.of(createMediaAssetDetail(mediaAssetId, MediaAssetStatusDTO.ACTIVE))
        );

        doThrow(
                new RuntimeException("Media delete failed")
        ).when(
                mediaContract
        ).delete(
                mediaAssetId
        );

        WikiImageCleanupResult result =
                useCase.execute(false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        assertThat(result.failed()).isEqualTo(1);

        verify(imageRepositoryPort, never()).deleteById(candidate.id());
    }

    @Test
    @DisplayName("Cloudinary delete failure keeps Wiki metadata and counts failure")
    void shouldKeepDatabaseMetadataWhenCloudinaryDeleteFails() {
        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-legacy",
                        "https://example.com/wiki/test.webp",
                        "kiemlai/wiki/test",
                        null,
                        "image/png",
                        123L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        doThrow(
                new IllegalStateException(
                        "Cloudinary delete failed"
                )
        ).when(
                legacyImageStoragePort
        ).delete(
                candidate.publicId()
        );

        WikiImageCleanupResult result =
                useCase.execute(false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        assertThat(result.failed()).isEqualTo(1);

        verify(legacyImageStoragePort).delete(candidate.publicId());
        verify(imageRepositoryPort, never()).deleteById(candidate.id());
    }

    @Test
    @DisplayName("invalid row with no mediaAssetId and blank/null publicId counts failure")
    void shouldCountFailureAndKeepMetadataForInvalidTransitionalRow() {
        WikiImageAsset nullPublicIdCandidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-invalid-1",
                        "https://example.com/invalid1.webp",
                        null,
                        null,
                        "image/png",
                        512L,
                        NOW.minus(Duration.ofDays(8))
                );

        WikiImageAsset blankPublicIdCandidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-invalid-2",
                        "https://example.com/invalid2.webp",
                        "   ",
                        null,
                        "image/png",
                        512L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(nullPublicIdCandidate, blankPublicIdCandidate)
        );

        WikiImageCleanupResult result =
                useCase.execute(false);

        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.deleted()).isZero();
        assertThat(result.failed()).isEqualTo(2);

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(legacyImageStoragePort);
        verify(imageRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("one failed candidate does not stop later candidates")
    void shouldIsolateCandidateFailuresAndContinueProcessingRemainingCandidates() {
        UUID failedMediaAssetId = UUID.randomUUID();
        WikiImageAsset c1FailedMedia =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-c1",
                        "/media/assets/" + failedMediaAssetId + "/content",
                        null,
                        failedMediaAssetId,
                        "image/png",
                        100L,
                        NOW.minus(Duration.ofDays(8))
                );

        UUID successMediaAssetId = UUID.randomUUID();
        WikiImageAsset c2SuccessMedia =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-c2",
                        "/media/assets/" + successMediaAssetId + "/content",
                        null,
                        successMediaAssetId,
                        "image/png",
                        200L,
                        NOW.minus(Duration.ofDays(8))
                );

        WikiImageAsset c3FailedLegacy =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-c3",
                        "https://example.com/c3.webp",
                        "kiemlai/wiki/c3",
                        null,
                        "image/webp",
                        300L,
                        NOW.minus(Duration.ofDays(8))
                );

        WikiImageAsset c4SuccessLegacy =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-c4",
                        "https://example.com/c4.webp",
                        "kiemlai/wiki/c4",
                        null,
                        "image/webp",
                        400L,
                        NOW.minus(Duration.ofDays(8))
                );

        WikiImageAsset c5Invalid =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-c5",
                        "https://example.com/c5.webp",
                        "",
                        null,
                        "image/webp",
                        500L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(c1FailedMedia, c2SuccessMedia, c3FailedLegacy, c4SuccessLegacy, c5Invalid)
        );

        when(
                mediaContract.getAssetDetail(failedMediaAssetId)
        ).thenReturn(
                Optional.of(createMediaAssetDetail(failedMediaAssetId, MediaAssetStatusDTO.ACTIVE))
        );

        when(
                mediaContract.getAssetDetail(successMediaAssetId)
        ).thenReturn(
                Optional.of(createMediaAssetDetail(successMediaAssetId, MediaAssetStatusDTO.ACTIVE))
        );

        doThrow(
                new RuntimeException("Media delete c1 error")
        ).when(
                mediaContract
        ).delete(
                failedMediaAssetId
        );

        doThrow(
                new RuntimeException("Cloudinary delete c3 error")
        ).when(
                legacyImageStoragePort
        ).delete(
                c3FailedLegacy.publicId()
        );

        WikiImageCleanupResult result =
                useCase.execute(false);

        assertThat(result.candidates()).isEqualTo(5);
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(3);

        // c2 succeeded
        verify(mediaContract).delete(successMediaAssetId);
        verify(imageRepositoryPort).deleteById(c2SuccessMedia.id());

        // c4 succeeded
        verify(legacyImageStoragePort).delete("kiemlai/wiki/c4");
        verify(imageRepositoryPort).deleteById(c4SuccessLegacy.id());

        // c1, c3, c5 metadata rows were NOT deleted
        verify(imageRepositoryPort, never()).deleteById(c1FailedMedia.id());
        verify(imageRepositoryPort, never()).deleteById(c3FailedLegacy.id());
        verify(imageRepositoryPort, never()).deleteById(c5Invalid.id());
    }

    @Test
    @DisplayName("existing 7-day candidate selection semantics remain unchanged")
    void shouldPreserve7DayOrphanGracePeriodSemantics() {
        when(
                imageRepositoryPort
                        .findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of()
        );

        useCase.execute(false);

        verify(imageRepositoryPort).findCleanupCandidates(CUTOFF);
        assertThat(CUTOFF).isEqualTo(NOW.minus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("already-DELETED Media asset cleans up the remaining Wiki row without another delete call")
    void shouldDirectlyDeleteWikiRowWithoutCallingMediaDeleteWhenMediaAssetAlreadyDeleted() {
        UUID mediaAssetId = UUID.randomUUID();
        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-media-deleted",
                        "/media/assets/" + mediaAssetId + "/content",
                        null,
                        mediaAssetId,
                        "image/png",
                        1024L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort.findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        when(
                mediaContract.getAssetDetail(mediaAssetId)
        ).thenReturn(
                Optional.of(createMediaAssetDetail(mediaAssetId, MediaAssetStatusDTO.DELETED))
        );

        WikiImageCleanupResult result =
                useCase.execute(false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        verify(mediaContract, never()).delete(any());
        verify(imageRepositoryPort).deleteById(candidate.id());
    }

    @Test
    @DisplayName("missing Media asset counts failure and retains Wiki row")
    void shouldCountFailureAndRetainWikiRowWhenMediaAssetNotFound() {
        UUID mediaAssetId = UUID.randomUUID();
        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-media-missing",
                        "/media/assets/" + mediaAssetId + "/content",
                        null,
                        mediaAssetId,
                        "image/png",
                        1024L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort.findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        when(
                mediaContract.getAssetDetail(mediaAssetId)
        ).thenReturn(
                Optional.empty()
        );

        WikiImageCleanupResult result =
                useCase.execute(false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        assertThat(result.failed()).isEqualTo(1);

        verify(mediaContract, never()).delete(any());
        verify(imageRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("ARCHIVED Media asset calls delete then Wiki metadata delete")
    void shouldDeleteMediaBackedOrphanWhenMediaAssetIsArchived() {
        UUID mediaAssetId = UUID.randomUUID();
        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash-media-archived",
                        "/media/assets/" + mediaAssetId + "/content",
                        null,
                        mediaAssetId,
                        "image/png",
                        1024L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort.findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        when(
                mediaContract.getAssetDetail(mediaAssetId)
        ).thenReturn(
                Optional.of(createMediaAssetDetail(mediaAssetId, MediaAssetStatusDTO.ARCHIVED))
        );

        WikiImageCleanupResult result =
                useCase.execute(false);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        InOrder inOrder = inOrder(mediaContract, imageRepositoryPort);
        inOrder.verify(mediaContract).delete(mediaAssetId);
        inOrder.verify(imageRepositoryPort).deleteById(candidate.id());
    }

    @Test
    @DisplayName("DB deletion failure after successful Media delete is recoverable on a later simulated run where detail reports DELETED")
    void shouldRecoverAndCleanUpRemainingWikiRowOnSubsequentRunWhenPreviousDbDeleteFailed() {
        UUID mediaAssetId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        WikiImageAsset candidate =
                new WikiImageAsset(
                        candidateId,
                        "hash-media-recoverable",
                        "/media/assets/" + mediaAssetId + "/content",
                        null,
                        mediaAssetId,
                        "image/png",
                        1024L,
                        NOW.minus(Duration.ofDays(8))
                );

        when(
                imageRepositoryPort.findCleanupCandidates(CUTOFF)
        ).thenReturn(
                List.of(candidate)
        );

        // === RUN 1: Media ACTIVE, Media delete succeeds, Wiki metadata delete fails ===
        when(
                mediaContract.getAssetDetail(mediaAssetId)
        ).thenReturn(
                Optional.of(createMediaAssetDetail(mediaAssetId, MediaAssetStatusDTO.ACTIVE))
        );

        doThrow(
                new RuntimeException("DB deadlock during metadata delete")
        ).when(
                imageRepositoryPort
        ).deleteById(
                candidateId
        );

        WikiImageCleanupResult result1 = useCase.execute(false);

        assertThat(result1.candidates()).isEqualTo(1);
        assertThat(result1.deleted()).isZero();
        assertThat(result1.failed()).isEqualTo(1);

        verify(mediaContract).delete(mediaAssetId);
        verify(imageRepositoryPort).deleteById(candidateId);

        // === RUN 2: Simulated next cleanup run ===
        // Detail now reports DELETED (because Run 1 succeeded in transitioning Media to DELETED)
        when(
                mediaContract.getAssetDetail(mediaAssetId)
        ).thenReturn(
                Optional.of(createMediaAssetDetail(mediaAssetId, MediaAssetStatusDTO.DELETED))
        );

        // DB delete now succeeds (reset mock stubbing)
        doNothing().when(imageRepositoryPort).deleteById(candidateId);

        WikiImageCleanupResult result2 = useCase.execute(false);

        assertThat(result2.candidates()).isEqualTo(1);
        assertThat(result2.deleted()).isEqualTo(1);
        assertThat(result2.failed()).isZero();

        // Verify: no second Media delete occurred (total invocations across both runs is exactly 1)
        verify(mediaContract, times(1)).delete(mediaAssetId);

        // Verify: Wiki metadata deletion succeeded on run 2 (total invocations is 2)
        verify(imageRepositoryPort, times(2)).deleteById(candidateId);
    }
}