package com.universe.novel.domain.reader;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho tiến độ đọc của một người dùng đã xác thực.
 *
 * Quản lý:
 * - Chương vừa mở gần nhất (lastOpenedChapterId);
 * - Mức tiến độ chương cao nhất đã đạt được (highestReachedChapterNumber);
 * - Tính đơn điệu không giảm của highestReachedChapterNumber;
 * - Thời điểm tạo và cập nhật trạng thái tiến độ đọc.
 *
 * Aggregate thuần túy không chứa thông tin version của persistence (JPA @Version)
 * hoặc logic lịch sử/bookmark.
 */
public class UserReadingProgress {

    private final UUID id;

    private final UUID userId;

    private UUID lastOpenedChapterId;

    private int highestReachedChapterNumber;

    private final Instant createdAt;

    private Instant updatedAt;

    private UserReadingProgress(
            UUID id,
            UUID userId,
            UUID lastOpenedChapterId,
            int highestReachedChapterNumber,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "ID tiến độ đọc không được để trống."
                );

        this.userId =
                Objects.requireNonNull(
                        userId,
                        "ID người dùng không được để trống."
                );

        this.lastOpenedChapterId =
                Objects.requireNonNull(
                        lastOpenedChapterId,
                        "ID chương vừa mở không được để trống."
                );

        this.highestReachedChapterNumber =
                validateChapterNumber(
                        highestReachedChapterNumber
                );

        this.createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Thời gian tạo tiến độ đọc không được để trống."
                );

        this.updatedAt =
                Objects.requireNonNull(
                        updatedAt,
                        "Thời gian cập nhật tiến độ đọc không được để trống."
                );
    }

    /**
     * Tạo bản ghi tiến độ đọc ban đầu cho người dùng khi bắt đầu đọc chương đầu tiên.
     */
    public static UserReadingProgress createInitial(
            UUID id,
            UUID userId,
            UUID chapterId,
            int chapterNumber,
            Instant now
    ) {
        Objects.requireNonNull(
                now,
                "Thời gian tạo tiến độ đọc không được để trống."
        );

        int validatedChapterNumber =
                validateChapterNumber(
                        chapterNumber
                );

        return new UserReadingProgress(
                id,
                userId,
                chapterId,
                validatedChapterNumber,
                now,
                now
        );
    }

    /**
     * Khôi phục Aggregate từ dữ liệu persistence.
     */
    public static UserReadingProgress rehydrate(
            UUID id,
            UUID userId,
            UUID lastOpenedChapterId,
            int highestReachedChapterNumber,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new UserReadingProgress(
                id,
                userId,
                lastOpenedChapterId,
                highestReachedChapterNumber,
                createdAt,
                updatedAt
        );
    }

    /**
     * Ghi nhận hành động mở/truy cập một chương của người dùng với thời điểm quan sát (observedAt).
     *
     * Quy tắc nghiệp vụ:
     * - highestReachedChapterNumber luôn tăng đơn điệu không giảm (monotonic high-water mark).
     * - lastOpenedChapterId và mốc watermark updatedAt chỉ cập nhật khi observedAt mới hơn updatedAt hiện tại.
     * - Khi mở lại một chương cũ với observedAt mới hơn: lastOpenedChapterId chuyển về chương cũ, highest giữ nguyên.
     * - Khi một sự kiện đọc bị trễ (observedAt cũ hơn updatedAt hiện tại): lastOpenedChapterId và updatedAt
     *   KHÔNG bị ghi đè lùi thời gian; highest chỉ tăng nếu số chương trễ này cao hơn highest hiện tại.
     * - Mở lại cùng chương ở thời điểm mới hơn: nâng mốc watermark updatedAt (trả về true).
     * - Sự kiện trùng lặp hoàn toàn cùng mốc thời gian: trả về false (idempotent no-op).
     *
     * @return true nếu có sự thay đổi trạng thái cần lưu trữ; false nếu là thao tác no-op idempotent.
     */
    public boolean recordChapterAccess(
            UUID chapterId,
            int chapterNumber,
            Instant observedAt
    ) {
        Objects.requireNonNull(
                chapterId,
                "ID chương không được để trống."
        );

        int validatedChapterNumber =
                validateChapterNumber(
                        chapterNumber
                );

        Objects.requireNonNull(
                observedAt,
                "Thời gian quan sát không được để trống."
        );

        boolean stateChanged = false;

        if (validatedChapterNumber > this.highestReachedChapterNumber) {
            this.highestReachedChapterNumber =
                    validatedChapterNumber;
            stateChanged = true;
        }

        if (observedAt.isAfter(this.updatedAt)) {
            this.lastOpenedChapterId =
                    chapterId;
            this.updatedAt =
                    observedAt;
            stateChanged = true;
        }

        return stateChanged;
    }

    private static int validateChapterNumber(
            int chapterNumber
    ) {
        if (chapterNumber < 1) {
            throw new IllegalArgumentException(
                    "Số chương phải lớn hơn hoặc bằng 1."
            );
        }
        return chapterNumber;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getLastOpenedChapterId() {
        return lastOpenedChapterId;
    }

    public int getHighestReachedChapterNumber() {
        return highestReachedChapterNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
