package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Ngoại lệ ném ra khi không tìm thấy bản ghi ghi nhận công trạng cho đóng góp.
 */
public class WikiContributionCreditNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public WikiContributionCreditNotFoundException(UUID contributionId) {
        super(
                "WIKI_CONTRIBUTION_CREDIT_NOT_FOUND",
                "Không tìm thấy bản ghi ghi nhận công trạng cho đóng góp Wiki " + contributionId + "."
        );
    }
}
