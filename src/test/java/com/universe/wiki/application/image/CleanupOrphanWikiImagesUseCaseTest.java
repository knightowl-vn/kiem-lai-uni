package com.universe.wiki.application.image;

import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.ports
        .WikiImageRepositoryPort;
import com.universe.wiki.application.ports
        .WikiImageStoragePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension
        .ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter
        .MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions
        .assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito
        .never;
import static org.mockito.Mockito
        .verify;
import static org.mockito.Mockito
        .when;

@ExtendWith(MockitoExtension.class)
class CleanupOrphanWikiImagesUseCaseTest {

    @Mock
    private WikiImageRepositoryPort
            imageRepositoryPort;

    @Mock
    private WikiImageStoragePort
            imageStoragePort;

    @Mock
    private ClockPort
            clockPort;


    @Test
    void shouldNotDeleteAnythingInDryRun() {

        Instant now =
                Instant.parse(
                        "2026-08-14T07:00:00Z"
                );

        when(
                clockPort.now()
        ).thenReturn(
                now
        );

        when(
                imageRepositoryPort
                        .findCleanupCandidates(
                                now.minusSeconds(
                                        7L * 24 * 60 * 60
                                )
                        )
        ).thenReturn(
                List.of()
        );

        CleanupOrphanWikiImagesUseCase useCase =
                new CleanupOrphanWikiImagesUseCase(
                        imageRepositoryPort,
                        imageStoragePort,
                        clockPort
                );

        WikiImageCleanupResult result =
                useCase.execute(
                        true
                );

        assertThat(
                result.dryRun()
        ).isTrue();

        verify(
                imageStoragePort,
                never()
        ).delete(
                org.mockito.ArgumentMatchers
                        .anyString()
        );
    }
    
    @Test
    void shouldKeepDatabaseMetadataWhenCloudinaryDeleteFails() {

        Instant now =
                Instant.parse(
                        "2026-08-14T07:00:00Z"
                );

        WikiImageAsset candidate =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash",
                        "https://example.com/wiki/test.webp",
                        "kiemlai/wiki/test",
                        "image/png",
                        123L,
                        now.minusSeconds(
                                8L * 24 * 60 * 60
                        )
                );


        when(
                clockPort.now()
        ).thenReturn(
                now
        );


        when(
                imageRepositoryPort
                        .findCleanupCandidates(
                                now.minusSeconds(
                                        7L * 24 * 60 * 60
                                )
                        )
        ).thenReturn(
                List.of(
                        candidate
                )
        );


        doThrow(
                new IllegalStateException(
                        "Cloudinary delete failed"
                )
        ).when(
                imageStoragePort
        ).delete(
                candidate.publicId()
        );


        CleanupOrphanWikiImagesUseCase useCase =
                new CleanupOrphanWikiImagesUseCase(
                        imageRepositoryPort,
                        imageStoragePort,
                        clockPort
                );


        WikiImageCleanupResult result =
                useCase.execute(
                        false
                );


        assertThat(
                result.candidates()
        ).isEqualTo(
                1
        );

        assertThat(
                result.deleted()
        ).isZero();

        assertThat(
                result.failed()
        ).isEqualTo(
                1
        );


        verify(
                imageStoragePort
        ).delete(
                candidate.publicId()
        );


        /*
         * Quan trọng nhất:
         *
         * Cloudinary thất bại
         * → metadata DB KHÔNG được xóa.
         */
        verify(
                imageRepositoryPort,
                never()
        ).deleteById(
                candidate.id()
        );
    }
}