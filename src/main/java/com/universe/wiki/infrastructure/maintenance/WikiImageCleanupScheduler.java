package com.universe.wiki.infrastructure.maintenance;

import com.universe.wiki.application.image
        .CleanupOrphanWikiImagesUseCase;

import com.universe.wiki.application.image
        .WikiImageCleanupResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.scheduling.annotation
        .Scheduled;

import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(
        name = "wiki.image-cleanup.schedule.enabled",
        havingValue = "true"
)
public class WikiImageCleanupScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    WikiImageCleanupScheduler.class
            );

    private final CleanupOrphanWikiImagesUseCase
            cleanupUseCase;


    public WikiImageCleanupScheduler(
            CleanupOrphanWikiImagesUseCase cleanupUseCase
    ) {
        this.cleanupUseCase =
                cleanupUseCase;
    }


    /*
     * =====================================================
     * DAILY WIKI IMAGE CLEANUP
     * =====================================================
     *
     * Mặc định:
     *
     * 03:00 mỗi ngày
     * Asia/Ho_Chi_Minh
     *
     * Cleanup use case vẫn áp dụng
     * grace period 7 ngày.
     */
    @Scheduled(
            cron =
                    "${wiki.image-cleanup.schedule.cron:"
                            + "0 0 3 * * *}",
            zone =
                    "${wiki.image-cleanup.schedule.zone:"
                            + "Asia/Ho_Chi_Minh}"
    )
    public void cleanupOrphanImages() {

        LOGGER.info(
                "Bắt đầu scheduled cleanup "
                        + "ảnh Wiki orphan..."
        );

        try {

            WikiImageCleanupResult result =
                    cleanupUseCase.execute(
                            false
                    );

            LOGGER.info(
                    "Hoàn tất scheduled cleanup ảnh Wiki. "
                            + "Candidates: {}, "
                            + "Deleted: {}, "
                            + "Failed: {}",
                    result.candidates(),
                    result.deleted(),
                    result.failed()
            );

        } catch (
                RuntimeException exception
        ) {

            /*
             * Lỗi cấp batch, ví dụ DB unavailable.
             *
             * Không làm application chết.
             * Scheduler sẽ thử lại vào lần chạy tiếp theo.
             */
            LOGGER.error(
                    "Scheduled cleanup ảnh Wiki thất bại.",
                    exception
            );
        }
    }
}