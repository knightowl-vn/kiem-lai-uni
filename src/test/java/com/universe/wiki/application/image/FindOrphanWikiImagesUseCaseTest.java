package com.universe.wiki.application.image;

import com.universe.wiki.application.ports
        .WikiImageRepositoryPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions
        .assertThat;

import static org.mockito.Mockito
        .when;

@ExtendWith(MockitoExtension.class)
class FindOrphanWikiImagesUseCaseTest {

    @Mock
    private WikiImageRepositoryPort
            imageRepositoryPort;

    @Test
    void shouldReturnOrphanImages() {

        WikiImageAsset orphan =
                new WikiImageAsset(
                        UUID.randomUUID(),
                        "hash",
                        "https://example.com/orphan.webp",
                        "kiemlai/wiki/orphan",
                        "image/png",
                        123L,
                        Instant.now()
                );

        when(
                imageRepositoryPort
                        .findOrphanImages()
        ).thenReturn(
                List.of(
                        orphan
                )
        );

        FindOrphanWikiImagesUseCase useCase =
                new FindOrphanWikiImagesUseCase(
                        imageRepositoryPort
                );

        List<WikiImageAsset> result =
                useCase.execute();

        assertThat(result)
                .containsExactly(
                        orphan
                );
    }
}