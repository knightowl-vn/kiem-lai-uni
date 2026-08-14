package com.universe.wiki.application.ports;

import com.universe.wiki.application.image
        .WikiImageReferenceBackfillResult;

public interface WikiImageReferenceBackfillPort {

    WikiImageReferenceBackfillResult backfill();
}