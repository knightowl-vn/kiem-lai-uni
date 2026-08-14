package com.universe.wiki.infrastructure.maintenance;

import com.universe.wiki.application.image
        .BackfillWikiImageReferencesUseCase;
import com.universe.wiki.application.image
        .WikiImageReferenceBackfillResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(
        name =
                "wiki.image-reference-backfill.enabled",
        havingValue =
                "true"
)
public class WikiImageReferenceBackfillRunner
        implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    WikiImageReferenceBackfillRunner.class
            );

    private final BackfillWikiImageReferencesUseCase
            backfillUseCase;


    public WikiImageReferenceBackfillRunner(
            BackfillWikiImageReferencesUseCase backfillUseCase
    ) {
        this.backfillUseCase =
                backfillUseCase;
    }


    @Override
    public void run(
            ApplicationArguments args
    ) {

        LOGGER.info(
                "Bắt đầu backfill Wiki image references..."
        );

        WikiImageReferenceBackfillResult result =
                backfillUseCase.execute();

        LOGGER.info(
                "Hoàn tất backfill Wiki image references. "
                        + "Articles scanned: {}, "
                        + "Revisions scanned: {}",
                result.articlesScanned(),
                result.revisionsScanned()
        );
    }
}