package com.universe.wiki.infrastructure.maintenance;

import com.universe.wiki.application.image
        .CleanupOrphanWikiImagesUseCase;
import com.universe.wiki.application.image
        .WikiImageCleanupResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(
        name = "wiki.image-cleanup.enabled",
        havingValue = "true"
)
public class WikiImageCleanupRunner
        implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    WikiImageCleanupRunner.class
            );

    private final CleanupOrphanWikiImagesUseCase
            cleanupUseCase;


    public WikiImageCleanupRunner(
            CleanupOrphanWikiImagesUseCase cleanupUseCase
    ) {
        this.cleanupUseCase =
                cleanupUseCase;
    }


    @Override
    public void run(
            ApplicationArguments args
    ) {

        LOGGER.warn(
                "Bắt đầu LIVE cleanup ảnh Wiki orphan..."
        );

        WikiImageCleanupResult result =
                cleanupUseCase.execute(
                        false
                );

        LOGGER.warn(
                "Hoàn tất LIVE cleanup ảnh Wiki. "
                        + "Candidates: {}, "
                        + "Deleted: {}, "
                        + "Failed: {}, "
                        + "Dry-run: {}",
                result.candidates(),
                result.deleted(),
                result.failed(),
                result.dryRun()
        );
    }
}