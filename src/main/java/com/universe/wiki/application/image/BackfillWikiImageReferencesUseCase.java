package com.universe.wiki.application.image;

import com.universe.wiki.application.ports
        .WikiImageReferenceBackfillPort;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class BackfillWikiImageReferencesUseCase {

    private final WikiImageReferenceBackfillPort
            backfillPort;

    public BackfillWikiImageReferencesUseCase(
            WikiImageReferenceBackfillPort backfillPort
    ) {
        this.backfillPort =
                Objects.requireNonNull(
                        backfillPort,
                        "WikiImageReferenceBackfillPort "
                                + "không được để trống."
                );
    }

    public WikiImageReferenceBackfillResult execute() {
        return backfillPort.backfill();
    }
}