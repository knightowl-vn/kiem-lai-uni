package com.universe.wiki.application.image;

import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.ports
        .WikiImageRepositoryPort;
import com.universe.wiki.application.ports
        .WikiImageStoragePort;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class CleanupOrphanWikiImagesUseCase {

    /*
     * Ảnh orphan chỉ được cleanup
     * khi đã tồn tại ít nhất 7 ngày.
     */
    private static final Duration
            ORPHAN_GRACE_PERIOD =
            Duration.ofDays(
                    7
            );

    private final WikiImageRepositoryPort
            imageRepositoryPort;

    private final WikiImageStoragePort
            imageStoragePort;

    private final ClockPort
            clockPort;


    public CleanupOrphanWikiImagesUseCase(
            WikiImageRepositoryPort imageRepositoryPort,
            WikiImageStoragePort imageStoragePort,
            ClockPort clockPort
    ) {
        this.imageRepositoryPort =
                Objects.requireNonNull(
                        imageRepositoryPort,
                        "WikiImageRepositoryPort "
                                + "không được để trống."
                );

        this.imageStoragePort =
                Objects.requireNonNull(
                        imageStoragePort,
                        "WikiImageStoragePort "
                                + "không được để trống."
                );

        this.clockPort =
                Objects.requireNonNull(
                        clockPort,
                        "ClockPort không được để trống."
                );
    }


    public WikiImageCleanupResult execute(
            boolean dryRun
    ) {

        Instant now =
                clockPort.now();

        Instant cutoff =
                now.minus(
                        ORPHAN_GRACE_PERIOD
                );

        List<WikiImageAsset> candidates =
                imageRepositoryPort
                        .findCleanupCandidates(
                                cutoff
                        );


        /*
         * DRY RUN:
         *
         * chỉ thống kê,
         * tuyệt đối không xóa gì.
         */
        if (dryRun) {
            return new WikiImageCleanupResult(
                    candidates.size(),
                    0,
                    0,
                    true
            );
        }


        int deleted =
                0;

        int failed =
                0;


        for (
                WikiImageAsset candidate :
                candidates
        ) {
            try {

                /*
                 * QUAN TRỌNG:
                 *
                 * Cloudinary trước.
                 * DB sau.
                 */
                imageStoragePort
                        .delete(
                                candidate.publicId()
                        );


                /*
                 * Chỉ khi Cloudinary đã xóa
                 * hoặc báo asset không tồn tại
                 * thì mới xóa metadata DB.
                 */
                imageRepositoryPort
                        .deleteById(
                                candidate.id()
                        );

                deleted++;

            } catch (
                    RuntimeException exception
            ) {
                /*
                 * Một ảnh lỗi không làm dừng
                 * toàn bộ batch.
                 */
                failed++;
            }
        }


        return new WikiImageCleanupResult(
                candidates.size(),
                deleted,
                failed,
                false
        );
    }
}