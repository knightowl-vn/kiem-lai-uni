package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaUploadSessionServiceMultiProviderTest {

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final StorageProviderId R2_MOVIE_PROVIDER = StorageProviderId.of("r2-movie");
    private static final String R2_MOVIE_BUCKET = "kiemlai-r2-movie-test";
    private static final StorageKey STORAGE_KEY = StorageKey.of("objects/movie/test.mp4");
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @Mock
    private MediaUploadSessionRepositoryPort sessionRepositoryPort;
    @Mock
    private StorageKeyAllocationService storageKeyAllocationService;
    @Mock
    private MediaStorageRoutingService routingService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private MultipartStoragePort b2Port;
    @Mock
    private MultipartStoragePort r2MoviePort;

    private ClockPort clockPort;
    private MediaUploadSessionService service;

    @BeforeEach
    void setUp() {
        clockPort = () -> NOW;
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        service = new MediaUploadSessionService(
                sessionRepositoryPort,
                storageKeyAllocationService,
                routingService,
                clockPort,
                transactionManager,
                List.of(b2Port, r2MoviePort),
                null,
                null
        );
    }

    @Test
    @DisplayName("allocates session with r2-movie when routing service resolves r2-movie")
    void allocatesSessionWithR2Movie() {
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(sessionRepositoryPort.findByIdempotencyKey("user-1", "idem-1"))
                .thenReturn(Optional.empty());
        when(routingService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen.entry:my-movie"))
                .thenReturn(R2_MOVIE_PROVIDER);
        when(routingService.generateStorageKey(eq(MediaType.VIDEO), eq("screen.entry:my-movie"), any(), eq(1)))
                .thenReturn(STORAGE_KEY);
        when(routingService.resolveBucketName(R2_MOVIE_PROVIDER))
                .thenReturn(R2_MOVIE_BUCKET);

        StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                allocId,
                R2_MOVIE_PROVIDER,
                R2_MOVIE_BUCKET,
                STORAGE_KEY,
                StorageKeyAllocationType.UPLOAD_SESSION,
                UUID.randomUUID(),
                NOW
        );
        when(storageKeyAllocationService.reserveKey(any(), any(), any(), any(), any()))
                .thenReturn(mockAlloc);
        when(sessionRepositoryPort.save(any(MediaUploadSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MediaUploadSession session = service.allocateSession(
                "user-1",
                "screen",
                "ScreenEntry",
                targetId,
                "idem-1",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 4,
                "feature.mp4",
                "screen.entry:my-movie"
        );

        assertThat(session).isNotNull();
        assertThat(session.getStorageProviderId()).isEqualTo(R2_MOVIE_PROVIDER);
        assertThat(session.getStorageBucketName()).isEqualTo(R2_MOVIE_BUCKET);
        assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);
    }

    @Test
    @DisplayName("delegates presigned URL generation to r2MoviePort when session has r2-movie provider")
    void delegatesPresignToR2MoviePort() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        MediaUploadSession r2Session = MediaUploadSession.create(
                sessionId,
                "user-1",
                "screen",
                "ScreenEntry",
                targetId,
                "idem-2",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                R2_MOVIE_PROVIDER,
                R2_MOVIE_BUCKET,
                STORAGE_KEY,
                allocId,
                "feature.mp4",
                "screen.entry:my-movie",
                NOW.plusSeconds(3600),
                NOW
        );
        // Advance to INITIATED
        r2Session.markInitiated("provider-up-r2-123", NOW);

        StorageKeyAllocation mockAlloc = StorageKeyAllocation.reserve(
                allocId,
                R2_MOVIE_PROVIDER,
                R2_MOVIE_BUCKET,
                STORAGE_KEY,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId,
                NOW
        );

        when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(r2Session));
        when(storageKeyAllocationService.findById(allocId)).thenReturn(Optional.of(mockAlloc));
        when(r2MoviePort.generatePresignedPartUploadUrl(eq(STORAGE_KEY), eq("provider-up-r2-123"), eq(1), any(Duration.class)))
                .thenReturn("https://r2.presigned.url/part-1");

        String url = service.generatePresignedPartUploadUrl(sessionId, 1, "user-1", Duration.ofMinutes(15));

        assertThat(url).isEqualTo("https://r2.presigned.url/part-1");
        verify(r2MoviePort).generatePresignedPartUploadUrl(eq(STORAGE_KEY), eq("provider-up-r2-123"), eq(1), any(Duration.class));
    }
}
