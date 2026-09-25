package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Ngoại lệ ném ra khi phát hiện xung đột khóa lạc quan (optimistic locking failure)
 * trong quá trình cập nhật bản ghi ghi nhận công trạng.
 */
public class WikiContributionCreditStaleMutationException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public WikiContributionCreditStaleMutationException(UUID creditId, Throwable cause) {
        super(
                "WIKI_CONTRIBUTION_CREDIT_STALE_MUTATION",
                "Bản ghi ghi nhận công trạng " + creditId + " đã bị cập nhật đồng thời bởi một tác vụ khác.",
                cause
        );
    }
}
