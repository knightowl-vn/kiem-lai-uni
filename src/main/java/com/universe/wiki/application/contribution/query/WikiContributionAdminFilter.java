package com.universe.wiki.application.contribution.query;

import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;

/**
 * Filter and pagination parameters for querying the admin Wiki contribution inbox.
 */
public record WikiContributionAdminFilter(
        WikiContributionStatus status,
        WikiContributionType contributionType,
        String keyword,
        int page,
        int size
) {
    private static final int MAX_KEYWORD_LENGTH = 180;

    public WikiContributionAdminFilter {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 20;
        }
        if (keyword != null) {
            keyword = keyword.trim();
            if (keyword.isEmpty()) {
                keyword = null;
            } else if (keyword.length() > MAX_KEYWORD_LENGTH) {
                keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
            }
        }
    }
}
