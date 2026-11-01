package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class InvalidScreenTitleTotalEpisodeCountException extends BaseApplicationException {

    public InvalidScreenTitleTotalEpisodeCountException(int totalEpisodeCount, int latestPublishedEpisodeNumber) {
        super(
                "SCREEN_TITLE_INVALID_TOTAL_EPISODES",
                "Tổng số tập dự kiến (" + totalEpisodeCount + ") không thể nhỏ hơn số tập mới nhất đã xuất bản (" + latestPublishedEpisodeNumber + ")."
        );
    }

    public InvalidScreenTitleTotalEpisodeCountException(String message) {
        super("SCREEN_TITLE_INVALID_TOTAL_EPISODES", message);
    }
}
