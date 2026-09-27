package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Ngoại lệ ném ra khi cố gắng ghi nhận công trạng cho một đóng góp đã được ghi nhận trước đó.
 */
public class WikiContributionAlreadyCreditedException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public WikiContributionAlreadyCreditedException(UUID contributionId) {
        super(
                "WIKI_CONTRIBUTION_ALREADY_CREDITED",
                "Đóng góp Wiki " + contributionId + " đã được ghi nhận công trạng trước đó."
        );
    }

    public WikiContributionAlreadyCreditedException(UUID contributionId, Throwable cause) {
        super(
                "WIKI_CONTRIBUTION_ALREADY_CREDITED",
                "Đóng góp Wiki " + contributionId + " đã được ghi nhận công trạng trước đó.",
                cause
        );
    }
}
