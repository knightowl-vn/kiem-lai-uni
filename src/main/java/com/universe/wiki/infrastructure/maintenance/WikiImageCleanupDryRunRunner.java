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
        name = "wiki.image-cleanup.dry-run.enabled",
        havingValue = "true"
)
public class WikiImageCleanupDryRunRunner
        implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    WikiImageCleanupDryRunRunner.class
            );

    private final CleanupOrphanWikiImagesUseCase
            cleanupUseCase;


    public WikiImageCleanupDryRunRunner(
            CleanupOrphanWikiImagesUseCase cleanupUseCase
    ) {
        this.cleanupUseCase =
                cleanupUseCase;
    }


    @Override
    public void run(
            ApplicationArguments args
    ) {

        LOGGER.info(
                "Bắt đầu DRY-RUN cleanup ảnh Wiki orphan..."
        );

        WikiImageCleanupResult result =
                cleanupUseCase.execute(
                        true
                );

        LOGGER.info(
                "Hoàn tất DRY-RUN cleanup ảnh Wiki. "
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