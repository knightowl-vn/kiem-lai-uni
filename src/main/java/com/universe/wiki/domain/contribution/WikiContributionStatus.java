package com.universe.wiki.domain.contribution;

/**
 * Trạng thái xử lý của một đóng góp Wiki trong quy trình kiểm duyệt.
 */
public enum WikiContributionStatus {
    NEW,
    REVIEWING,
    RESOLVED,
    REJECTED
}
