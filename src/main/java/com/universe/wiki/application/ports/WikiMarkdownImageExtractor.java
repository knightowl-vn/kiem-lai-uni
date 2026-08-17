package com.universe.wiki.application.ports;

import java.util.Set;

public interface WikiMarkdownImageExtractor {

    Set<String> extractImageUrls(
            String markdown
    );
}