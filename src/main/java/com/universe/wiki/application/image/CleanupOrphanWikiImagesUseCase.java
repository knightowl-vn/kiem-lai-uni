package com.universe.wiki.application.image;

import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.ports
        .LegacyWikiImageStoragePort;
import com.universe.wiki.application.ports
        .WikiImageRepositoryPort;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    private final LegacyWikiImageStoragePort
            legacyImageStoragePort;

    private final MediaContract
            mediaContract;

    private final ClockPort
            clockPort;


    public CleanupOrphanWikiImagesUseCase(
            WikiImageRepositoryPort imageRepositoryPort,
            LegacyWikiImageStoragePort legacyImageStoragePort,
            MediaContract mediaContract,
            ClockPort clockPort
    ) {
        this.imageRepositoryPort =
                Objects.requireNonNull(
                        imageRepositoryPort,
                        "WikiImageRepositoryPort "
                                + "không được để trống."
                );

        this.legacyImageStoragePort =
                Objects.requireNonNull(
                        legacyImageStoragePort,
                        "LegacyWikiImageStoragePort "
                                + "không được để trống."
                );

        this.mediaContract =
                Objects.requireNonNull(
                        mediaContract,
                        "MediaContract "
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
                if (candidate.mediaAssetId() != null) {
                    /*
                     * Media-backed retry-safe cleanup:
                     * 1. Kiểm tra trạng thái lifecycle của Media asset
                     * 2. Nếu ACTIVE hoặc ARCHIVED: gọi MediaContract.delete rồi xóa DB metadata
                     * 3. Nếu đã DELETED: không gọi delete lại (retry-safe), trực tiếp xóa DB metadata
                     * 4. Nếu không tìm thấy: coi như lỗi, giữ nguyên DB metadata
                     */
                    Optional<MediaAssetDetailDTO> maybeDetail =
                            mediaContract
                                    .getAssetDetail(
                                            candidate.mediaAssetId()
                                    );

                    if (maybeDetail.isEmpty()) {
                        failed++;
                    } else {
                        MediaAssetStatusDTO status =
                                maybeDetail.get().status();

                        if (status == MediaAssetStatusDTO.ACTIVE
                                || status == MediaAssetStatusDTO.ARCHIVED) {
                            mediaContract
                                    .delete(
                                            candidate.mediaAssetId()
                                    );

                            imageRepositoryPort
                                    .deleteById(
                                            candidate.id()
                                    );

                            deleted++;
                        } else if (status == MediaAssetStatusDTO.DELETED) {
                            imageRepositoryPort
                                    .deleteById(
                                            candidate.id()
                                    );

                            deleted++;
                        } else {
                            failed++;
                        }
                    }
                } else if (candidate.publicId() != null && !candidate.publicId().isBlank()) {
                    /*
                     * Legacy Cloudinary:
                     * 1. Yêu cầu publicId hợp lệ không rỗng
                     * 2. Xóa storage Cloudinary trước
                     * 3. Xóa metadata DB sau
                     */
                    legacyImageStoragePort
                            .delete(
                                    candidate.publicId()
                            );

                    imageRepositoryPort
                            .deleteById(
                                    candidate.id()
                            );

                    deleted++;
                } else {
                    /*
                     * Bản ghi chuyển tiếp không hợp lệ:
                     * mediaAssetId == null và publicId null/blank.
                     * Coi là candidate thất bại, không xóa metadata DB.
                     */
                    failed++;
                }

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