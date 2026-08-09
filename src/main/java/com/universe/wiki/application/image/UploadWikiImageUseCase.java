package com.universe.wiki.application.image;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class UploadWikiImageUseCase {

    private static final long MAX_FILE_SIZE =
            5L * 1024 * 1024;

    private static final Set<String>
            SUPPORTED_CONTENT_TYPES =
            Set.of(
                    "image/jpeg",
                    "image/png",
                    "image/webp"
            );

    private static final Set<String>
            SUPPORTED_EXTENSIONS =
            Set.of(
                    "jpg",
                    "jpeg",
                    "png",
                    "webp"
            );

    private final WikiImageStoragePort
            imageStoragePort;

    public UploadWikiImageUseCase(
            WikiImageStoragePort imageStoragePort
    ) {
        this.imageStoragePort =
                Objects.requireNonNull(
                        imageStoragePort,
                        "WikiImageStoragePort không được để trống."
                );
    }

    public WikiImageUploadResult execute(
            String originalFilename,
            String contentType,
            byte[] content
    ) {
        validate(
                originalFilename,
                contentType,
                content
        );

        return imageStoragePort.upload(
                normalizeFilename(
                        originalFilename
                ),
                contentType,
                content
        );
    }

    /*
     * =====================================================
     * VALIDATION
     * =====================================================
     */

    private void validate(
            String originalFilename,
            String contentType,
            byte[] content
    ) {
        validateContent(
                content
        );

        validateContentType(
                contentType
        );

        validateFilename(
                originalFilename
        );
    }

    private void validateContent(
            byte[] content
    ) {
        if (
                content == null
                || content.length == 0
        ) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn một ảnh Wiki."
            );
        }

        if (
                content.length > MAX_FILE_SIZE
        ) {
            throw new IllegalArgumentException(
                    "Ảnh Wiki không được vượt quá 5 MB."
            );
        }
    }

    private void validateContentType(
            String contentType
    ) {
        if (
                contentType == null
                || !SUPPORTED_CONTENT_TYPES.contains(
                        contentType
                )
        ) {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP."
            );
        }
    }

    private void validateFilename(
            String originalFilename
    ) {
        if (
                originalFilename == null
                || originalFilename.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "Tên file ảnh không hợp lệ."
            );
        }

        String extension =
                extractExtension(
                        originalFilename
                );

        if (
                !SUPPORTED_EXTENSIONS.contains(
                        extension
                )
        ) {
            throw new IllegalArgumentException(
                    "Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP."
            );
        }
    }

    /*
     * =====================================================
     * FILENAME
     * =====================================================
     */

    private String normalizeFilename(
            String originalFilename
    ) {
        String normalized =
                originalFilename.trim();

        /*
         * Phòng trường hợp client gửi cả đường dẫn:
         *
         * C:\fakepath\image.png
         * /home/user/image.png
         */
        normalized =
                normalized.replace(
                        '\\',
                        '/'
                );

        int lastSlashIndex =
                normalized.lastIndexOf('/');

        if (
                lastSlashIndex >= 0
                && lastSlashIndex
                        < normalized.length() - 1
        ) {
            normalized =
                    normalized.substring(
                            lastSlashIndex + 1
                    );
        }

        return normalized;
    }

    private String extractExtension(
            String originalFilename
    ) {
        if (
                originalFilename == null
                || originalFilename.isBlank()
        ) {
            return "";
        }

        String normalizedFilename =
                normalizeFilename(
                        originalFilename
                );

        int dotIndex =
                normalizedFilename.lastIndexOf('.');

        if (
                dotIndex < 0
                || dotIndex
                        == normalizedFilename.length() - 1
        ) {
            return "";
        }

        return normalizedFilename
                .substring(
                        dotIndex + 1
                )
                .toLowerCase(
                        Locale.ROOT
                );
    }
}