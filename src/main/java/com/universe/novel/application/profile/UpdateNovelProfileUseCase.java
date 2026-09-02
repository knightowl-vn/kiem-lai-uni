package com.universe.novel.application.profile;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.NovelProfileRepositoryPort;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;
import com.universe.novel.domain.NovelStatus;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Use case orchestrating updates to the default Novel profile and managing cover media asset lifecycle.
 *
 * <p><strong>Transaction Boundary:</strong> This service does NOT hold an outer database transaction while
 * executing binary storage operations via {@link MediaContract}. Database updates occur only after media upload
 * has completed.
 *
 * <p><strong>Compensation Semantics:</strong>
 * <ul>
 *   <li>Initial Asset: If {@code uploadAsset} succeeds but Novel database persistence fails, compensation deletes
 *       the newly registered media asset via {@link MediaContract#delete(UUID)}. If compensation also fails, the
 *       suppressed exception is attached to the primary persistence exception.</li>
 *   <li>Replacement Version: If {@code uploadVersion} succeeds but Novel database persistence fails, the existing
 *       asset is preserved without deletion or synthetic version rollback (non-atomic cross-module limitation).</li>
 * </ul>
 */
@Service
public class UpdateNovelProfileUseCase {

    private static final String DEFAULT_NOVEL_SLUG =
            "kiem-lai";

    private static final long MAX_COVER_FILE_SIZE =
            5L * 1024 * 1024; // 5 MB

    private static final Set<String> SUPPORTED_CONTENT_TYPES =
            Set.of(
                    "image/jpeg",
                    "image/png",
                    "image/webp"
            );

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(
                    "jpg",
                    "jpeg",
                    "png",
                    "webp"
            );

    private final NovelProfileRepositoryPort
            novelProfileRepositoryPort;

    private final MediaContract
            mediaContract;

    private final ClockPort
            clockPort;

    public UpdateNovelProfileUseCase(
            NovelProfileRepositoryPort novelProfileRepositoryPort,
            MediaContract mediaContract,
            ClockPort clockPort
    ) {
        this.novelProfileRepositoryPort =
                Objects.requireNonNull(
                        novelProfileRepositoryPort,
                        "NovelProfileRepositoryPort không được để trống."
                );

        this.mediaContract =
                Objects.requireNonNull(
                        mediaContract,
                        "MediaContract không được để trống."
                );

        this.clockPort =
                Objects.requireNonNull(
                        clockPort,
                        "ClockPort không được để trống."
                );
    }

    public NovelProfileDTO execute(
            UpdateNovelProfileCommand command
    ) {
        Objects.requireNonNull(
                command,
                "UpdateNovelProfileCommand không được để trống."
        );

        /*
         * 1. Validate toàn bộ thông tin văn bản trước.
         */
        String title = validateTitle(
                command.title()
        );

        String author = validateAuthor(
                command.author()
        );

        String description = validateDescription(
                command.description()
        );

        String status = validateStatus(
                command.status()
        );

        /*
         * 2. Validate dữ liệu file ảnh bìa (nếu có) trước khi thực hiện bất kỳ side effect nào.
         */
        if (command.coverUpload() != null) {
            validateCoverUpload(
                    command.coverUpload()
            );
        }

        /*
         * 3. Tải hồ sơ hiện tại từ persistence layer.
         */
        NovelProfileDTO existingProfile =
                novelProfileRepositoryPort
                        .findBySlug(
                                DEFAULT_NOVEL_SLUG
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "Không tìm thấy hồ sơ tiểu thuyết mặc định: "
                                        + DEFAULT_NOVEL_SLUG
                        ));

        /*
         * 4. Giải quyết Media Cover:
         *    - Nếu không upload ảnh mới: không tương tác MediaContract, giữ nguyên coverMediaAssetId và coverImageUrl legacy.
         *    - Nếu upload ảnh mới và chưa có coverMediaAssetId: uploadAsset tạo asset mới, lưu coverMediaAssetId mới.
         *    - Nếu upload ảnh mới và đã có coverMediaAssetId: uploadVersion tạo version mới cho asset hiện có, giữ nguyên coverMediaAssetId.
         */
        UUID targetCoverMediaAssetId = existingProfile.coverMediaAssetId();
        boolean isInitialMediaAssetUpload = false;
        UUID newlyCreatedMediaAssetId = null;

        if (command.coverUpload() != null) {
            NovelCoverUpload coverUpload = command.coverUpload();

            if (existingProfile.coverMediaAssetId() == null) {
                UploadMediaAssetRequestDTO uploadRequest = new UploadMediaAssetRequestDTO(
                        coverUpload.content(),
                        coverUpload.sizeBytes(),
                        coverUpload.contentType(),
                        MediaTypeDTO.IMAGE,
                        MediaVisibilityDTO.PUBLIC,
                        coverUpload.originalFilename()
                );

                UploadMediaAssetResponseDTO uploadResponse =
                        mediaContract.uploadAsset(uploadRequest);

                targetCoverMediaAssetId = uploadResponse.assetId();
                newlyCreatedMediaAssetId = uploadResponse.assetId();
                isInitialMediaAssetUpload = true;

            } else {
                UploadMediaAssetVersionRequestDTO uploadVersionRequest = new UploadMediaAssetVersionRequestDTO(
                        existingProfile.coverMediaAssetId(),
                        coverUpload.content(),
                        coverUpload.sizeBytes(),
                        coverUpload.contentType(),
                        coverUpload.originalFilename()
                );

                mediaContract.uploadVersion(uploadVersionRequest);
                targetCoverMediaAssetId = existingProfile.coverMediaAssetId();
            }
        }

        /*
         * 5. Cập nhật hồ sơ trong persistence layer (bảo toàn raw coverImageUrl legacy).
         *    Nếu cập nhật database thất bại sau khi uploadAsset ban đầu thành công, thực hiện compensation xóa media asset mới.
         */
        Instant now = clockPort.now();

        try {
            return novelProfileRepositoryPort.update(
                    DEFAULT_NOVEL_SLUG,
                    title,
                    author,
                    description,
                    existingProfile.coverImageUrl(),
                    targetCoverMediaAssetId,
                    status,
                    now
            );
        } catch (RuntimeException ex) {
            if (isInitialMediaAssetUpload && newlyCreatedMediaAssetId != null) {
                try {
                    mediaContract.delete(newlyCreatedMediaAssetId);
                } catch (Exception compEx) {
                    ex.addSuppressed(compEx);
                }
            }
            throw ex;
        }
    }

    private String validateTitle(
            String title
    ) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(
                    "Tiêu đề tiểu thuyết không được để trống."
            );
        }

        String trimmed = title.trim();

        if (trimmed.length() > 200) {
            throw new IllegalArgumentException(
                    "Tiêu đề tiểu thuyết không được vượt quá 200 ký tự."
            );
        }

        return trimmed;
    }

    private String validateAuthor(
            String author
    ) {
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException(
                    "Tác giả tiểu thuyết không được để trống."
            );
        }

        String trimmed = author.trim();

        if (trimmed.length() > 200) {
            throw new IllegalArgumentException(
                    "Tác giả tiểu thuyết không được vượt quá 200 ký tự."
            );
        }

        return trimmed;
    }

    private String validateDescription(
            String description
    ) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException(
                    "Mô tả tiểu thuyết không được để trống."
            );
        }

        String trimmed = description.trim();

        if (trimmed.length() > 10000) {
            throw new IllegalArgumentException(
                    "Mô tả tiểu thuyết không được vượt quá 10.000 ký tự."
            );
        }

        return trimmed;
    }

    private String validateStatus(
            String status
    ) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException(
                    "Trạng thái tiểu thuyết không được để trống."
            );
        }

        String normalized = status.trim().toUpperCase(Locale.ROOT);

        try {
            NovelStatus.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Trạng thái tiểu thuyết không hợp lệ: "
                            + status
            );
        }

        return normalized;
    }

    private void validateCoverUpload(
            NovelCoverUpload coverUpload
    ) {
        if (coverUpload.content() == null || coverUpload.sizeBytes() <= 0) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn file ảnh bìa hợp lệ."
            );
        }

        if (coverUpload.sizeBytes() > MAX_COVER_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "Ảnh bìa không được vượt quá 5 MB."
            );
        }

        String contentType = coverUpload.contentType();
        if (contentType == null
                || !SUPPORTED_CONTENT_TYPES.contains(
                        contentType.trim().toLowerCase(Locale.ROOT)
                )) {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh định dạng JPG, PNG hoặc WEBP."
            );
        }

        String filename = coverUpload.originalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException(
                    "Tên file ảnh bìa không hợp lệ."
            );
        }

        String extension = extractExtension(filename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh định dạng JPG, PNG hoặc WEBP."
            );
        }
    }

    private String extractExtension(
            String filename
    ) {
        String normalized = filename.trim().replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < normalized.length() - 1) {
            normalized = normalized.substring(lastSlash + 1);
        }

        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == normalized.length() - 1) {
            return "";
        }

        return normalized.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
