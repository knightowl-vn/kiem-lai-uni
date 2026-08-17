package com.universe.wiki.infrastructure.maintenance;

import com.universe.wiki.application.image
        .CleanupOrphanWikiImagesUseCase;

import com.universe.wiki.application.image
        .WikiImageCleanupResult;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension
        .ExtendWith;

import org.mockito.Mock;

import org.mockito.junit.jupiter
        .MockitoExtension;

import static org.mockito.Mockito
        .verify;

import static org.mockito.Mockito
        .when;


@ExtendWith(MockitoExtension.class)
class WikiImageCleanupSchedulerTest {

    @Mock
    private CleanupOrphanWikiImagesUseCase
            cleanupUseCase;


    @Test
    void shouldRunLiveCleanup() {

        when(
                cleanupUseCase.execute(
                        false
                )
        ).thenReturn(
                new WikiImageCleanupResult(
                        2,
                        2,
                        0,
                        false
                )
        );


        WikiImageCleanupScheduler scheduler =
                new WikiImageCleanupScheduler(
                        cleanupUseCase
                );


        scheduler.cleanupOrphanImages();


        verify(
                cleanupUseCase
        ).execute(
                false
        );
    }
    
    @Test
    void shouldNotCrashWhenCleanupFails() {

        when(
                cleanupUseCase.execute(
                        false
                )
        ).thenThrow(
                new IllegalStateException(
                        "Database unavailable"
                )
        );


        WikiImageCleanupScheduler scheduler =
                new WikiImageCleanupScheduler(
                        cleanupUseCase
                );


        org.assertj.core.api.Assertions
                .assertThatCode(
                        scheduler::cleanupOrphanImages
                )
                .doesNotThrowAnyException();


        verify(
                cleanupUseCase
        ).execute(
                false
        );
    }
}